package tv.jellybeam.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.EndEffect.NoMedia
import tv.jellybeam.player.EndEffect.PastEnd
import tv.jellybeam.player.EndEffect.PlayedThrough
import tv.jellybeam.player.EndEffect.RecoverSeek
import tv.jellybeam.player.EndEffect.StopStuck
import tv.jellybeam.player.EndEvent.FirstFrame
import tv.jellybeam.player.EndEvent.LoadOpened
import tv.jellybeam.player.EndEvent.MediaArrived
import tv.jellybeam.player.EndEvent.Observed
import tv.jellybeam.player.EndEvent.Released
import tv.jellybeam.player.EndEvent.Reprepared
import tv.jellybeam.player.PlaybackEnd.Live
import tv.jellybeam.player.PlaybackEnd.NoLoad
import tv.jellybeam.player.PlaybackEnd.Over

/** docs/18 §7: [reducePlaybackEnd], every ordering up to four events plus the named rows of its table. */
class PlaybackEndTest {
    private fun facts(
        positionMs: Long,
        bufferedMs: Long = positionMs,
        phase: Int = Player.STATE_BUFFERING,
        playing: Boolean = true,
        loading: Boolean = false,
        seeking: Boolean = false,
        toEnd: Boolean = false,
        audience: Audience = Audience.SOLO,
    ) = EndFacts(phase, playing, loading, seeking, positionMs, bufferedMs, toEnd, audience)

    /** Stalled where the measured Jellyfin 10.11 file stops (47.88 s, first stub at 54 s), everything after buffered. */
    private val endStall = facts(47_880L, bufferedMs = 66_000L)
    private val midStall = facts(20_000L, bufferedMs = 46_000L)
    private val voidStall = facts(600_000L)
    private val seekStall = endStall.copy(seekInFlight = true)
    private val paused = endStall.copy(playWhenReady = false)
    private val playing = facts(30_000L, bufferedMs = 50_000L, phase = Player.STATE_READY)
    private val ended = facts(54_000L, phase = Player.STATE_ENDED)
    private val heldStall = endStall.copy(audience = Audience.HELD)
    private val loadingStall = endStall.copy(loading = true)
    private val shortStall = facts(46_000L, bufferedMs = 48_000L)

    private fun tripped(real: Seg?, stub: Seg) = Media.Tripped(real, stub)
    private val endKnown = tripped(Seg(8, 54_000L), Seg(9, 54_000L))
    private val endUnknown = tripped(Seg(3, 24_000L), Seg(100, 600_000L))
    private val runtime = PlaybackTicks.msToTicks(180_000L)

    /** Drives the reducer like the view model: one owner, effects collected in order. */
    private class Script(var state: PlaybackEnd = NoLoad) {
        var now = 0L
        val effects = mutableListOf<EndEffect>()
        fun send(vararg events: EndEvent) = events.forEach { e ->
            val step = reducePlaybackEnd(state, e)
            state = step.state
            step.effect?.let(effects::add)
        }
        fun see(f: EndFacts, afterMs: Long = 0L, times: Int = 1) = repeat(times) {
            now += afterMs
            send(Observed(f, now))
        }
    }

    /** A first-framed load 1 that has read up to [real] and tripped at [stub] (null: never tripped). */
    private fun playingLoad(real: Seg? = null, stub: Seg? = null) = Script().apply {
        send(LoadOpened(1), FirstFrame(1))
        real?.let { send(MediaArrived(1, it.index, it.ms)) }
        stub?.let { send(EndEvent.Tripped(1, it.index, it.ms)) }
    }

    // ---- sequence test ----

    private sealed interface Sym {
        data class Tagged(val event: EndEvent) : Sym
        data class Obs(val facts: EndFacts) : Sym
    }

