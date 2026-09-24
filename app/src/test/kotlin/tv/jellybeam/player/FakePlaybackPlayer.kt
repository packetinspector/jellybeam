package tv.jellybeam.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.TrackDecisionFfi

/**
 * [PlaybackPlayer] test double for [PlaybackViewModel] tests; no real ExoPlayer/decoder involved.
 * Listener callbacks are fired manually via [fireIsPlayingChanged], [firePlaybackStateChanged],
 * [fireError], etc.
 */
class FakePlaybackPlayer : PlaybackPlayer {
    var loadedPlan: PlaybackPlan? = null
        private set

    /** The `tolerateMislabeledLevels` argument from the most recent [load] call, or `null` before
     * any call.
     */
    var lastTolerateMislabeledLevels: Boolean? = null
        private set

    var lastAudioDecoderPreferences: AudioDecoderPreferences? = null
        private set

    private val listeners = mutableListOf<Player.Listener>()

    var togglePlayPauseCallCount: Int = 0
        private set

    /** Every [setPlaybackRate] call, in order. */
    val setPlaybackRateCalls = mutableListOf<Float>()

    /** The most recent [setPlaybackRate] argument, or `1f` before any call -- mirrors a
     * fresh/never-touched player's own default rate.
     */
    var lastPlaybackRate: Float = 1f
        private set

    override fun setPlaybackRate(rate: Float) {
        setPlaybackRateCalls.add(rate)
        lastPlaybackRate = rate
    }

    val seekCalls = mutableListOf<Long>()

    var stopAndClearCallCount: Int = 0
        private set

    var positionTicks: Long = 0L

    /** Backing value for [bufferedPositionTicks] -- settable so a test can simulate buffering ahead
     * of/behind [positionTicks].
     */
    var bufferedPositionTicksValue: Long = 0L

    /** Backing value for [playWhenReady] -- defaults to `true`, matching a loaded/started session.
     */
    override var playWhenReady: Boolean = true

    /** Backing value for [bandwidthBytesPerSecond]; `0L` default means no sample has landed yet. */
    var bandwidthBytesPerSecondValue: Long = 0L

    /** Set by tests to model the real load policy handing an exhausted outage to the player layer.
     */
    var loadRetryBudgetExhausted: Boolean = false

    override fun bandwidthBytesPerSecond(): Long = bandwidthBytesPerSecondValue

    var livePlaybackStatsValue = PlaybackLiveStats(
        bufferedAheadMs = 0L,
        allocatedBufferBytes = 0L,
        bandwidthBytesPerSecond = 0L,
        state = PlaybackLiveState.IDLE,
        droppedFrames = 0L,
    )

    override fun livePlaybackStats(): PlaybackLiveStats = livePlaybackStatsValue

    override fun hasExhaustedLoadRetryBudget(): Boolean = loadRetryBudgetExhausted

    override var isPlaying: Boolean = false
    override var playbackState: Int = Player.STATE_IDLE

    override fun load(
        plan: PlaybackPlan,
        tolerateMislabeledLevels: Boolean,
        audioDecoderPreferences: AudioDecoderPreferences,
    ) {
        loadedPlan = plan
        lastTolerateMislabeledLevels = tolerateMislabeledLevels
        lastAudioDecoderPreferences = audioDecoderPreferences
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun togglePlayPause() {
        togglePlayPauseCallCount++
    }

    var pauseCallCount: Int = 0
        private set

    override fun pause() {
        playWhenReady = false
        isPlaying = false
        pauseCallCount++
    }

    override fun seekBy(deltaMs: Long) {
        seekCalls.add(deltaMs)
    }

    override fun stopAndClear() {
        stopAndClearCallCount++
    }

    /** Every [retryAfterError] call, in order, as `(positionTicks, playing)`. */
    val retryAfterErrorCalls = mutableListOf<Pair<Long, Boolean>>()

    /**
     * Mirrors [PlayerHolder]'s `retryAfterError`: updates [positionTicks] and [playWhenReady].
     * Pair with [firePlaybackStateChanged] to a non-`STATE_IDLE` state to simulate the retry
     * recovering.
     */
    override fun retryAfterError(positionTicks: Long, playing: Boolean) {
        retryAfterErrorCalls.add(positionTicks to playing)
        this.positionTicks = positionTicks
        this.playWhenReady = playing
    }

    override fun currentPositionTicks(): Long = positionTicks

    override fun bufferedPositionTicks(): Long = bufferedPositionTicksValue

    override var currentTracks: Tracks? = null

    /** Settable directly by a test; no `fire*` wrapper needed since only one consumer is ever
     * wired, unlike the [Player.Listener] list above.
     */
    override var onVideoDecoderInitialized: ((String) -> Unit)? = null

    val applyTrackDecisionCalls = mutableListOf<Pair<TrackDecisionFfi, Tracks>>()

    override fun applyTrackDecision(decision: TrackDecisionFfi, tracks: Tracks) {
        applyTrackDecisionCalls.add(decision to tracks)
    }

    /** Updates [currentTracks] and fires every registered [Player.Listener]'s `onTracksChanged`. */
    fun fireTracksChanged(tracks: Tracks) {
        currentTracks = tracks
        listeners.toList().forEach { it.onTracksChanged(tracks) }
    }

    /** Whether [addListener] has ever been called without a matching [removeListener] for the same
     * listener.
     */
    val hasActiveListener: Boolean get() = listeners.isNotEmpty()

    fun fireIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    fun firePlaybackStateChanged(state: Int) {
        playbackState = state
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    /** Simulates `Player.Listener.onPlayWhenReadyChanged`. [reason] is a placeholder;
     * [PlaybackViewModel] never inspects its value.
     */
    fun firePlayWhenReadyChanged(playWhenReady: Boolean, reason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
        this.playWhenReady = playWhenReady
        listeners.toList().forEach { it.onPlayWhenReadyChanged(playWhenReady, reason) }
    }

    fun fireError(error: PlaybackException) {
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    /** Simulates `Player.Listener.onRenderedFirstFrame()` (docs/12 "Buffering treatments"). */
    fun fireRenderedFirstFrame() {
        listeners.toList().forEach { it.onRenderedFirstFrame() }
    }
}
