@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // ForwardingPlayer, Commands.Builder, AcceptedResultBuilder

package tv.jellybeam.player

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult

/**
 * One `MediaSession` publishing [PlayerHolder]'s player to the system UI and Assistant (docs/17 §2): scoped to
 * [tv.jellybeam.player.PlaybackActivity], [release]d in `onStop` unless [sessionShouldSurviveStop], and the app's
 * only media-button consumer, routing Stop/FF/REW/seek/Play/Pause through [MediaKeyRouter].
 */
class MediaSessionHolder(
    context: Context,
    player: Player,
    router: MediaKeyRouter,
    /** `PendingIntent.getActivity(..., FLAG_IMMUTABLE)` back to
     * [tv.jellybeam.player.PlaybackActivity], launched by "Now Playing" surfaces.
     */
    sessionActivity: PendingIntent,
) {
    private val mediaSession: MediaSession = MediaSession.Builder(context, RoutedPlayer(player, router))
        .setSessionActivity(sessionActivity)
        .setCallback(
            object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                    grantedCommands(ConnectionResult.AcceptedResultBuilder(session, controller).build())
            },
        )
        .build()

    /** Releases the `MediaSession` only; the shared player it wrapped is never touched. */
    fun release() {
        mediaSession.release()
    }
}

/** docs/17 §3: PiP hidden behind Home keeps playing, so a stop that is not finishing keeps its session; every other stop releases it. */
internal fun sessionShouldSurviveStop(inPip: Boolean, finishing: Boolean): Boolean = inPip && !finishing

/**
 * docs/17 §2: every controller gets exactly [ALLOWED_COMMANDS], trusted or not, since remote remappers and the cast
 * shell are unverifiable apps whose default grant is read-only; Media3 refuses every other call before the shared player.
 */
internal fun grantedCommands(default: ConnectionResult): ConnectionResult =
    ConnectionResult.accept(default.availableSessionCommands, allowedCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS))

/** Playback, stop, in-item seeks and the reads the session publishes; nothing that changes the queue, tracks, volume or surface. */
internal val ALLOWED_COMMANDS: IntArray = intArrayOf(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_STOP,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
    Player.COMMAND_GET_TRACKS,
    Player.COMMAND_GET_DEVICE_VOLUME,
)

/** The [ALLOWED_COMMANDS] that [commands] has. */
internal fun allowedCommands(commands: Player.Commands): Player.Commands =
    Player.Commands.Builder().apply { for (command in ALLOWED_COMMANDS) addIf(command, commands.contains(command)) }.build()

/**
 * docs/17 §2: the commands a remote or Assistant sends the session, answered by [router]. Reports only
 * [ALLOWED_COMMANDS], which is what the platform session advertises (it has no notification controller to narrow it).
 */
internal class RoutedPlayer(player: Player, private val router: MediaKeyRouter) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = allowedCommands(super.getAvailableCommands())

    /** `Util.handlePlayButtonAction` asks this before preparing or restarting, so Play at IDLE/ENDED only reaches [router]. */
    override fun isCommandAvailable(command: Int): Boolean = command in ALLOWED_COMMANDS && super.isCommandAvailable(command)

    override fun getSeekForwardIncrement(): Long = router.seekForwardIncrementMs()

    override fun getSeekBackIncrement(): Long = router.seekBackIncrementMs()

    override fun stop() = router.onStop()

    override fun seekForward() = router.onSeekForward()

    override fun seekBack() = router.onSeekBack()

    override fun seekTo(positionMs: Long) = router.onSeekTo(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = router.onSeekTo(positionMs)

    override fun play() = router.onPlay()

    override fun pause() = router.onPause()

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) router.onPlay() else router.onPause()

    override fun prepare() = Unit

    override fun seekToDefaultPosition() = Unit
}