    private val alphabet: List<Sym> = listOf(
        LoadOpened(2), Reprepared(1), FirstFrame(1), FirstFrame(0),
        MediaArrived(1, 8, 54_000L), MediaArrived(1, 3, 24_000L), MediaArrived(0, 50, 300_000L),
        EndEvent.Tripped(1, 9, 54_000L), EndEvent.Tripped(1, 100, 600_000L), EndEvent.Tripped(0, 2, 12_000L), Released,
        EndEvent.Failed(endStall.copy(phase = Player.STATE_IDLE)), EndEvent.Failed(midStall.copy(phase = Player.STATE_IDLE)),
    ).map { Sym.Tagged(it) } + listOf(endStall, shortStall, midStall, voidStall, seekStall, paused, playing, ended, heldStall, loadingStall).map { Sym.Obs(it) }

    /** The spec's model of the safety net, kept beside the state: framed since (re)prepare, and where a candidate freeze began. */
    private data class Run(
        val state: PlaybackEnd,
        val now: Long = 0L,
        val framed: Boolean = false,
        val freezeSince: Long? = null,
        val freezeAt: Long? = null,
        val trace: List<String> = emptyList(),
    )

    private val seeds: List<Run> = listOf(
        Run(NoLoad),
        Run(Live(Load(1), Watch.Opening)),
        Run(Live(Load(1, Media.Flowing(Seg(8, 54_000L))), Watch.Moving), framed = true),
        Run(Live(Load(1, endKnown), Watch.Moving), framed = true),
        Run(Live(Load(1, endUnknown), Watch.Moving), framed = true),
        Run(Over(Load(1, endKnown), Ending.PLAYED_THROUGH)),
        // Recovery spent and frozen at the end since 0: two more observations reach the stop.
        Run(
            Live(Load(1, Media.Flowing(Seg(8, 54_000L)), recoveredAtMs = 47_880L), Watch.Frozen(0L, 47_880L)),
            framed = true, freezeSince = 0L, freezeAt = 47_880L,
        ),
    )

    private val PlaybackEnd.loadOrNull: Load? get() = (this as? Live)?.load ?: (this as? Over)?.load

    private val EndEvent.seq: Long? get() = when (this) {
        is LoadOpened -> seq
        is Reprepared -> seq
        is FirstFrame -> seq
        is MediaArrived -> seq
        is EndEvent.Tripped -> seq
        is Observed, is EndEvent.Failed, Released -> null
    }

    private fun stall(f: EndFacts) = f.playWhenReady && f.phase == Player.STATE_BUFFERING && !f.seekInFlight && f.audience != Audience.HELD

