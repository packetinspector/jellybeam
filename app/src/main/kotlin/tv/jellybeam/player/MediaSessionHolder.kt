package tv.jellybeam.player

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * Thin wrapper around one `androidx.media3.session.MediaSession`, publishing [PlayerHolder]'s
 * shared
 * player to the TV system UI / global play-pause / Assistant while alive.
 *
 * - **Lifetime**: scoped to one [tv.jellybeam.player.PlaybackActivity], not to [PlayerHolder]'s
 * process-wide lifetime -- there's no `MediaSessionService`/background-audio intent, so the session
 * must not outlive the Activity (nothing plays once it's gone). Created in `onCreate`, [release]d
 * in
 *   `onStop`.
 * - **Media-button ownership**: this is the app's only consumer of hardware/remote media-button
 *   events
 * (`onPreviewKeyEvent` and every Activity ignore `KEYCODE_MEDIA_*`); no double-fire to guard
 * against.
 * - No decision logic here to unit test; title text is [PlaybackBreadcrumb.format], built in
 *   [PlayerHolder.load].
 */
class MediaSessionHolder(
    context: Context,
    player: Player,
    /** `PendingIntent.getActivity(..., FLAG_IMMUTABLE)` back to
     * [tv.jellybeam.player.PlaybackActivity], launched by "Now Playing" surfaces.
     */
    sessionActivity: PendingIntent,
) {
    private val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setSessionActivity(sessionActivity)
        .build()

    /** Releases the `MediaSession` only; the shared player it wrapped is never touched. */
    fun release() {
        mediaSession.release()
    }
}
