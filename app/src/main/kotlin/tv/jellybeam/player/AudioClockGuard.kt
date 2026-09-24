package tv.jellybeam.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import kotlin.math.max

/**
 * docs/18 §5.1: the invariant an audio output's reported position must satisfy -- it can never be
 * further along than the time the output has actually spent playing. Every seek releases the
 * passthrough AudioTrack and creates a new one, and on a real TV the new track can report
 * a position several seconds ahead within its first 50 ms (the HAL keeps counting the previous
 * stream). Media3 then sees `written <= position`, the audio renderer reports not-ready, the player
 * parks in BUFFERING with a full buffer and nothing can ever change. Bounding the position here
 * keeps the renderer ready and the video clock honest, so the break never happens.
 *
 * Pure and JVM-tested; [PlausibleClockAudioOutput] feeds it the output's play/pause/flush calls.
 * Times are microseconds. [slackUs] is the lead a truthful position is allowed over play time
 * (clock sampling and timestamp extrapolation jitter) before it counts as a lie; a real track
 * always lags play time by the route's latency. A lie is replaced by `play time - lag`, where
 * [lagUs] is that latency as measured on the last healthy output ([learnedLagUs]), so a track
 * whose offset never corrects still keeps lip sync instead of running a constant lead.
 */
internal class AudioClockGuard(
    private val slackUs: Long = DEFAULT_SLACK_US,
    private val lagUs: Long = 0L,
) {

    private var playingSinceUs = C.TIME_UNSET
    private var playedBeforeUs = 0L
    private var lastReportedUs = C.TIME_UNSET

    val isPlaying: Boolean get() = playingSinceUs != C.TIME_UNSET

    /** `play time - reported` seen on this output after [LEARN_AFTER_PLAYED_US] of play, healthy only; [C.TIME_UNSET] until then. */
    var learnedLagUs: Long = C.TIME_UNSET
        private set

    /** Total clamps applied since the last [onFlush]; the largest excess the output claimed. */
    var clampCount: Int = 0
        private set
    var maxExcessUs: Long = 0
        private set

    fun onPlay(nowUs: Long) {
        if (playingSinceUs == C.TIME_UNSET) playingSinceUs = nowUs
    }

    fun onPause(nowUs: Long, speed: Float) {
        if (playingSinceUs != C.TIME_UNSET) {
            playedBeforeUs += mediaDuration(nowUs - playingSinceUs, speed)
            playingSinceUs = C.TIME_UNSET
        }
    }

    fun onFlush() {
        playingSinceUs = C.TIME_UNSET
        playedBeforeUs = 0L
        lastReportedUs = C.TIME_UNSET
        clampCount = 0
        maxExcessUs = 0
        learnedLagUs = C.TIME_UNSET
    }

    /** The position to report for [reportedUs] at [nowUs]; a lie becomes `play time - lag`, and nothing goes backwards. */
    fun filter(reportedUs: Long, nowUs: Long, speed: Float): Long {
        val playedUs = playedBeforeUs + if (playingSinceUs != C.TIME_UNSET) mediaDuration(nowUs - playingSinceUs, speed) else 0L
        var positionUs = reportedUs
        if (positionUs > playedUs + slackUs) {
            clampCount++
            maxExcessUs = max(maxExcessUs, positionUs - playedUs)
            positionUs = max(0L, playedUs - lagUs)
        } else if (clampCount == 0 && playingSinceUs != C.TIME_UNSET && playedUs >= LEARN_AFTER_PLAYED_US) {
            learnedLagUs = max(0L, playedUs - positionUs)
        }
        if (lastReportedUs != C.TIME_UNSET) positionUs = max(positionUs, lastReportedUs)
        lastReportedUs = positionUs
        return positionUs
    }

    private fun mediaDuration(playoutUs: Long, speed: Float): Long =
        if (speed == 1f) playoutUs else (playoutUs * speed.toDouble()).toLong()

    companion object {
        const val DEFAULT_SLACK_US = 100_000L

        /** Latency is read only once the track has settled; the first second of a track is start-up noise. */
        const val LEARN_AFTER_PLAYED_US = 1_000_000L
    }
}

/** An [AudioOutput] whose position passes through [AudioClockGuard]; see that class. */
@UnstableApi
internal class PlausibleClockAudioOutput(
    delegate: AudioOutput,
    private val clock: Clock,
    private val guard: AudioClockGuard = AudioClockGuard(),
    /** Receives this output's [AudioClockGuard.learnedLagUs] on release, for the next output's guard. */
    private val onLagLearned: (Long) -> Unit = {},
) : ForwardingAudioOutput(delegate) {

    private val nowUs: Long get() = clock.nanoTime() / 1000

    private val speed: Float get() = playbackParameters.speed

    override fun play() {
        super.play()
        guard.onPlay(nowUs)
    }

    override fun pause() {
        guard.onPause(nowUs, speed)
        super.pause()
    }

    override fun flush() {
        super.flush()
        guard.onFlush()
    }

    override fun getPositionUs(): Long {
        val reportedUs = super.getPositionUs()
        if (reportedUs < 0) return reportedUs
        val clampsBefore = guard.clampCount
        val positionUs = guard.filter(reportedUs, nowUs, speed)
        if (clampsBefore == 0 && guard.clampCount == 1) {
            // First clamp on this output only: numbers, always on, same tag as the seek log.
            Log.i(SEEK_LOG_TAG, "clock clamp reported=${reportedUs / 1000} bound=${positionUs / 1000}")
        }
        return positionUs
    }

    override fun release() {
        if (guard.clampCount > 0) {
            Log.i(SEEK_LOG_TAG, "clock clamps=${guard.clampCount} maxExcess=${guard.maxExcessUs / 1000}")
        } else if (guard.learnedLagUs != C.TIME_UNSET) {
            onLagLearned(guard.learnedLagUs)
        }
        super.release()
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        // Speed changes are accounted from now on at the new rate: settle the old rate first.
        val now = nowUs
        val wasPlaying = guard.isPlaying
        guard.onPause(now, speed)
        super.setPlaybackParameters(playbackParams)
        if (wasPlaying) guard.onPlay(now)
    }
}

/** Wraps every output the delegate creates in a [PlausibleClockAudioOutput]. */
@UnstableApi
internal class PlausibleClockAudioOutputProvider(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {

    private var clock: Clock = Clock.DEFAULT

    /** The route's output latency as last measured on a healthy output; 0 until one has played a second. */
    @Volatile
    private var healthyLagUs: Long = 0L

    override fun setClock(clock: Clock) {
        this.clock = clock
        super.setClock(clock)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
        PlausibleClockAudioOutput(
            delegate = super.getAudioOutput(config),
            clock = clock,
            guard = AudioClockGuard(lagUs = healthyLagUs),
            onLagLearned = { healthyLagUs = it },
        )
}

private const val SEEK_LOG_TAG = "JellybeamSeek"