    private fun step(run: Run, sym: Sym, failures: MutableList<String>): Run {
        val now = if (sym is Sym.Obs) run.now + 8_000L else run.now
        val event = when (sym) {
            is Sym.Tagged -> sym.event
            is Sym.Obs -> Observed(sym.facts, now)
        }
        val prev = run.state
        val (next, effect) = reducePlaybackEnd(prev, event)
        val trace = run.trace + "$event"
        fun fail(rule: String) {
            failures += "$rule: $trace -> $next / $effect"
        }

        // Model update (the spec's safety net), before checking it.
        var framed = run.framed
        var freezeSince = run.freezeSince
        var freezeAt = run.freezeAt
        val current = prev.loadOrNull?.seq
        when (event) {
            is LoadOpened -> { framed = false; freezeSince = null }
            is Reprepared -> if (prev is Live && event.seq == current) { framed = false; freezeSince = null }
            is FirstFrame -> if (prev is Live && event.seq == current) framed = true
            is Observed -> when {
                // A revocation by a seek waits for that seek's frame; by READY (no renderer reset) it stays armed.
                prev is Over && next is Live -> { framed = !event.facts.seekInFlight; freezeSince = null }
                prev !is Live -> Unit
                stall(event.facts) && !event.facts.loading && framed ->
                    if (freezeSince == null || freezeAt != event.facts.positionMs) { freezeSince = now; freezeAt = event.facts.positionMs }
                else -> freezeSince = null
            }
            else -> Unit
        }
        if (effect is RecoverSeek) freezeSince = null

        // 2. An effect comes only from Live: a stop goes to Over on the same load, the recovery stays Live and spends it.
        if (effect != null) {
            if (prev !is Live) fail("2: effect outside Live")
            if (effect is RecoverSeek) {
                if (next !is Live || next.load.seq != current || !next.load.recovered || (prev as? Live)?.load?.recovered != false) fail("2: recovery")
            } else if (next !is Over || next.load.seq != current) fail("2: effect without Live -> Over")
        }
        // 3. A tagged event from any load but the current one changes nothing.
        val tag = event.seq
        if (tag != null && event !is LoadOpened && tag != current && (next != prev || effect != null)) fail("3: stale event acted")
        // 4. NoLoad moves only on LoadOpened; Released lands in NoLoad silently.
        if (prev == NoLoad && event !is LoadOpened && (next != NoLoad || effect != null)) fail("4: NoLoad moved")
        if (event == Released && (next != NoLoad || effect != null)) fail("4: Released")
        // 5. Within one load the real end only rises and the stub only falls.
        val before = prev.loadOrNull
        val after = next.loadOrNull
        if (before != null && after != null && before.seq == after.seq) {
            val r0 = before.media.real?.index
            val r1 = after.media.real?.index
            if (r0 != null && (r1 == null || r1 < r0)) fail("5: real fell")
            val s0 = (before.media as? Media.Tripped)?.stub?.index
            val s1 = (after.media as? Media.Tripped)?.stub?.index
            if (s0 != null && (s1 == null || s1 > s0)) fail("5: stub rose")
        }
        val f = (event as? Observed)?.facts ?: (event as? EndEvent.Failed)?.facts
        val tripEnd = prev is Live && next is Over && next.ending != Ending.NATURAL
        // 6. A played-through trip end needs the known end, the position near it, everything buffered, no seek.
        if (prev is Live && next is Over && next.ending == Ending.PLAYED_THROUGH) {
            val end = (next.load.media as? Media.Tripped)?.endMs
            if (f == null || end == null || f.positionMs !in (end - 12_000L)..(end + 1_000L) || f.bufferedMs < end - 1_000L || f.seekInFlight) {
                fail("6: played through without its evidence")
            }
        }
        // 7. No stall end while paused, seeking or held.
        if ((tripEnd && event !is EndEvent.Failed || effect is RecoverSeek) && (f == null || !stall(f))) fail("7: stall end outside a stall")
        // 8. The net needs a frame since (re)prepare and 15 s of continuous candidate stall at one position; the stop
        // only once the load's recovery is spent.
        // 12. A failure ends the load only where its spent recovery left it stuck; elsewhere it belongs to the error path.
        if (event is EndEvent.Failed && effect != null) {
            val at = (prev as? Live)?.load?.recoveredAtMs
            if (effect !is StopStuck || at == null || kotlin.math.abs(event.facts.positionMs - at) > 1_000L) fail("12: failure stopped off the stuck spot")
        }
        if (effect is RecoverSeek || (effect is StopStuck && event !is EndEvent.Failed)) {
            val since = run.freezeSince
            if (!run.framed || since == null || run.freezeAt != f?.positionMs || now - since < 15_000L) fail("8: net without its freeze")
            if (effect is StopStuck && (prev as? Live)?.load?.recovered != true) fail("8: stop before the recovery")
        }
        // 9. Only a tripped load ends other than naturally or stuck.
        if (next is Over && next.ending in setOf(Ending.PLAYED_THROUGH, Ending.PAST_END, Ending.NO_MEDIA) && next.load.media !is Media.Tripped) {
            fail("9: untripped trip end")
        }
        // 10. Over never re-emits.
        if (prev is Over && effect != null) fail("10: Over re-emitted")
        // 11. The stop report: watched at a played-through end, two segments before the real end past it, never in the stubs.
        for (posMs in listOf(30_000L, 47_880L, 600_000L)) {
            val pos = PlaybackTicks.msToTicks(posMs)
            val got = endStopTicks(next, pos, runtime)
            val media = next.loadOrNull?.media
            val real = media?.real?.let { PlaybackTicks.msToTicks(it.ms) }
            val ok = when {
                next is Over && next.ending == Ending.PLAYED_THROUGH -> got == runtime
                next is Over && next.ending == Ending.PAST_END -> real != null && got == (real - PlaybackTicks.msToTicks(12_000L)).coerceAtLeast(0L)
                // In the stubs: the real end, or the position when this load read no real media.
                media is Media.Tripped && pos >= PlaybackTicks.msToTicks(media.stub.ms) -> got == (real ?: pos)
                else -> got == pos
            }
            if (!ok) fail("11: stop ticks $got at $posMs")
        }
        return Run(next, now, framed, freezeSince, freezeAt, trace)
    }

