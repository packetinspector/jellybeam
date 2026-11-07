package tv.jellybeam.ui.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/29 §6 (M1/M2) and the K6 ambient table: named rows, then every event sequence up to length four. */
class AmbientLogicTest {

    private val minute = 60_000L

    private fun run(start: Ambient, vararg events: AmbientEvent): Ambient = events.fold(start) { s, e -> reduceAmbient(s, e) }

    private val counting = run(AMBIENT_START, AmbientEvent.Shown(0))

    @Test
    fun `nothing counts until ambient may count`() {
        assertNull(ambientDeadline(AMBIENT_START))
        assertEquals(AMBIENT_START, run(AMBIENT_START, AmbientEvent.Tick(60 * minute)))
        assertEquals(2 * minute, ambientDeadline(counting))
    }

    @Test
    fun `two minutes with no input starts drawing`() {
        assertEquals(counting, run(counting, AmbientEvent.Tick(2 * minute - 1)))
        assertEquals(Ambient.Drawing(startedAt = 2 * minute, pausedSince = null), run(counting, AmbientEvent.Tick(2 * minute)))
    }

    @Test
    fun `a key restarts the idle count and is not consumed while off`() {
        val s = run(counting, AmbientEvent.Key(90_000))
        assertFalse(ambientConsumesKey(counting))
        assertEquals(90_000 + 2 * minute, ambientDeadline(s))
        assertEquals(s, run(s, AmbientEvent.Tick(2 * minute)))
    }

    @Test
    fun `any key while drawing only closes it and is consumed`() {
        val drawing = run(counting, AmbientEvent.Tick(2 * minute))
        assertTrue(ambientConsumesKey(drawing))
        assertEquals(Ambient.Off(idleSince = 3 * minute, paused = false), run(drawing, AmbientEvent.Key(3 * minute)))
    }

    @Test
    fun `ambient may count only while the app is in front with nothing open and no keyboard`() {
        assertTrue(ambientMayCount(resumed = true, blockers = 0, imeVisible = false))
        assertFalse("a video or the launcher is in front", ambientMayCount(resumed = false, blockers = 0, imeVisible = false))
        assertFalse("a dialog, menu or panel is open", ambientMayCount(resumed = true, blockers = 1, imeVisible = false))
        assertFalse("two of them", ambientMayCount(resumed = true, blockers = 2, imeVisible = false))
        assertFalse("the keyboard takes the keys", ambientMayCount(resumed = true, blockers = 0, imeVisible = true))
        assertFalse(ambientMayCount(resumed = false, blockers = 3, imeVisible = true))
    }

    @Test
    fun `blocked a second before the deadline never draws, and counting again starts a full two minutes`() {
        val blocked = run(counting, AmbientEvent.Hidden)
        assertEquals(Ambient.Off(idleSince = null, paused = false), run(counting, AmbientEvent.Tick(2 * minute - 1_000), AmbientEvent.Hidden))
        assertNull("no timer while blocked", ambientDeadline(blocked))
        assertEquals("a late tick while blocked never draws", blocked, run(blocked, AmbientEvent.Tick(3 * minute)))
        val again = run(blocked, AmbientEvent.Shown(4 * minute))
        assertEquals(6 * minute, ambientDeadline(again))
        assertEquals(again, run(again, AmbientEvent.Tick(6 * minute - 1)))
        assertEquals(Ambient.Drawing(startedAt = 6 * minute, pausedSince = null), run(again, AmbientEvent.Tick(6 * minute)))
    }

    @Test
    fun `a key while drawing turns it off counting from that key, and changes nothing else`() {
        val drawing = run(counting, AmbientEvent.Tick(2 * minute), AmbientEvent.TrackChanged)
        assertTrue(ambientConsumesKey(drawing))
        val off = run(drawing, AmbientEvent.Key(5 * minute))
        assertEquals(Ambient.Off(idleSince = 5 * minute, paused = false), off)
        assertEquals(7 * minute, ambientDeadline(off))
    }

    @Test
    fun `while drawing and playing no timer runs`() {
        assertNull(ambientDeadline(run(counting, AmbientEvent.Tick(2 * minute))))
    }

