package tv.jellybeam.player

import androidx.annotation.StringRes
import tv.jellybeam.R

/** Media3's current playback state, decoupled from its Android constants so formatting stays pure.
 */
enum class PlaybackLiveState(@StringRes val labelRes: Int) {
    IDLE(R.string.player_live_state_idle),
    PREPARING(R.string.player_live_state_preparing),
    PLAYING(R.string.player_live_state_playing),
    PAUSED(R.string.player_live_state_paused),
    BUFFERING(R.string.player_live_state_buffering),
    ENDED(R.string.player_live_state_ended),
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
