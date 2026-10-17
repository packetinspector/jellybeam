package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.MediaSession.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/17 §2: the MediaSession's commands reach the view model's own stop/seek/toggle paths. */
class MediaKeyRouterTest {
    private val calls = mutableListOf<String>()
    private var playing = true
    private var forwardMs = 30_000L
    private var backMs = 10_000L

    private fun router() = MediaKeyRouter(
        stop = { calls += "stop" },
        seekBy = { calls += "seekBy($it)" },
        seekToMs = { calls += "seekToMs($it)" },
        skipForwardMs = { forwardMs },
        skipBackMs = { backMs },
        playWhenReady = { playing },
        togglePlayPause = { calls += "toggle" },
    )

    @Test
    fun `stop calls the view model's stop once`() {
        router().onStop()

        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `fast forward seeks by the skip-forward setting and rewind by the skip-back setting`() {
        val router = router()

        router.onSeekForward()
        router.onSeekBack()
        forwardMs = 15_000L
        backMs = 5_000L
        router.onSeekForward()
        router.onSeekBack()

        assertEquals(listOf("seekBy(30000)", "seekBy(-10000)", "seekBy(15000)", "seekBy(-5000)"), calls)
    }

    @Test
    fun `an absolute seek goes through the view model`() {
        router().onSeekTo(90_000L)

        assertEquals(listOf("seekToMs(90000)"), calls)
    }

    @Test
    fun `play and pause toggle only when they change the intent`() {
        val router = router()

        playing = true
        router.onPlay()
        router.onPause()
        playing = false
        router.onPause()
        router.onPlay()

        assertEquals(listOf("toggle", "toggle"), calls)
    }

    @Test
    fun `every transport key reaches the session hidden or shown, and fast forward and rewind reveal a hidden OSD`() {
        val transport = listOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaStop, Key.MediaFastForward, Key.MediaRewind)
        for (key in transport) {
            val revealing = key == Key.MediaFastForward || key == Key.MediaRewind
            assertEquals("$key hidden", if (revealing) MediaKeyRoute.SESSION_REVEAL_OSD else MediaKeyRoute.SESSION, MediaKeys.route(key, osdVisible = false))
            assertEquals("$key shown", MediaKeyRoute.SESSION, MediaKeys.route(key, osdVisible = true))
        }
        for (key in listOf(Key.DirectionLeft, Key.DirectionCenter, Key.Back, Key.MediaNext, Key.MediaPrevious, Key.PageUp)) {
            assertEquals("$key", MediaKeyRoute.NOT_TRANSPORT, MediaKeys.route(key, osdVisible = false))
        }
    }