    private fun walk(run: Run, depth: Int, failures: MutableList<String>) {
        if (depth == 0) return
        for (sym in alphabet) walk(step(run, sym, failures), depth - 1, failures)
    }

    @Test
    fun `every sequence of four events from every seed keeps the invariants`() {
        val failures = mutableListOf<String>()
        for (seed in seeds) walk(seed.copy(trace = listOf("${seed.state}")), 4, failures)
        assertTrue(failures.take(10).joinToString("\n") + "\n(${failures.size} total)", failures.isEmpty())
    }

    @Test
    fun `the sequence test reaches every ending and effect`() {
        val endings = mutableSetOf<Ending>()
        val effects = mutableSetOf<String>()
        fun visit(run: Run, depth: Int) {
            if (depth == 0) return
            for (sym in alphabet) {
                val event = if (sym is Sym.Obs) Observed(sym.facts, run.now + 8_000L) else (sym as Sym.Tagged).event
                val (next, effect) = reducePlaybackEnd(run.state, event)
                (next as? Over)?.let { endings += it.ending }
                effect?.let { effects += it::class.simpleName!! }
                visit(step(run, sym, mutableListOf()), depth - 1)
            }
        }
        for (seed in seeds) visit(seed, 3)
        assertEquals(Ending.entries.toSet(), endings)
        assertEquals(setOf("PlayedThrough", "PastEnd", "NoMedia", "RecoverSeek", "StopStuck"), effects)
    }

    // ---- named rows ----

    @Test
    fun `a truncated file played through ends normally and counts as watched - the measured 47_88 s stall too`() {
        for (positionMs in listOf(42_000L, 47_600L, 47_880L, 55_000L)) {
            val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
            run.see(facts(positionMs, bufferedMs = 54_000L))
            assertEquals("at $positionMs", listOf(PlayedThrough), run.effects)
            assertEquals(runtime, endStopTicks(run.state, PlaybackTicks.msToTicks(positionMs), runtime))
        }
        val short = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        short.see(facts(41_999L, bufferedMs = 54_000L))
        assertTrue("more than two segments short of the end is no trip decision", short.effects.isEmpty())
    }

    @Test
    fun `a seek into the void closes quietly and resumes two segments before the real end`() {
        val run = playingLoad(real = Seg(3, 24_000L), stub = Seg(100, 600_000L))
        run.see(voidStall)
        assertEquals(listOf(PastEnd), run.effects)
        assertEquals(PlaybackTicks.msToTicks(12_000L), endStopTicks(run.state, PlaybackTicks.msToTicks(600_000L), runtime))
    }

    @Test
    fun `after a void trip a seek back that rebuffers short of the void is no end`() {
        val run = playingLoad(real = Seg(3, 24_000L), stub = Seg(100, 600_000L))
        run.send(MediaArrived(1, 20, 126_000L))
        run.see(facts(110_000L, bufferedMs = 126_000L))
        assertTrue(run.effects.isEmpty())
        assertTrue(run.state is Live)
    }

    @Test
    fun `a known end not yet buffered through is no trip decision`() {
        val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        run.see(shortStall)
        assertTrue(run.effects.isEmpty())
        assertTrue(run.state is Live)
    }

    @Test
    fun `media read after a void trip makes the true end known`() {
        val run = playingLoad(real = Seg(3, 24_000L), stub = Seg(100, 600_000L))
        run.send(MediaArrived(1, 48, 294_000L), MediaArrived(1, 49, 300_000L), EndEvent.Tripped(1, 50, 300_000L))
        assertEquals(300_000L, ((run.state as Live).load.media as Media.Tripped).endMs)
        run.see(facts(295_000L, bufferedMs = 300_000L))
        assertEquals(listOf(PlayedThrough), run.effects)
    }

