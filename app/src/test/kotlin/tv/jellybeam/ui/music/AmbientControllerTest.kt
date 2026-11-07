package tv.jellybeam.ui.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.ui.cards.testCard

/** docs/29 §6: the app's one ambient owner over a real music owner, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AmbientControllerTest {

    @get:Rule val main = MainDispatcherRule()

    private val minute = 60_000L

    private val gateway = FakeCoreGateway().apply {
        accountEpoch = 1uL
        albumTracksByAlbum = mapOf(ALBUM to listOf(testCard(id = "a", itemType = "Audio", name = "Song a")))
    }

    private class Playing(val controller: AmbientController, val owner: MusicPlaybackOwner, val g: Long)

    private fun TestScope.playing(): Playing {
        val host = NpHost()
        val owner = MusicPlaybackOwner(gateway, backgroundScope, host, closeVideo = {}, holdsPlaybackChanged = {}, main = UnconfinedTestDispatcher(testScheduler))
        owner.play(MusicSource.Album(ALBUM))
        val g = owner.attach(NpPort())
        owner.send(MusicEvent.ServiceReady(g, host.started.single()))
        owner.publish(g, now(paused = false))
        runCurrent()
        return Playing(AmbientController(owner, backgroundScope, clock = { testScheduler.currentTime }), owner, g)
    }

    private fun now(paused: Boolean) =
        MusicNow(listOf(QueueEntry("a", QueueOrigin.Source)), listOf(0), 0, isPlaying = !paused, positionMs = 0, durationMs = 1_000, shuffle = false, repeat = RepeatMode.Off, paused = paused)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `two idle minutes draw, a block a second before never draws, and counting again starts a full two minutes`() = runTest {
        val p = playing()
        p.controller.setMayCount(true)
        advance(2 * minute - 1_000)
        assertEquals(Ambient.Off(idleSince = 0, paused = false), p.controller.state.value)
        p.controller.setMayCount(false)
        advance(3 * minute)
        assertEquals("nothing counts while blocked", Ambient.Off(idleSince = null, paused = false), p.controller.state.value)
        val shownAt = testScheduler.currentTime
        p.controller.setMayCount(true)
        advance(2 * minute - 1)
        assertEquals(Ambient.Off(idleSince = shownAt, paused = false), p.controller.state.value)
        advance(1)
        assertEquals(Ambient.Drawing(startedAt = shownAt + 2 * minute, pausedSince = null), p.controller.state.value)
    }

    @Test
    fun `a key while drawing is consumed and only closes it, and a key while off passes`() = runTest {
        val p = playing()
        p.controller.setMayCount(true)
        advance(2 * minute)
        assertTrue(p.controller.state.value is Ambient.Drawing)
        advance(30_000)
        val at = testScheduler.currentTime
        assertTrue("the closing key is consumed", p.controller.key())
        assertEquals(Ambient.Off(idleSince = at, paused = false), p.controller.state.value)
        assertFalse("the next key reaches the screen", p.controller.key())
    }

    @Test
    fun `a pause read from the player while counting makes the drawing paused, and ten paused minutes release it`() = runTest {
        val p = playing()
        p.controller.setMayCount(true)
        runCurrent()
        p.owner.publish(p.g, now(paused = true))
        advance(2 * minute)
        assertEquals(Ambient.Drawing(startedAt = 2 * minute, pausedSince = 2 * minute), p.controller.state.value)
        advance(10 * minute)
        assertEquals(Ambient.Released, p.controller.state.value)
        assertTrue(p.controller.key())
        assertEquals(Ambient.Off(idleSince = 12 * minute, paused = true), p.controller.state.value)
    }

    private companion object {
        const val ALBUM = "album-1"
    }
}