    /** A wrapped player in [state] that fails the test on any call that is not a plain read. */
    private fun wrappedIn(state: Int, commands: Player.Commands = Player.Commands.Builder().addAllCommands().build()): Player {
        return java.lang.reflect.Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> false
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "getAvailableCommands" -> commands
                "isCommandAvailable" -> commands.contains(args[0] as Int)
                else -> error("reached the wrapped player: ${method.name}")
            }
        } as Player
    }

    /** docs/17 §2, docs/12 §9: Media3's own Play handling must act through the router, never restart or prepare the wrapped player. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun `Play and PlayPause at ENDED or IDLE reach the router and never prepare or restart the wrapped player`() {
        for (state in listOf(Player.STATE_ENDED, Player.STATE_IDLE)) {
            calls.clear()
            playing = false
            val session = RoutedPlayer(wrappedIn(state), router())

            Util.handlePlayButtonAction(session)
            Util.handlePlayPauseButtonAction(session)
            session.setPlayWhenReady(true)
            session.setPlayWhenReady(false)

            assertEquals("state $state: only the router acts", listOf("toggle", "toggle", "toggle"), calls)
        }
    }

    @Test
    fun `the session reports the skip settings as its seek increments and never prepares or restarts the wrapped player`() {
        val session = RoutedPlayer(wrappedIn(Player.STATE_READY), router())
        session.seekToDefaultPosition()
        session.prepare()

        assertEquals(30_000L, session.seekForwardIncrement)
        assertEquals(10_000L, session.seekBackIncrement)
        forwardMs = 15_000L
        backMs = 5_000L
        assertEquals(15_000L, session.seekForwardIncrement)
        assertEquals(5_000L, session.seekBackIncrement)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the session's player answers stop, seek and play-pause from the router and never touches the wrapped player`() {
        // Any call that reaches the wrapped player fails the test.
        val wrapped = java.lang.reflect.Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            error("reached the wrapped player: ${method.name}")
        } as Player
        val session = RoutedPlayer(wrapped, router())

        playing = false
        session.stop()
        session.seekForward()
        session.seekBack()
        session.seekTo(5_000L)
        session.seekTo(0, 6_000L)
        session.play()
        session.pause()

        assertEquals(
            listOf("stop", "seekBy(30000)", "seekBy(-10000)", "seekToMs(5000)", "seekToMs(6000)", "toggle"),
            calls,
        )
    }

    private val transportKeyCodes = listOf(
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_STOP,
        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        android.view.KeyEvent.KEYCODE_HEADSETHOOK,
    )

    /** docs/12 §9, docs/17 §2: before the session exists (and for a consumed key's UP) a transport key must not fall to another app. */
    @Test
    fun `the activity consumes exactly the transport keys while no session exists`() {
        val others = listOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_BACK,
            android.view.KeyEvent.KEYCODE_ESCAPE,
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_PAGE_UP,
        )
        for (keyCode in transportKeyCodes + others) {
            val transport = keyCode in transportKeyCodes
            assertEquals("$keyCode", transport, MediaKeys.consumeWithoutSession(keyCode))
            assertEquals("$keyCode no session", transport, MediaKeys.dispatchConsumed(keyCode, sessionLive = false))
            assertFalse("$keyCode session live", MediaKeys.dispatchConsumed(keyCode, sessionLive = true))
        }
    }

    /** docs/17 §3: only a PiP stop that is not finishing keeps the session. */
    @Test
    fun `a stop keeps the session only in PiP and not finishing`() {
        for (inPip in listOf(false, true)) {
            for (finishing in listOf(false, true)) {
                assertEquals("inPip=$inPip finishing=$finishing", inPip && !finishing, sessionShouldSurviveStop(inPip, finishing))
            }
        }
    }

    private val allowedCommands = setOf(
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

    private fun commandConstants(): Map<String, Int> = Player::class.java.fields
        .filter { it.name.startsWith("COMMAND_") && it.type == Int::class.javaPrimitiveType }
        .associate { it.name to it.getInt(null) }

    /** docs/17 §2: the session reports an allowlist of commands, which is what the platform session advertises. */
    @Test
    fun `the session offers exactly the allowlisted commands the wrapped player has`() {
        val everything = RoutedPlayer(wrappedIn(Player.STATE_READY), router())
        val nothing = RoutedPlayer(wrappedIn(Player.STATE_READY, Player.Commands.EMPTY), router())
        assertTrue(commandConstants().size > 30)
        for ((name, command) in commandConstants()) {
            assertEquals("$name available", command in allowedCommands, everything.isCommandAvailable(command))
            assertEquals("$name advertised", command in allowedCommands, everything.availableCommands.contains(command))
            assertFalse("$name without the wrapped player's support", nothing.isCommandAvailable(command))
            assertFalse("$name advertised without support", nothing.availableCommands.contains(command))
        }
    }

    /** docs/17 §2: a remapped remote is an untrusted controller, so it gets the same allowlist as a trusted one and nothing else. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun `every connecting controller, trusted or not, is granted exactly the allowlisted commands`() {
        val trusted = grantedCommands(ConnectionResult.accept(ConnectionResult.DEFAULT_SESSION_COMMANDS, ConnectionResult.DEFAULT_PLAYER_COMMANDS))
        val untrusted = grantedCommands(ConnectionResult.accept(ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS, ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS))
        for ((name, command) in commandConstants()) {
            assertEquals("$name trusted", command in allowedCommands, trusted.availablePlayerCommands.contains(command))
            assertEquals("$name untrusted", command in allowedCommands, untrusted.availablePlayerCommands.contains(command))
        }
        assertTrue(trusted.isAccepted)
        assertEquals(ConnectionResult.DEFAULT_SESSION_COMMANDS, trusted.availableSessionCommands)
        assertEquals(ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS, untrusted.availableSessionCommands)
    }
}