    @Test
    fun `a track change while drawing moves to a new start point`() {
        val drawing = run(counting, AmbientEvent.Tick(2 * minute))
        assertEquals(Ambient.Drawing(2 * minute, null, startPoint = 1), run(drawing, AmbientEvent.TrackChanged))
        assertEquals(counting, run(counting, AmbientEvent.TrackChanged))
    }

    @Test
    fun `ten minutes paused while drawing releases the screen`() {
        val paused = run(counting, AmbientEvent.Tick(2 * minute), AmbientEvent.Paused(3 * minute))
        assertEquals(Ambient.Drawing(2 * minute, pausedSince = 3 * minute), paused)
        assertEquals(13 * minute, ambientDeadline(paused))
        assertEquals(paused, run(paused, AmbientEvent.Tick(13 * minute - 1)))
        val released = run(paused, AmbientEvent.Tick(13 * minute))
        assertEquals(Ambient.Released, released)
        assertNull("nothing runs once released", ambientDeadline(released))
        assertTrue(ambientConsumesKey(released))
        assertEquals(Ambient.Off(20 * minute, paused = true), run(released, AmbientEvent.Key(20 * minute)))
    }

    @Test
    fun `the paused count starts when drawing starts, not when the song paused`() {
        val s = run(counting, AmbientEvent.Paused(0), AmbientEvent.Tick(2 * minute))
        assertEquals(Ambient.Drawing(2 * minute, pausedSince = 2 * minute), s)
    }

    @Test
    fun `resuming while drawing stops the paused count, and while released starts counting again`() {
        val paused = run(counting, AmbientEvent.Tick(2 * minute), AmbientEvent.Paused(3 * minute))
        assertEquals(Ambient.Drawing(2 * minute, null), run(paused, AmbientEvent.Resumed(4 * minute)))
        assertEquals(Ambient.Off(30 * minute, paused = false), run(Ambient.Released, AmbientEvent.Resumed(30 * minute)))
    }

    @Test
    fun `blocked or ended turns it off with no timer`() {
        val drawing = run(counting, AmbientEvent.Tick(2 * minute), AmbientEvent.Paused(3 * minute))
        for (s in listOf(counting, drawing, Ambient.Released)) {
            val hidden = run(s, AmbientEvent.Hidden)
            assertTrue(hidden is Ambient.Off)
            assertNull(ambientDeadline(hidden))
            val ended = run(s, AmbientEvent.Ended)
            assertEquals(AMBIENT_START, ended)
        }
        assertEquals(Ambient.Off(null, paused = true), run(drawing, AmbientEvent.Hidden))
    }

    @Test
    fun `the player feeds ambient a track change, a pause or resume, and the end`() {
        val a = AmbientSource("s1", paused = false)
        assertEquals("the first read only sets the pause", listOf<AmbientEvent>(AmbientEvent.Resumed(5)), ambientInputs(null, a, 5))
        assertEquals(emptyList<AmbientEvent>(), ambientInputs(a, a, 5))
        assertEquals(listOf<AmbientEvent>(AmbientEvent.TrackChanged), ambientInputs(a, a.copy(item = "s2"), 5))
        assertEquals(listOf<AmbientEvent>(AmbientEvent.Paused(7)), ambientInputs(a, a.copy(paused = true), 7))
        assertEquals(listOf<AmbientEvent>(AmbientEvent.Ended), ambientInputs(a, null, 9))
        assertEquals(emptyList<AmbientEvent>(), ambientInputs(null, null, 9))
    }

    /** Each letter advances the clock by its delta, then makes its event at the new time. */
    private class Letter(val name: String, val delta: Long, val make: (Long) -> AmbientEvent)

    private val alphabet = listOf(
        Letter("shown", 0) { AmbientEvent.Shown(it) },
        Letter("hidden", 0) { AmbientEvent.Hidden },
        Letter("key", 5_000) { AmbientEvent.Key(it) },
        Letter("tick+1m", minute) { AmbientEvent.Tick(it) },
        Letter("tick+2m", 2 * minute) { AmbientEvent.Tick(it) },
        Letter("tick+10m", 10 * minute) { AmbientEvent.Tick(it) },
        Letter("track", 0) { AmbientEvent.TrackChanged },
        Letter("paused", 1_000) { AmbientEvent.Paused(it) },
        Letter("resumed", 1_000) { AmbientEvent.Resumed(it) },
        Letter("ended", 0) { AmbientEvent.Ended },
    )

