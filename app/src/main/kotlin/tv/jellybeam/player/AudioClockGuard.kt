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
import java.nio.ByteBuffer
import kotlin.math.max
import tv.jellybeam.AppGraph
import tv.jellybeam.diag.DiagLog
import tv.jellybeam.visualizer.tap.PcmTap

/** Which rule found a position to be a lie; see [AudioClockGuard]. */
internal enum class ClockRule { START, JUMP, CEILING }

/**
 * docs/18 §5.1: the invariant an audio output's reported position must satisfy -- it can never be
 * further along than the time the output has actually spent playing, unless its own clock runs
 * a little fast. Every seek releases the passthrough AudioTrack and creates a new one, and on a real
 * TV the new track can report a position far ahead within its first 50 ms (the HAL keeps counting the
 * previous stream). Media3 then sees `written <= position`, the audio renderer reports not-ready, the
 * player parks in BUFFERING with a full buffer and nothing can ever change.
 *
 * A position is a lie only when it leads play time by more than [slackUs] AND one rule fires:
 * [ClockRule.START] (first sample, or inside the first [START_WINDOW_US] of play: no clock drifts
 * measurably yet), [ClockRule.JUMP] (one step gains more than [JUMP_US] on play time) or
 * [ClockRule.CEILING] (the lead grows faster than [CEILING_PPM]). A smooth drift below the ceiling is
 * a real clock running fast and is followed: clamping it to wall time desynchronises sound and
 * picture by hundreds of ms per hour (#32). A lie is rebased: the output reports the raw position
 * minus the offset seen at the fault, so the track keeps its own rate, bounded below by
 * `play time - lag` and above by `play time + slack`; the offset ends when the raw position tells
 * the truth again.
 *
 * Pure and JVM-tested; [PlausibleClockAudioOutput] feeds it the output's play/pause/flush calls.
 * Times are microseconds. [lagUs] is the route latency measured on the last healthy output
 * ([learnedLagUs], learnt inside the first [LEARN_UNTIL_PLAYED_US] only, so drift never carries over).
 */
