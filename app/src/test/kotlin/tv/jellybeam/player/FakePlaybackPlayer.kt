package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.TrackDecisionFfi

/**
 * [PlaybackPlayer] test double for [PlaybackViewModel] tests; no real ExoPlayer/decoder involved.
 * Listener callbacks are fired manually via [fireIsPlayingChanged], [firePlaybackStateChanged],
 * [fireError], etc.
 */
class FakePlaybackPlayer : PlaybackPlayer {
    /** docs/18 §3.2: what [assSidecarTarget] answers; null (full styling off) reads ASS as plain cues. */
    var assTarget: (Int) -> AssSidecarTarget? = { null }

    /** Every [showAssSidecar] call, in order. */
    val shownAssSidecars = mutableListOf<AssSidecarTarget?>()

    override fun assSidecarTarget(index: Int): AssSidecarTarget? = assTarget(index)

    override fun showAssSidecar(target: AssSidecarTarget?) {
        shownAssSidecars += target
    }

    var loadedPlan: PlaybackPlan? = null
        private set

    /** Every plan [load]ed, in order. */
    val loadedPlans = mutableListOf<PlaybackPlan>()

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

    /** The `fullAssStyling` snapshot the last [load] received. */
    var lastFullAssStyling: Boolean? = null

    /** Backing value for [seekability]; set UNSEEKABLE or UNKNOWN to model those files. */
    var seekabilityValue: Seekability = Seekability.SEEKABLE

    override val seekability: Seekability get() = seekabilityValue

    override fun load(
        plan: PlaybackPlan,
        tolerateMislabeledLevels: Boolean,
        audioDecoderPreferences: AudioDecoderPreferences,
        fullAssStyling: Boolean,
    ) {
        loadedPlan = plan
        lastFullAssStyling = fullAssStyling
        loadedPlans += plan
        // The real per-load reset (docs/18 §3.1), so a test sees what a reload does to text.
        trackSelectionParameters = trackSelectionBaseline(trackSelectionParameters)
        currentTracks = null
        lastTolerateMislabeledLevels = tolerateMislabeledLevels
        lastAudioDecoderPreferences = audioDecoderPreferences
        playWhenReady = true // as PlayerHolder.load
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

    /** The selection state [PlayerHolder] would hold, changed by the same pure functions. */
    var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.Builder().build()
        private set

    val textDisabled: Boolean get() = C.TRACK_TYPE_TEXT in trackSelectionParameters.disabledTrackTypes

    /** The (group, track) the text override selects in [tracks], or `null`. */
    fun selectedTextOverride(tracks: Tracks): Pair<Int, Int>? = overrideIn(tracks, C.TRACK_TYPE_TEXT)

    fun selectedAudioOverride(tracks: Tracks): Pair<Int, Int>? = overrideIn(tracks, C.TRACK_TYPE_AUDIO)

    private fun overrideIn(tracks: Tracks, type: Int): Pair<Int, Int>? {
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.mediaTrackGroup.type != type) return@forEachIndexed
            val override = trackSelectionParameters.overrides[group.mediaTrackGroup] ?: return@forEachIndexed
            return groupIndex to override.trackIndices.single()
        }
        return null
    }

    override fun applyTrackDecision(decision: TrackDecisionFfi, tracks: Tracks) {
        applyTrackDecisionCalls.add(decision to tracks)
        trackSelectionParameters = withTrackDecision(trackSelectionParameters, decision, tracks)
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

    /** Simulates `Player.Listener.onTimelineChanged` alone, with no state change beside it. */
    fun fireTimelineChanged() {
        listeners.toList().forEach { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }
    }

    fun fireError(error: PlaybackException) {
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    /** Simulates `Player.Listener.onRenderedFirstFrame()` (docs/12 "Buffering treatments"). */
    fun fireRenderedFirstFrame() {
        listeners.toList().forEach { it.onRenderedFirstFrame() }
    }
}
