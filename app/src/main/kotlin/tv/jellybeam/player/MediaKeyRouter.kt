package tv.jellybeam.player

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key

/**
 * docs/17 §2: the MediaSession's transport commands routed to the [PlaybackViewModel], so a
 * remote's Stop/FF/REW/Play/Pause/Next/Previous end, seek, toggle and skip exactly as Back and the on-screen controls
 * do (Stop reports the final position; FF/REW use the viewer's skip settings), not as raw Media3
 * calls that bypass the app.
 */
class MediaKeyRouter(
    private val stop: () -> Unit,
    private val seekBy: (deltaMs: Long) -> Unit,
    private val seekToMs: (positionMs: Long) -> Unit,
    private val skipForwardMs: () -> Long,
    private val skipBackMs: () -> Long,
    /** docs/31 §4.2: the view model's idempotent, group-aware play and pause, so a repeated Play never pauses. */
    private val play: () -> Unit,
    private val pause: () -> Unit,
    /** docs/31 §4.1.1: the on-screen Next and Previous buttons' calls (group-aware, walking the remote list first). */
    private val next: () -> Unit,
    private val previous: () -> Unit,
    private val hasNext: () -> Boolean,
    private val hasPrevious: () -> Boolean,
) {
    fun onStop() = stop()

    fun onSeekForward() = seekBy(skipForwardMs())

    fun onSeekBack() = seekBy(-skipBackMs())

    /** The viewer's skip settings, which the session reports as its seek increments. */
    fun seekForwardIncrementMs(): Long = skipForwardMs()

    fun seekBackIncrementMs(): Long = skipBackMs()

    fun onSeekTo(positionMs: Long) = seekToMs(positionMs)

    fun onPlay() = play()

    fun onPause() = pause()

    fun onNext() = next()

    fun onPrevious() = previous()

    /** docs/12 §8: the session offers a skip only while the OSD shows its button. */
    fun canSkipNext(): Boolean = hasNext()

    fun canSkipPrevious(): Boolean = hasPrevious()
}

/** docs/12 §9: what [PlaybackScreen]'s key handler does with a hardware media key. */
enum class MediaKeyRoute {
    /** Not a session transport key; the handler treats it as any other key. */
    NOT_TRANSPORT,

    /** Left unconsumed so the MediaSession ([MediaKeyRouter]) acts on it. */
    SESSION,

    /** As [SESSION], and the hidden OSD is briefly revealed to show the result. */
    SESSION_REVEAL_OSD,
}

/**
 * docs/12 §9: every transport key reaches the session, OSD hidden or shown, so one owner acts on it;
 * hidden Fast Forward / Rewind also reveal the OSD (they move the playhead), while Play / Pause
 * stay silent and Stop leaves. While no session of ours exists [PlaybackActivity] swallows them
 * ([consumeWithoutSession]) so they cannot reach another app's session.
 */
object MediaKeys {
    private val SILENT: Set<Key> = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaStop, Key.MediaNext, Key.MediaPrevious)
    private val REVEALING: Set<Key> = setOf(Key.MediaFastForward, Key.MediaRewind)

    /** Every key Android hands the app's media session, or another app's when ours is absent. */
    private val TRANSPORT_KEY_CODES: Set<Int> = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_HEADSETHOOK,
    )

    fun route(key: Key, osdVisible: Boolean): MediaKeyRoute = when {
        key !in SILENT && key !in REVEALING -> MediaKeyRoute.NOT_TRANSPORT
        key in SILENT -> MediaKeyRoute.SESSION
        osdVisible -> MediaKeyRoute.SESSION
        else -> MediaKeyRoute.SESSION_REVEAL_OSD
    }

    /** docs/17 §2: a transport key code, whose DOWN and UP alike must not fall to another app. */
    fun consumeWithoutSession(keyCode: Int): Boolean = keyCode in TRANSPORT_KEY_CODES

    /** [PlaybackActivity.dispatchKeyEvent]'s whole decision, for every key action. */
    fun dispatchConsumed(keyCode: Int, sessionLive: Boolean): Boolean = !sessionLive && consumeWithoutSession(keyCode)
}