internal class AudioClockGuard(
    private val slackUs: Long = DEFAULT_SLACK_US,
    private val lagUs: Long = 0L,
) {

    private var playingSinceUs = C.TIME_UNSET
    private var playedBeforeUs = 0L
    private var lastReportedUs = C.TIME_UNSET

    /** Set by [onStop]: the position freezes at the last frame while play time runs on, so it measures no latency. */
    private var drained = false

    private var samples = 0
    private var prevRawUs = 0L
    private var prevPlayedUs = 0L
    private var refPlayedUs = C.TIME_UNSET
    private var refLeadUs = 0L
    private var latched = false
    private var offsetUs = 0L

    val isPlaying: Boolean get() = playingSinceUs != C.TIME_UNSET

    /** `play time - reported` seen on this output between [LEARN_AFTER_PLAYED_US] and [LEARN_UNTIL_PLAYED_US] of play, healthy only; [C.TIME_UNSET] until then. */
    var learnedLagUs: Long = C.TIME_UNSET
        private set

    /** What [learnedLagUs] would read without the [onStop] cut-off, for the release log only; [C.TIME_UNSET] until learnt. */
    var unstoppedLagUs: Long = C.TIME_UNSET
        private set

    /** Calls whose output was replaced since the last [onFlush]; the largest excess the output claimed. */
    var clampCount: Int = 0
        private set
    var maxExcessUs: Long = 0
        private set

    /** Faults found per [ClockRule] since the last [onFlush]; [lastRule] is the latest. */
    val ruleCounts = IntArray(ClockRule.entries.size)
    var lastRule: ClockRule? = null
        private set

    /** Raw-position drift against play time; read by the always-on `clock drift` line. */
    val drift = ClockDriftMeter()

    fun onPlay(nowUs: Long) {
        if (playingSinceUs == C.TIME_UNSET) playingSinceUs = nowUs
    }

    fun onPause(nowUs: Long, speed: Float) {
        if (playingSinceUs != C.TIME_UNSET) {
            playedBeforeUs += mediaDuration(nowUs - playingSinceUs, speed)
            playingSinceUs = C.TIME_UNSET
        }
    }

    /** No more data will be written; the frames already written still play out truthfully. */
    fun onStop() {
        drained = true
    }

    fun onFlush() {
        drift.reset()
        drained = false
        playingSinceUs = C.TIME_UNSET
        playedBeforeUs = 0L
        lastReportedUs = C.TIME_UNSET
        samples = 0
        refPlayedUs = C.TIME_UNSET
        latched = false
        offsetUs = 0L
        clampCount = 0
        maxExcessUs = 0
        ruleCounts.fill(0)
        lastRule = null
        learnedLagUs = C.TIME_UNSET
        unstoppedLagUs = C.TIME_UNSET
    }

    /** The position to report for [reportedUs] at [nowUs]; a lie is rebased (see the class), and nothing goes backwards. */
    fun filter(reportedUs: Long, nowUs: Long, speed: Float): Long {
        val playedUs = playedBeforeUs + if (playingSinceUs != C.TIME_UNSET) mediaDuration(nowUs - playingSinceUs, speed) else 0L
        if (playingSinceUs != C.TIME_UNSET) drift.sample(reportedUs, playedUs)
        val boundUs = max(0L, playedUs - lagUs)
        if (latched && reportedUs <= playedUs + slackUs) {
            latched = false
            offsetUs = 0L
            refPlayedUs = C.TIME_UNSET
        }
        var positionUs = reportedUs - offsetUs
        val rule = faultRule(positionUs, reportedUs, playedUs)
        if (rule != null) {
            ruleCounts[rule.ordinal]++
            lastRule = rule
            maxExcessUs = max(maxExcessUs, positionUs - playedUs)
            latched = true
            offsetUs = reportedUs - boundUs
            refPlayedUs = C.TIME_UNSET
            positionUs = boundUs
        } else if (latched) {
            positionUs = positionUs.coerceAtLeast(boundUs).coerceAtMost(playedUs + slackUs)
        } else if (clampCount == 0 && playingSinceUs != C.TIME_UNSET && playedUs in LEARN_AFTER_PLAYED_US..LEARN_UNTIL_PLAYED_US) {
            unstoppedLagUs = max(0L, playedUs - positionUs)
            if (!drained) learnedLagUs = unstoppedLagUs
        }
        if (latched) clampCount++
        if (!latched && refPlayedUs == C.TIME_UNSET && playedUs >= START_WINDOW_US) {
            refPlayedUs = playedUs
            refLeadUs = positionUs - playedUs
        }
        samples++
        prevRawUs = reportedUs
        prevPlayedUs = playedUs
        if (lastReportedUs != C.TIME_UNSET) positionUs = max(positionUs, lastReportedUs)
        lastReportedUs = positionUs
        return positionUs
    }

    /** The rule that makes [effUs] (raw minus offset) a lie, or null; never fires without a lead beyond [slackUs]. */
    private fun faultRule(effUs: Long, rawUs: Long, playedUs: Long): ClockRule? {
        if (effUs <= playedUs + slackUs) return null
        if (samples == 0 || playedUs < START_WINDOW_US) return ClockRule.START
        if ((rawUs - prevRawUs) - (playedUs - prevPlayedUs) > JUMP_US) return ClockRule.JUMP
        val spanUs = playedUs - refPlayedUs
        if (refPlayedUs != C.TIME_UNSET && spanUs >= CEILING_MIN_SPAN_US &&
            (effUs - playedUs - refLeadUs - CEILING_ALLOWANCE_US) * 1_000_000.0 > CEILING_PPM * spanUs.toDouble()
        ) {
            return ClockRule.CEILING
        }
        return null
    }

    private fun mediaDuration(playoutUs: Long, speed: Float): Long =
        if (speed == 1f) playoutUs else (playoutUs * speed.toDouble()).toLong()

    companion object {
        const val DEFAULT_SLACK_US = 100_000L

        /** Latency is read only once the track has settled; the first second of a track is start-up noise. */
        const val LEARN_AFTER_PLAYED_US = 1_000_000L

        /** ...and only while drift cannot have built up (1000 ppm x 10 s = 10 ms). */
        const val LEARN_UNTIL_PLAYED_US = 10_000_000L

        /** Inside it any lead beyond the slack is a lie: no clock gains 100 ms in 2 s. */
        const val START_WINDOW_US = 2_000_000L

        /** One step gaining this much on play time is a jump, not drift. */
        const val JUMP_US = 250_000L

        /** A lead growing faster than this is not a clock rate any real audio crystal has. */
        const val CEILING_PPM = 1000.0
        const val CEILING_MIN_SPAN_US = 1_000_000L

        /** Position-sampling jitter allowed on top of the ceiling, so a clock at exactly the ceiling is not a lie. */
        const val CEILING_ALLOWANCE_US = 5_000L
    }
}