    @Test
    fun `a lower run re-trips lower and a higher one never raises the end`() {
        val run = playingLoad(stub = Seg(100, 600_000L))
        run.send(EndEvent.Tripped(1, 50, 300_000L), EndEvent.Tripped(1, 70, 420_000L))
        assertEquals(Seg(50, 300_000L), ((run.state as Live).load.media as Media.Tripped).stub)
    }

    @Test
    fun `the safety net arms per load - a new load waits for its own first frame`() {
        val run = playingLoad()
        run.send(LoadOpened(2))
        run.see(endStall, afterMs = 8_000L, times = 6)
        assertTrue("no frame on load 2 yet", run.effects.isEmpty())
        run.send(FirstFrame(2))
        run.see(endStall, afterMs = 8_000L, times = 3)
        assertEquals(listOf<EndEffect>(RecoverSeek(47_880L)), run.effects)
    }

    @Test
    fun `a reprepare disarms the net until its first frame`() {
        val run = playingLoad()
        run.see(endStall, afterMs = 8_000L)
        run.send(Reprepared(1))
        run.see(endStall, afterMs = 8_000L, times = 6)
        assertTrue(run.effects.isEmpty())
        run.send(FirstFrame(1))
        run.see(endStall, afterMs = 8_000L, times = 3)
        assertEquals(listOf<EndEffect>(RecoverSeek(47_880L)), run.effects)
    }

    @Test
    fun `a renderer stall re-seeks once at 15 s, then stops 15 s later - mid-file and at the listed end`() {
        for ((stall, toEnd) in listOf(facts(39_980L, bufferedMs = 65_980L) to false, facts(47_960L, bufferedMs = 48_000L, toEnd = true) to true)) {
            val run = playingLoad()
            run.see(stall, afterMs = 250L)
            run.see(stall, afterMs = 14_999L)
            assertTrue("under 15 s", run.effects.isEmpty())
            run.see(stall, afterMs = 1L)
            assertEquals(listOf<EndEffect>(RecoverSeek(stall.positionMs)), run.effects)
            run.see(stall, afterMs = 250L)
            run.see(stall, afterMs = 15_000L)
            assertEquals(
                listOf(RecoverSeek(stall.positionMs), StopStuck(toEnd, stall.positionMs, stall.bufferedMs - stall.positionMs)),
                run.effects,
            )
            assertEquals("at its position, never watched", PlaybackTicks.msToTicks(stall.positionMs), endStopTicks(run.state, PlaybackTicks.msToTicks(stall.positionMs), runtime))
        }
    }

    @Test
    fun `a failure where the recovery seek left it stuck is the net's stop - the held request's IO timeout`() {
        val run = playingLoad()
        run.see(endStall, afterMs = 8_000L, times = 3)
        run.send(FirstFrame(1))
        // The re-seek lands 17 ms on; the held segment request then times out and the player goes idle.
        val failed = endStall.copy(positionMs = 47_897L, phase = Player.STATE_IDLE)
        run.send(EndEvent.Failed(failed))
        assertEquals(listOf(RecoverSeek(47_880L), StopStuck(false, 47_897L, 66_000L - 47_897L)), run.effects)
        assertEquals(Ending.STUCK, (run.state as Over).ending)
    }

    @Test
    fun `a failure before any recovery or away from the stuck spot stays with the error path`() {
        val fresh = playingLoad()
        fresh.send(EndEvent.Failed(endStall.copy(phase = Player.STATE_IDLE)))
        assertTrue(fresh.effects.isEmpty())
        assertTrue(fresh.state is Live)

        val moved = playingLoad()
        moved.see(endStall, afterMs = 8_000L, times = 3)
        moved.send(EndEvent.Failed(facts(60_000L, phase = Player.STATE_IDLE)))
        assertEquals(listOf<EndEffect>(RecoverSeek(47_880L)), moved.effects)
        assertTrue(moved.state is Live)
    }

