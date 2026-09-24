package tv.jellybeam.player

/** Media3's current playback state, decoupled from its Android constants so formatting stays pure.
 */
enum class PlaybackLiveState(val displayName: String) {
    IDLE("Idle"),
    PREPARING("Preparing"),
    PLAYING("Playing"),
    PAUSED("Paused"),
    BUFFERING("Buffering"),
    ENDED("Ended"),
}

/** Raw live values sampled from the shared Media3 player; feeds [StatsSheetFormat.build]'s `live`
 * parameter.
 */
data class PlaybackLiveStats(
    val bufferedAheadMs: Long,
    val allocatedBufferBytes: Long,
    val bandwidthBytesPerSecond: Long,
    val state: PlaybackLiveState,
    val droppedFrames: Long,
)