/**
 * docs/18 §5.1: rate of an output's raw reported position against play time, in ppm, for the always-on
 * `clock drift` line. The first sample after [AudioClockGuard.LEARN_AFTER_PLAYED_US] is the reference
 * (start-up noise is over); the rate is the slope since then, so latency cancels out.
 */
internal class ClockDriftMeter {
    private var refPlayedUs = C.TIME_UNSET
    private var refReportedUs = 0L
    private var lastPlayedUs = 0L
    private var lastReportedUs = 0L

    fun reset() {
        refPlayedUs = C.TIME_UNSET
    }

    fun sample(reportedUs: Long, playedUs: Long) {
        if (playedUs < AudioClockGuard.LEARN_AFTER_PLAYED_US) return
        if (refPlayedUs == C.TIME_UNSET) {
            refPlayedUs = playedUs
            refReportedUs = reportedUs
        }
        lastPlayedUs = playedUs
        lastReportedUs = reportedUs
    }

    /** Seconds of play time the rate covers. */
    val spanS: Long get() = if (refPlayedUs == C.TIME_UNSET) 0L else (lastPlayedUs - refPlayedUs) / 1_000_000

    /** Reported minus played advance since the reference, per million; null until 10 s are covered. */
    val ppm: Long?
        get() {
            val spanUs = lastPlayedUs - refPlayedUs
            if (refPlayedUs == C.TIME_UNSET || spanUs < MIN_SPAN_US) return null
            return Math.round(((lastReportedUs - refReportedUs) - spanUs) * 1e6 / spanUs)
        }

    /** `play time - reported` now, in ms: the route latency plus whatever drift has built up. */
    val lagMs: Long get() = (lastPlayedUs - lastReportedUs) / 1000

    companion object {
        const val MIN_SPAN_US = 10_000_000L
    }
}

/** One `audio.clock` diagnostic event (docs/21 §2.1): a fixed [kind], an optional fixed [rule] word, numbers only. */
internal class ClockEvent(val kind: String, val rule: String?, val fields: List<Pair<String, Long>>)

/**
 * docs/21 §2.1: the clock guard's always-on `JellybeamSeek` lines and, when diagnostic logging is on, the
 * same facts as `audio.clock` events for the report. Called on every position read but acts only on a
 * new fault, once a minute and on release, so the hot path allocates nothing and an event is built only
 * when [enabled].
 */
internal class ClockDiagReporter(
    private val outputId: Int,
    startMs: Long,
    private val log: (String) -> Unit = { Log.i(SEEK_LOG_TAG, it) },
    private val enabled: () -> Boolean,
    private val emit: (ClockEvent) -> Unit,
) {
    private var lastDriftMs = startMs

    fun onRead(guard: AudioClockGuard, reportedUs: Long, positionUs: Long, faultsBefore: Int, nowUs: Long) {
        val faults = guard.ruleCounts.sum()
        if (faults > faultsBefore && faults <= MAX_FAULT_EVENTS) {
            val rule = guard.lastRule?.name?.lowercase()
            log("clock clamp rule=$rule reported=${reportedUs / 1000} bound=${positionUs / 1000}")
            if (enabled()) {
                emit(ClockEvent("clamp", rule, listOf("out" to outputId.toLong(), "reported" to reportedUs / 1000, "bound" to positionUs / 1000, "faults" to faults.toLong())))
            }
        }
        val nowMs = nowUs / 1000
        if (nowMs - lastDriftMs >= DRIFT_INTERVAL_MS) {
            lastDriftMs = nowMs
            drift(guard, final = false)
        }
    }

    fun onRelease(guard: AudioClockGuard) {
        drift(guard, final = true)
        if (guard.clampCount > 0) {
            val c = guard.ruleCounts
            log("clock clamps=${guard.clampCount} maxExcess=${guard.maxExcessUs / 1000} start=${c[0]} jump=${c[1]} ceiling=${c[2]}")
            if (enabled()) {
                emit(ClockEvent("summary", null, listOf("out" to outputId.toLong(), "clamps" to guard.clampCount.toLong(), "maxExcess" to guard.maxExcessUs / 1000, "start" to c[0].toLong(), "jump" to c[1].toLong(), "ceiling" to c[2].toLong())))
            }
        } else if (guard.learnedLagUs != C.TIME_UNSET) {
            log("clock lag=${guard.learnedLagUs / 1000} unstopped=${guard.unstoppedLagUs / 1000}")
            if (enabled()) {
                emit(ClockEvent("lag", null, listOf("out" to outputId.toLong(), "lag" to guard.learnedLagUs / 1000, "unstopped" to guard.unstoppedLagUs / 1000)))
            }
        }
    }

    private fun drift(guard: AudioClockGuard, final: Boolean) {
        val ppm = guard.drift.ppm ?: return
        val d = guard.drift
        log("clock drift out=$outputId ppm=$ppm span=${d.spanS} lag=${d.lagMs} clamps=${guard.clampCount}" + if (final) " final=1" else "")
        if (enabled()) {
            emit(ClockEvent("drift", null, listOf("out" to outputId.toLong(), "ppm" to ppm, "span" to d.spanS, "lag" to d.lagMs, "clamps" to guard.clampCount.toLong(), "final" to if (final) 1L else 0L)))
        }
    }

    companion object {
        const val DRIFT_INTERVAL_MS = 60_000L

        /** Faults reported per output, to bound a pathological track's lines and ring use. */
        const val MAX_FAULT_EVENTS = 20
    }
}

