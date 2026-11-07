package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/21 §2.1 `audio.clock`: numeric fields only, rare events only, nothing per position read. */
class ClockDiagReporterTest {

    private val logged = mutableListOf<String>()
    private val events = mutableListOf<ClockEvent>()
    private var enabled = true
    private val reporter = ClockDiagReporter(outputId = 7, startMs = 0, log = { logged += it }, enabled = { enabled }, emit = { events += it })

    /** Reads [trace] through a guard the way the output does, one report call per read. */
    private fun replay(points: List<Pair<Long, Long>>, lagUs: Long = 70_000L): AudioClockGuard {
        val g = AudioClockGuard(lagUs = lagUs)
        g.onPlay(0)
        for ((now, raw) in points) {
            val before = g.ruleCounts.sum()
            val pos = g.filter(raw, now, 1f)
            reporter.onRead(g, reportedUs = raw, positionUs = pos, faultsBefore = before, nowUs = now)
        }
        return g
    }

    private fun healthy(durationUs: Long, ppm: Long = 0L) =
        (0L..durationUs step 20_000L).map { now -> now to maxOf(0L, Math.round((now - 70_000L) * (1.0 + ppm / 1e6))) }

    @Test
    fun `a two hour movie emits one drift event a minute and nothing per read`() {
        val g = replay(healthy(2 * 3_600_000_000L, ppm = 50))
        assertEquals(0, g.clampCount)
        assertEquals(120, events.size) // 360k reads, one event per minute once 10 s are covered
        assertTrue(events.all { it.kind == "drift" })
        assertEquals(events.size, logged.size)
    }

    @Test
    fun `the drift event carries numbers only`() {
        replay(healthy(130_000_000L))
        val e = events.first()
        assertEquals(listOf("out", "ppm", "span", "lag", "clamps", "final"), e.fields.map { it.first })
        assertEquals(7L, e.fields.first { it.first == "out" }.second)
        assertEquals(null, e.rule)
        assertTrue(logged.first().startsWith("clock drift out=7 ppm="))
    }

    @Test
    fun `a fault emits one clamp event with its rule`() {
        val pts = (0L..5_000_000L step 20_000L).map { now -> now to 44_739_000_000L + maxOf(0L, now - 70_000L) }
        replay(pts)
        val clamps = events.filter { it.kind == "clamp" }
        assertEquals(1, clamps.size)
        assertEquals("start", clamps[0].rule)
        assertEquals(listOf("out", "reported", "bound", "faults"), clamps[0].fields.map { it.first })
        assertTrue(logged.any { it.startsWith("clock clamp rule=start reported=") })
    }

    @Test
    fun `nothing is built or emitted while diagnostic logging is off but logcat still logs`() {
        enabled = false
        replay(healthy(130_000_000L))
        assertEquals(0, events.size)
        assertEquals(2, logged.size)
    }

    @Test
    fun `release emits the summary of a track that clamped, else the learned lag`() {
        val faulty = AudioClockGuard(lagUs = 70_000L)
        faulty.onPlay(0)
        faulty.filter(2_500_000, 50_000, 1f)
        reporter.onRelease(faulty)
        val s = events.single { it.kind == "summary" }
        assertEquals(listOf("out", "clamps", "maxExcess", "start", "jump", "ceiling"), s.fields.map { it.first })
        assertEquals(1L, s.fields.first { it.first == "start" }.second)

        events.clear()
        val ok = AudioClockGuard()
        ok.onPlay(0)
        ok.filter(1_930_000, 2_000_000, 1f)
        reporter.onRelease(ok)
        val l = events.single { it.kind == "lag" }
        assertEquals(listOf("out", "lag", "unstopped"), l.fields.map { it.first })
        assertEquals(70L, l.fields.first { it.first == "lag" }.second)
    }

    @Test
    fun `the fault events of one output are capped`() {
        val g = AudioClockGuard(lagUs = 70_000L)
        g.onPlay(0)
        var now = 0L
        repeat(40) {
            now += 3_000_000L
            val before = g.ruleCounts.sum()
            val pos = g.filter(reportedUs = now + 5_000_000L * (it + 1), nowUs = now, speed = 1f)
            reporter.onRead(g, now + 5_000_000L * (it + 1), pos, before, now)
        }
        assertTrue(events.count { it.kind == "clamp" } <= ClockDiagReporter.MAX_FAULT_EVENTS)
    }
}