    @Test
    fun `one recovery per load - a reprepare keeps it spent, a new load gets its own`() {
        val run = playingLoad()
        run.see(endStall, afterMs = 8_000L, times = 3)
        assertEquals(1, run.effects.size)
        run.send(Reprepared(1), FirstFrame(1))
        run.see(endStall, afterMs = 8_000L, times = 3)
        assertEquals(listOf(RecoverSeek(47_880L), StopStuck(false, 47_880L, 66_000L - 47_880L)), run.effects)

        val fresh = playingLoad()
        fresh.see(endStall, afterMs = 8_000L, times = 3)
        fresh.send(LoadOpened(2), FirstFrame(2))
        fresh.see(endStall, afterMs = 8_000L, times = 3)
        assertEquals(listOf<EndEffect>(RecoverSeek(47_880L), RecoverSeek(47_880L)), fresh.effects)
    }

    @Test
    fun `a held-open request or a slow load never ends playback`() {
        val run = playingLoad(real = Seg(7, 48_000L))
        run.see(loadingStall, afterMs = 250L, times = 1_200) // 5 min
        assertTrue(run.effects.isEmpty())
    }

    @Test
    fun `paused at the real end does nothing until play resumes`() {
        val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        run.see(paused, afterMs = 8_000L, times = 10)
        assertTrue(run.effects.isEmpty())
        run.see(endStall, afterMs = 8_000L)
        assertEquals(listOf(PlayedThrough), run.effects)
    }

    @Test
    fun `a pause inside a freeze restarts the 15 s`() {
        val run = playingLoad()
        run.see(endStall, afterMs = 250L)
        run.see(endStall, afterMs = 10_000L)
        run.see(paused, afterMs = 60_000L)
        run.see(endStall, afterMs = 1_000L)
        run.see(endStall, afterMs = 10_000L)
        assertTrue(run.effects.isEmpty())
        run.see(endStall, afterMs = 5_000L)
        assertEquals(listOf<EndEffect>(RecoverSeek(47_880L)), run.effects)
    }

    @Test
    fun `a seek's own stall at the end is the serializer's until it lands or times out`() {
        val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        run.see(seekStall, afterMs = 250L, times = 80) // 20 s, a transcode landing timeout
        assertTrue(run.effects.isEmpty())
        run.see(endStall, afterMs = 250L)
        assertEquals(listOf(PlayedThrough), run.effects)
    }

    @Test
    fun `no real media on this load is the message, reported at the position`() {
        val run = playingLoad(stub = Seg(500, 3_000_000L))
        run.see(facts(3_000_000L))
        assertEquals(listOf(NoMedia), run.effects)
        assertEquals(PlaybackTicks.msToTicks(3_000_000L), endStopTicks(run.state, PlaybackTicks.msToTicks(3_000_000L), runtime))
    }

    @Test
    fun `an earlier load's late gate and frame events change nothing`() {
        val run = playingLoad()
        run.send(LoadOpened(2))
        val before = run.state
        run.send(MediaArrived(1, 8, 54_000L), EndEvent.Tripped(1, 9, 54_000L), FirstFrame(1), Reprepared(1))
        assertEquals(before, run.state)
        run.see(endStall)
        assertTrue(run.effects.isEmpty())
    }

    @Test
    fun `in a group a trip end plays through like a natural end, the void and no media leave through the error`() {
        val through = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        through.see(endStall.copy(audience = Audience.GROUP))
        assertEquals(listOf(PlayedThrough), through.effects)

        val void = playingLoad(real = Seg(3, 24_000L), stub = Seg(100, 600_000L))
        void.see(voidStall.copy(audience = Audience.GROUP))
        assertEquals(listOf(NoMedia), void.effects)

        val natural = playingLoad()
        natural.see(ended.copy(audience = Audience.GROUP))
        assertEquals(Over(Load(1), Ending.NATURAL), natural.state)
        assertEquals("one end report, which the group hears as PlayerEnded", listOf(PlayedThrough), natural.effects)
    }