/** docs/21 §2.1: hands a [ClockEvent] to the shared diagnostic ring as `audio.clock`; fields are numbers and fixed words only. */
internal fun DiagLog.clockEvent(e: ClockEvent) = event("audio.clock") {
    tag("kind", e.kind)
    e.rule?.let { tag("rule", it) }
    for ((k, v) in e.fields) num(k, v)
}

/** An [AudioOutput] whose position passes through [AudioClockGuard]; see that class. */
@UnstableApi
internal class PlausibleClockAudioOutput(
    delegate: AudioOutput,
    private val clock: Clock,
    private val guard: AudioClockGuard = AudioClockGuard(),
    /** Receives this output's [AudioClockGuard.learnedLagUs] on release, for the next output's guard. */
    private val onLagLearned: (Long) -> Unit = {},
    outputId: Int = nextOutputIdSource.getAndIncrement(),
    private val reporter: ClockDiagReporter = ClockDiagReporter(
        outputId = outputId,
        startMs = clock.nanoTime() / 1_000_000,
        enabled = { AppGraph.diag.enabled },
        emit = { AppGraph.diag.clockEvent(it) },
    ),
    /** docs/30 §2: sees every consumed buffer, flush and position; null on the video player. */
    private val tap: PcmTap? = null,
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
        tap?.pause(clock.nanoTime())
    }

    override fun flush() {
        super.flush()
        guard.onFlush()
        tap?.flush()
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val start = buffer.position()
        val written = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        // Only the bytes the output consumed; a partial write re-offers the rest next call.
        tap?.offer(buffer, start, buffer.position())
        return written
    }

    override fun stop() {
        super.stop()
        guard.onStop()
    }

    override fun getPositionUs(): Long {
        val reportedUs = super.getPositionUs()
        if (reportedUs < 0) return reportedUs
        val faultsBefore = guard.ruleCounts.sum()
        val now = nowUs
        val positionUs = guard.filter(reportedUs, now, speed)
        reporter.onRead(guard, reportedUs, positionUs, faultsBefore, now)
        tap?.onPosition(positionUs, now * 1000, guard.isPlaying, speed)
        return positionUs
    }

    override fun release() {
        reporter.onRelease(guard)
        if (guard.clampCount == 0 && guard.learnedLagUs != C.TIME_UNSET) onLagLearned(guard.learnedLagUs)
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

/** Wraps every output the delegate creates in a [PlausibleClockAudioOutput]; [tap] (music only, docs/30 §2) learns each output's format. */
@UnstableApi
internal class PlausibleClockAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val tap: PcmTap? = null,
) : ForwardingAudioOutputProvider(delegate) {

    private var clock: Clock = Clock.DEFAULT

    /** The route's output latency as last measured on a healthy output; 0 until one has played a second. */
    @Volatile
    private var healthyLagUs: Long = 0L

    override fun setClock(clock: Clock) {
        this.clock = clock
        super.setClock(clock)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = PlausibleClockAudioOutput(
            delegate = super.getAudioOutput(config),
            clock = clock,
            guard = AudioClockGuard(lagUs = healthyLagUs),
            onLagLearned = { healthyLagUs = it },
            tap = tap,
        )
        tap?.configure(config.encoding, config.sampleRate, Integer.bitCount(config.channelMask), config.isOffload)
        return output
    }

    /** Test hook (docs/30 §9 T28): whether this provider feeds a tap. */
    internal val hasTap: Boolean get() = tap != null
}

private const val SEEK_LOG_TAG = "JellybeamSeek"
private val nextOutputIdSource = java.util.concurrent.atomic.AtomicInteger(1)