    @Test
    fun `every event sequence up to length four keeps the invariants`() {
        var steps = 0
        val reached = mutableSetOf<String>()
        fun walk(s: Ambient, now: Long, paused: Boolean, path: String, depth: Int) {
            if (depth == 4) return
            for (l in alphabet) {
                val t = now + l.delta
                val e = l.make(t)
                val next = reduceAmbient(s, e)
                steps++
                val p = when (e) {
                    is AmbientEvent.Paused -> true
                    is AmbientEvent.Resumed, AmbientEvent.Ended -> false
                    else -> paused
                }
                checkInvariants(s, e, t, next, p, "$path ${l.name}")
                reached += next::class.simpleName.orEmpty()
                walk(next, t, p, "$path ${l.name}", depth + 1)
            }
        }
        walk(AMBIENT_START, 0, false, "", 0)
        assertEquals(setOf("Off", "Drawing", "Released"), reached)
        println("ambient sequences: $steps steps")
    }

    private fun checkInvariants(before: Ambient, e: AmbientEvent, t: Long, s: Ambient, paused: Boolean, where: String) {
        val playingPaused = when (s) {
            is Ambient.Off -> s.paused
            is Ambient.Drawing -> s.pausedSince != null
            Ambient.Released -> true
        }
        assertEquals("$where: the paused flag follows the last pause or resume", paused, playingPaused)
        if (e == AmbientEvent.Hidden || e == AmbientEvent.Ended) {
            assertTrue(where, s is Ambient.Off && s.idleSince == null)
        }
        if (s is Ambient.Off && s.idleSince == null) assertNull("$where: no timer while not counting", ambientDeadline(s))
        if (s is Ambient.Drawing && before !is Ambient.Drawing) {
            val idle = (before as? Ambient.Off)?.idleSince
            assertTrue("$where: drawing starts only after two idle minutes", e is AmbientEvent.Tick && idle != null && t - idle >= AMBIENT_IDLE_MS)
        }
        if (s == Ambient.Released && before != Ambient.Released) {
            val since = (before as? Ambient.Drawing)?.pausedSince
            assertTrue("$where: release only after ten paused minutes drawing", e is AmbientEvent.Tick && since != null && t - since >= AMBIENT_PAUSED_RELEASE_MS)
        }
        if (e is AmbientEvent.Key && before !is Ambient.Off) assertTrue("$where: a key always closes ambient", s is Ambient.Off)
        if (e is AmbientEvent.Key) assertEquals("$where: consumed exactly when ambient showed", before !is Ambient.Off, ambientConsumesKey(before))
        ambientDeadline(s)?.let { due ->
            assertTrue("$where: a timer exists only while counting", s is Ambient.Off && s.idleSince != null || s is Ambient.Drawing && s.pausedSince != null)
            assertTrue("$where: a deadline never moves backwards past its start", due >= 0)
        }
        if (s is Ambient.Drawing && before is Ambient.Drawing) {
            val expected = before.startPoint + if (e == AmbientEvent.TrackChanged) 1 else 0
            assertEquals("$where: the start point moves once per track change", expected, s.startPoint)
        }
    }

    @Test
    fun `a key that ends ambient stays consumed through its repeats until it is released`() {
        val swallowed = mutableSetOf<String>()
        var drawing = true
        val ends = { drawing.also { drawing = false } }
        assertTrue("the press that ends ambient", ambientKeyConsumed(down = true, "ok", swallowed, ends))
        assertTrue("its auto-repeat, with ambient already off", ambientKeyConsumed(down = true, "ok", swallowed, ends))
        assertTrue("its release", ambientKeyConsumed(down = false, "ok", swallowed, ends))
        assertFalse("the next press reaches the control", ambientKeyConsumed(down = true, "ok", swallowed, ends))
        assertFalse("a key ambient never saw passes", ambientKeyConsumed(down = false, "left", swallowed, ends))
    }

    @Test
    fun `every press counts as input, repeats of a swallowed key included`() {
        val swallowed = mutableSetOf("ok")
        var keys = 0
        ambientKeyConsumed(down = true, "ok", swallowed) { keys++; false }
        ambientKeyConsumed(down = true, "left", swallowed) { keys++; false }
        ambientKeyConsumed(down = false, "ok", swallowed) { keys++; false }
        assertEquals("presses reset the idle count, releases don't", 2, keys)
    }
}