    @Test
    fun `a held stall decides nothing - the next tick once free does`() {
        val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        run.see(heldStall, afterMs = 8_000L, times = 5)
        assertTrue(run.effects.isEmpty())
        run.see(endStall, afterMs = 250L)
        assertEquals(listOf(PlayedThrough), run.effects)
    }

    @Test
    fun `ENDED twice ends once`() {
        val run = playingLoad()
        run.see(ended)
        run.see(ended)
        assertEquals(listOf(PlayedThrough), run.effects)
    }

    @Test
    fun `a deferred played-through end is revoked by a seek, and the stop reports the position`() {
        val run = playingLoad(real = Seg(8, 54_000L), stub = Seg(9, 54_000L))
        run.see(endStall)
        assertEquals(runtime, endStopTicks(run.state, PlaybackTicks.msToTicks(47_880L), runtime))
        run.see(facts(20_000L, seeking = true))
        assertEquals(Watch.Opening, (run.state as Live).watch)
        assertEquals(PlaybackTicks.msToTicks(20_000L), endStopTicks(run.state, PlaybackTicks.msToTicks(20_000L), runtime))
        run.see(facts(20_000L, phase = Player.STATE_READY))
        run.see(endStall)
        assertEquals("the end re-arms once revoked", listOf(PlayedThrough, PlayedThrough), run.effects)
    }

    @Test
    fun `after release late events do nothing`() {
        val run = playingLoad(real = Seg(8, 54_000L))
        run.send(Released, MediaArrived(1, 9, 60_000L), EndEvent.Tripped(1, 10, 60_000L), FirstFrame(1))
        run.see(endStall)
        run.see(ended)
        assertEquals(NoLoad, run.state)
        assertTrue(run.effects.isEmpty())
    }

    @Test
    fun `a stall in the last 12 s without a trip is never played through`() {
        val run = playingLoad(real = Seg(8, 54_000L))
        val nearEnd = facts(47_880L, bufferedMs = 54_000L, toEnd = true)
        run.see(nearEnd, afterMs = 250L, times = 240) // 60 s
        assertEquals(listOf(RecoverSeek(47_880L), StopStuck(true, 47_880L, 6_120L)), run.effects)
        assertEquals(Ending.STUCK, (run.state as Over).ending)
    }

    @Test
    fun `endStopTicks - watched at a played-through end, before the real end past it, never in the stubs`() {
        val known = Load(1, endKnown)
        fun at(ms: Long) = PlaybackTicks.msToTicks(ms)
        assertEquals(runtime, endStopTicks(Over(known, Ending.PLAYED_THROUGH), at(47_880L), runtime))
        assertEquals("no runtime: the position", at(47_880L), endStopTicks(Over(known, Ending.PLAYED_THROUGH), at(47_880L), null))
        assertEquals("a resume plays out real media", at(42_000L), endStopTicks(Over(known, Ending.PAST_END), at(60_000L), runtime))
        assertEquals("clamped at zero", 0L, endStopTicks(Over(Load(1, tripped(Seg(0, 3_000L), Seg(1, 6_000L))), Ending.PAST_END), at(60_000L), runtime))
        assertEquals("Back while buffered media plays", at(30_000L), endStopTicks(Live(known, Watch.Moving), at(30_000L), runtime))
        assertEquals("never in the stubs", at(54_000L), endStopTicks(Live(known, Watch.Moving), at(57_000L), runtime))
        assertEquals("no real media: the position", at(600_000L), endStopTicks(Over(Load(1, tripped(null, Seg(9, 54_000L))), Ending.NO_MEDIA), at(600_000L), runtime))
        assertEquals("never tripped", at(600_000L), endStopTicks(Live(Load(1), Watch.Moving), at(600_000L), runtime))
        assertEquals("no load", at(600_000L), endStopTicks(NoLoad, at(600_000L), runtime))
        assertEquals("stuck: its position", at(47_880L), endStopTicks(Over(Load(1), Ending.STUCK), at(47_880L), runtime))
        assertNull(Media.Tripped(Seg(3, 24_000L), Seg(100, 600_000L)).endMs)
    }
}
