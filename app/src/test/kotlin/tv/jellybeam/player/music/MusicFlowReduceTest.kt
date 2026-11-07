package tv.jellybeam.player.music

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.player.music.MusicEffect.Apply
import tv.jellybeam.player.music.MusicEffect.BuildPlayer
import tv.jellybeam.player.music.MusicEffect.CancelSession
import tv.jellybeam.player.music.MusicEffect.CloseVideo
import tv.jellybeam.player.music.MusicEffect.End
import tv.jellybeam.player.music.MusicEffect.Insert
import tv.jellybeam.player.music.MusicEffect.LaunchEnqueue
import tv.jellybeam.player.music.MusicEffect.LaunchFetch
import tv.jellybeam.player.music.MusicEffect.Release
import tv.jellybeam.player.music.MusicEffect.ReplaceQueue
import tv.jellybeam.player.music.MusicEffect.RetryPrepare
import tv.jellybeam.player.music.MusicEffect.SkipNext
import tv.jellybeam.player.music.MusicEffect.StartService
import tv.jellybeam.player.music.MusicEffect.StartTrack
import tv.jellybeam.player.music.MusicEffect.StopService
import tv.jellybeam.player.music.MusicEvent.EnqueueFailed
import tv.jellybeam.player.music.MusicEvent.EnqueueFetched
import tv.jellybeam.player.music.MusicEvent.EpochsChanged
import tv.jellybeam.player.music.MusicEvent.FetchEmpty
import tv.jellybeam.player.music.MusicEvent.FetchFailed
import tv.jellybeam.player.music.MusicEvent.Fetched
import tv.jellybeam.player.music.MusicEvent.NetworkLost
import tv.jellybeam.player.music.MusicEvent.NoticeShown
import tv.jellybeam.player.music.MusicEvent.PlayerFailed
import tv.jellybeam.player.music.MusicEvent.QueueEnded
import tv.jellybeam.player.music.MusicEvent.ServiceDestroyed
import tv.jellybeam.player.music.MusicEvent.ServiceReady
import tv.jellybeam.player.music.MusicEvent.ServiceStartFailed
import tv.jellybeam.player.music.MusicEvent.Stop
import tv.jellybeam.player.music.MusicEvent.TrackChanged
import tv.jellybeam.player.music.MusicEvent.Transport
import tv.jellybeam.player.music.MusicEvent.UserPlay
import tv.jellybeam.player.music.MusicEvent.VideoStarting
import tv.jellybeam.player.music.MusicFlow.Net
import tv.jellybeam.player.music.MusicFlow.Pending
import tv.jellybeam.player.music.MusicFlow.Phase.Active
import tv.jellybeam.player.music.MusicFlow.Phase.Idle
import tv.jellybeam.player.music.MusicFlow.Phase.Starting
import tv.jellybeam.player.music.MusicFlow.Refusal

/** The music owner's transitions: one test per table row across Idle, Starting and Active, the named races, and every sequence up to length four. */
class MusicFlowReduceTest {
    private val s1 = session(1)
    private val idle = MusicFlow(lastSid = 1, lastTicket = 1)
    private val starting = MusicFlow(Starting(s1, ticket = 1), lastSid = 1, lastTicket = 1)
    private val active = MusicFlow(Active(s1, G, n = 2, Net.Online), lastSid = 1, lastTicket = 1)
    private val columns = listOf(idle, starting, active)

    /** [event] leaves every column unchanged and does nothing. */
    private fun assertIgnored(event: MusicEvent, vararg states: MusicFlow) =
        states.forEach { assertEquals("$event from $it", MusicStep(it), reduce(it, event)) }

    private fun assertStep(from: MusicFlow, event: MusicEvent, state: MusicFlow, vararg effects: MusicEffect) =
        assertEquals("$event from $from", MusicStep(state, effects.toList()), reduce(from, event))

    @Test
    fun `Play waits on its fetch and replaces an older one in every phase`() {
        columns.forEach { from ->
            assertStep(from, play(5), from.copy(pending = fetch(5)), LaunchFetch(5, SRC))
            assertStep(from.copy(pending = fetch(4)), play(5), from.copy(pending = fetch(5)), MusicEffect.CancelFetch(4), LaunchFetch(5, SRC))
            assertStep(from.copy(pending = Pending.Refused(4, Refusal.Empty, SRC)), play(5), from.copy(pending = fetch(5)), LaunchFetch(5, SRC))
        }
    }

    @Test
    fun `CancelFetch drops only the waiting request`() {
        columns.forEach { from ->
            assertStep(from.copy(pending = fetch(5)), MusicEvent.CancelFetch(5), from, MusicEffect.CancelFetch(5))
            assertStep(from.copy(pending = Pending.Refused(5, Refusal.Failed, SRC)), MusicEvent.CancelFetch(5), from)
            assertIgnored(MusicEvent.CancelFetch(STALE), from, from.copy(pending = fetch(5)), from.copy(pending = Pending.Refused(5, Refusal.Empty, SRC)))
        }
    }

    @Test
    fun `the current Fetched starts, restarts or replaces the session`() {
        val s2 = session(2, "x1", "x2")
        val result = Fetched(5, listOf("x1", "x2"))
        assertStep(idle.copy(pending = fetch(5)), result, MusicFlow(Starting(s2, 2), lastSid = 2, lastTicket = 2), CloseVideo, StartService(2))
        assertStep(starting.copy(pending = fetch(5)), result, MusicFlow(Starting(s2, 1), lastSid = 2, lastTicket = 1), CancelSession(1))
        assertStep(
            active.copy(pending = fetch(5)),
            result,
            MusicFlow(Active(s2, G, 0, Net.Online), lastSid = 2, lastTicket = 1),
            CancelSession(1),
            ReplaceQueue(G, s2),
            StartTrack(TrackRef(2, 0), "x1", TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)),
        )
        columns.forEach { assertIgnored(Fetched(STALE, listOf("x1")), it, it.copy(pending = fetch(5)), it.copy(pending = Pending.Refused(STALE, Refusal.Empty, SRC))) }
    }

    @Test
    fun `an empty or failed fetch is refused and leaves the session untouched`() {
        columns.forEach { from ->
            assertStep(from.copy(pending = fetch(5)), FetchEmpty(5), from.copy(pending = Pending.Refused(5, Refusal.Empty, SRC)))
            assertStep(from.copy(pending = fetch(5)), FetchFailed(5), from.copy(pending = Pending.Refused(5, Refusal.Failed, SRC)))
            assertIgnored(FetchEmpty(STALE), from, from.copy(pending = fetch(5)))
            assertIgnored(FetchFailed(STALE), from, from.copy(pending = fetch(5)))
        }
    }

    @Test
    fun `Enqueue with nothing playing plays the item, otherwise fetches in the session`() {
        val enqueue = MusicEvent.Enqueue(5, EnqueueMode.PlayNext, SRC_B, E1, "Song B")
        assertStep(idle, enqueue, idle.copy(pending = Pending.Fetch(5, E1, SRC_B)), LaunchFetch(5, SRC_B))
        val queued = MusicNotice.Queued(5, EnqueueMode.PlayNext, "Song B")
        assertStep(starting, enqueue, starting.copy(notice = queued), LaunchEnqueue(1, 5, EnqueueMode.PlayNext, SRC_B))
        assertStep(active, enqueue, active.copy(notice = queued), LaunchEnqueue(1, 5, EnqueueMode.PlayNext, SRC_B))
    }

    @Test
    fun `an Enqueue minted on another account plays as a new queue there, with no confirmation`() {
        val other = MusicEvent.Enqueue(6, EnqueueMode.PlayNext, SRC_B, E2, "Song B")
        val live = run(idle, play(5), Fetched(5, listOf("x1")), ServiceReady(G, 2)).state
        assertStep(live, other, live.copy(pending = Pending.Fetch(6, E2, SRC_B)), LaunchFetch(6, SRC_B))
        assertStep(starting, other, starting.copy(pending = Pending.Fetch(6, E2, SRC_B)), LaunchFetch(6, SRC_B))
    }

    @Test
    fun `a failed enqueue fetch of the live session leaves a notice and the queue untouched`() {
        assertStep(starting, EnqueueFailed(1, 5), starting.copy(notice = MusicNotice.EnqueueFailed(5)))
        assertStep(active, EnqueueFailed(1, 5), active.copy(notice = MusicNotice.EnqueueFailed(5)))
        assertIgnored(EnqueueFailed(1, 5), idle)
        assertIgnored(EnqueueFailed(STALE, 5), idle, starting, active)
    }

    @Test
    fun `NoticeShown clears only the notice it names`() {
        val shown = MusicNotice.EnqueueFailed(5)
        columns.forEach { from ->
            assertStep(from.copy(notice = shown), NoticeShown(shown), from)
            assertIgnored(NoticeShown(shown), from, from.copy(notice = MusicNotice.EnqueueFailed(6)), from.copy(notice = MusicNotice.StartFailed(5)))
        }
    }

    @Test
    fun `Confirm shows its notice only while a session holds playback`() {
        val queued = MusicNotice.Queued(5, EnqueueMode.PlayNext, "Song A")
        assertIgnored(MusicEvent.Confirm(queued), idle)
        listOf(starting, active).forEach { from ->
            assertStep(from, MusicEvent.Confirm(queued), from.copy(notice = queued))
            val again = MusicNotice.AddedToPlaylist(6, "List A", again = true)
            assertStep(from.copy(notice = queued), MusicEvent.Confirm(again), from.copy(notice = again))
        }
    }

    @Test
    fun `EnqueueFetched inserts into a starting queue or the playing one`() {
        val added = EnqueueFetched(1, EnqueueMode.AddToQueue, listOf("u1"))
        val next = EnqueueFetched(1, EnqueueMode.PlayNext, listOf("u2"))
        val run = run(starting, added, next)
        val items = (run.state.phase as Starting).session.items
        assertEquals(listOf("t1", "u2", "u1", "t2"), items.map { it.item })
        assertEquals(emptyList<MusicEffect>(), run.effects)
        assertStep(active, added, active, Insert(G, EnqueueMode.AddToQueue, listOf("u1")))
        assertIgnored(added, idle)
        assertIgnored(EnqueueFetched(STALE, EnqueueMode.PlayNext, listOf("u1")), idle, starting, active)
    }

    @Test
    fun `ServiceReady with the current ticket goes Active, any other instance is released`() {
        assertStep(starting, ServiceReady(G, 1), MusicFlow(Active(s1, G, 0, Net.Online), lastSid = 1, lastTicket = 1), BuildPlayer(G, s1), StartTrack(TrackRef(1, 0), "t1", null))
        assertStep(starting, ServiceReady(G, STALE), starting, Release(G))
        assertStep(idle, ServiceReady(G, 1), idle, Release(G))
        assertIgnored(ServiceReady(G, 1), active)
        assertStep(active, ServiceReady(G + 1, 1), active, Release(G + 1))
    }

    @Test
    fun `ServiceDestroyed ends an Active session without a release`() {
        assertStep(active, ServiceDestroyed(G, 40), idle, End(TrackEnd(TrackRef(1, 2), EndAt.Ticks(40))), CancelSession(1))
        assertIgnored(ServiceDestroyed(G, 40), idle, starting)
        assertIgnored(ServiceDestroyed(STALE, 40), active)
    }

    @Test
    fun `TrackChanged starts the next report and ends the previous one`() {
        assertStep(
            active,
            TrackChanged(1, "t2", 30),
            active.copy(phase = Active(s1, G, 3, Net.Online)),
            StartTrack(TrackRef(1, 3), "t2", TrackEnd(TrackRef(1, 2), EndAt.Ticks(30))),
        )
        assertIgnored(TrackChanged(1, "t2", 30), idle, starting)
        assertIgnored(TrackChanged(STALE, "t2", 30), active)
    }

    @Test
    fun `QueueEnded goes Idle, ends the track and releases the service`() {
        assertStep(active, QueueEnded(1, 50), idle, End(TrackEnd(TrackRef(1, 2), EndAt.Ticks(50))), Release(G), CancelSession(1))
        assertIgnored(QueueEnded(1, 50), idle, starting)
        assertIgnored(QueueEnded(STALE, 50), active)
    }

    @Test
    fun `NetworkLost pauses an online session once`() {
        val lost = active.copy(phase = Active(s1, G, 2, Net.Lost))
        assertStep(active, NetworkLost(1), lost, MusicEffect.Pause(G))
        assertIgnored(NetworkLost(1), idle, starting, lost)
        assertIgnored(NetworkLost(STALE), active)
    }

    @Test
    fun `UserPlay retries a lost session and plays an online one`() {
        val lost = active.copy(phase = Active(s1, G, 2, Net.Lost))
        assertStep(lost, UserPlay, active, RetryPrepare(G))
        assertStep(active, UserPlay, active, MusicEffect.Play(G))
        assertIgnored(UserPlay, idle, starting)
    }

    @Test
    fun `Transport applies in Active and next or previous reconnect a lost network`() {
        val lost = active.copy(phase = Active(s1, G, 2, Net.Lost))
        assertStep(active, Transport(TransportOp.Pause), active, Apply(G, TransportOp.Pause))
        assertStep(lost, Transport(TransportOp.Pause), lost, Apply(G, TransportOp.Pause))
        assertStep(lost, Transport(TransportOp.Next), active, Apply(G, TransportOp.Next), RetryPrepare(G))
        assertStep(lost, Transport(TransportOp.Previous), active, Apply(G, TransportOp.Previous), RetryPrepare(G))
        assertIgnored(Transport(TransportOp.Next), idle, starting)
    }

    @Test
    fun `Play next on a queued row applies in Active as a queue op and is ignored before`() {
        val lost = active.copy(phase = Active(s1, G, 2, Net.Lost))
        assertStep(active, Transport(TransportOp.PlayNext(3)), active, Apply(G, TransportOp.PlayNext(3)))
        assertStep(lost, Transport(TransportOp.PlayNext(3)), lost, Apply(G, TransportOp.PlayNext(3)))
        assertIgnored(Transport(TransportOp.PlayNext(3)), idle, starting)
    }

    @Test
    fun `Skip while the network is lost`() {
        val skipped = run(active, NetworkLost(1), Transport(TransportOp.Next))
        assertEquals(active, skipped.state)
        assertEquals(listOf(MusicEffect.Pause(G), Apply(G, TransportOp.Next), RetryPrepare(G)), skipped.effects)
    }

    @Test
    fun `PlayerFailed skips a track, and on the last one ends as the queue does`() {
        assertStep(active, PlayerFailed(1, isLast = false), active, SkipNext(G))
        assertStep(active, PlayerFailed(1, isLast = true), idle, End(TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)), Release(G), CancelSession(1))
        assertIgnored(PlayerFailed(1, isLast = true), idle, starting)
        assertIgnored(PlayerFailed(STALE, isLast = false), active)
    }

    @Test
    fun `Stop and VideoStarting end every phase and its fetch`() {
        listOf(Stop, VideoStarting).forEach { event ->
            assertStep(idle.copy(pending = fetch(5)), event, idle, MusicEffect.CancelFetch(5))
            assertStep(idle.copy(pending = Pending.Refused(5, Refusal.Empty, SRC)), event, idle)
            assertStep(starting.copy(pending = fetch(5)), event, idle, CancelSession(1), StopService, MusicEffect.CancelFetch(5))
            assertStep(active.copy(pending = fetch(5)), event, idle, End(TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)), Release(G), CancelSession(1), MusicEffect.CancelFetch(5))
        }
    }

    @Test
    fun `EpochsChanged drops a fetch and stops a session that belong to neither account`() {
        val onE2 = Pending.Fetch(5, E2, SRC)
        columns.forEach { from ->
            assertIgnored(EpochsChanged(E1, null), from.copy(pending = fetch(5)))
            assertIgnored(EpochsChanged(E2, E1), from.copy(pending = fetch(5)), from.copy(pending = onE2))
            assertStep(from.copy(pending = Pending.Refused(5, Refusal.Empty, SRC)), EpochsChanged(E2, E1), from)
        }
        assertStep(idle.copy(pending = fetch(5)), EpochsChanged(E2, null), idle, MusicEffect.CancelFetch(5))
        assertStep(idle.copy(pending = onE2), EpochsChanged(E1, null), idle, MusicEffect.CancelFetch(5))
        assertStep(starting, EpochsChanged(E2, null), idle, CancelSession(1), StopService)
        assertStep(active, EpochsChanged(E2, null), idle, End(TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)), Release(G), CancelSession(1))
        assertStep(active.copy(pending = onE2), EpochsChanged(E2, null), idle.copy(pending = onE2), End(TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)), Release(G), CancelSession(1))
    }

    @Test
    fun `Video start during a mix load`() {
        val loading = run(active, play(5, MIX), VideoStarting, Fetched(5, listOf("m1")))
        assertEquals(idle, loading.state)
        assertEquals(
            listOf(LaunchFetch(5, MIX), End(TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)), Release(G), CancelSession(1), MusicEffect.CancelFetch(5)),
            loading.effects,
        )
        val fromIdle = run(idle, play(5, MIX), VideoStarting, Fetched(5, listOf("m1")))
        assertEquals(idle, fromIdle.state)
        assertEquals(listOf(LaunchFetch(5, MIX), MusicEffect.CancelFetch(5)), fromIdle.effects)
    }

    @Test
    fun `Leave during Starting`() {
        val left = run(starting, Stop, ServiceReady(G, 1))
        assertEquals(idle, left.state)
        assertEquals(listOf(CancelSession(1), StopService, Release(G)), left.effects)
    }

    @Test
    fun `ServiceDestroyed while Active`() {
        val gone = run(active, ServiceDestroyed(G, 40), Stop, QueueEnded(1, 50))
        assertEquals(idle, gone.state)
        assertEquals(listOf(End(TrackEnd(TrackRef(1, 2), EndAt.Ticks(40))), CancelSession(1)), gone.effects)
    }

    @Test
    fun `Second Play while Starting`() {
        val s2 = session(2, "x1")
        val run = run(starting.copy(pending = fetch(4)), play(5), Fetched(4, listOf("old")), Fetched(5, listOf("x1")), ServiceReady(G, 1))
        assertEquals(MusicFlow(Active(s2, G, 0, Net.Online), lastSid = 2, lastTicket = 1), run.state)
        assertEquals(
            listOf(MusicEffect.CancelFetch(4), LaunchFetch(5, SRC), CancelSession(1), BuildPlayer(G, s2), StartTrack(TrackRef(2, 0), "x1", null)),
            run.effects,
        )
    }

    @Test
    fun `Stop right after TrackChanged`() {
        val run = run(active, TrackChanged(1, "t2", 30), Stop, TrackChanged(1, "t3", 60))
        assertEquals(idle, run.state)
        assertEquals(
            listOf(
                StartTrack(TrackRef(1, 3), "t2", TrackEnd(TrackRef(1, 2), EndAt.Ticks(30))),
                End(TrackEnd(TrackRef(1, 3), EndAt.PlayerPosition)),
                Release(G),
                CancelSession(1),
            ),
            run.effects,
        )
    }

    @Test
    fun `Empty mix result`() {
        val run = run(active, play(5, MIX), FetchEmpty(5), Fetched(5, listOf("m1")))
        assertEquals(active.copy(pending = Pending.Refused(5, Refusal.Empty, MIX)), run.state)
        assertEquals(listOf(LaunchFetch(5, MIX)), run.effects)
    }

    @Test
    fun `Play from a row starts the session at that row`() {
        assertStep(idle, MusicEvent.Play(5, SRC, E1, startIndex = 1), idle.copy(pending = fetch(5)), LaunchFetch(5, SRC, startIndex = 1))
        val s2 = session(2, "x1", "x2", "x3").copy(start = 1)
        val ready = run(idle.copy(pending = fetch(5)), Fetched(5, listOf("x1", "x2", "x3"), startIndex = 1), ServiceReady(G, 2))
        assertEquals(MusicFlow(Active(s2, G, 0, Net.Online), lastSid = 2, lastTicket = 2), ready.state)
        assertEquals(listOf(CloseVideo, StartService(2), BuildPlayer(G, s2), StartTrack(TrackRef(2, 0), "x2", null)), ready.effects)
        assertStep(
            active.copy(pending = fetch(5)),
            Fetched(5, listOf("x1", "x2", "x3"), startIndex = 2),
            MusicFlow(Active(s2.copy(start = 2), G, 0, Net.Online), lastSid = 2, lastTicket = 1),
            CancelSession(1),
            ReplaceQueue(G, s2.copy(start = 2)),
            StartTrack(TrackRef(2, 0), "x3", TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)),
        )
    }

    @Test
    fun `a shuffled Play starts its session shuffled in every phase`() {
        val shuffled = MusicEvent.Play(5, SRC, E1, shuffled = true)
        columns.forEach { from -> assertStep(from, shuffled, from.copy(pending = fetch(5, shuffled = true)), LaunchFetch(5, SRC, shuffled = true)) }
        val s2 = session(2, "x1", "x2").copy(shuffled = true, start = 1)
        val result = Fetched(5, listOf("x1", "x2"), startIndex = 1)
        val ready = run(idle.copy(pending = fetch(5, shuffled = true)), result, ServiceReady(G, 2))
        assertEquals(MusicFlow(Active(s2, G, 0, Net.Online), lastSid = 2, lastTicket = 2), ready.state)
        assertEquals(listOf(CloseVideo, StartService(2), BuildPlayer(G, s2), StartTrack(TrackRef(2, 0), "x2", null)), ready.effects)
        assertStep(
            active.copy(pending = fetch(5, shuffled = true)),
            result,
            MusicFlow(Active(s2, G, 0, Net.Online), lastSid = 2, lastTicket = 1),
            CancelSession(1),
            ReplaceQueue(G, s2),
            StartTrack(TrackRef(2, 0), "x2", TrackEnd(TrackRef(1, 2), EndAt.PlayerPosition)),
        )
    }

    @Test
    fun `a plain Play after a shuffled one plays in order, and Enqueue with nothing playing is never shuffled`() {
        val from = MusicFlow(Active(session(1).copy(shuffled = true), G, n = 0, Net.Online), lastSid = 1, lastTicket = 1)
        val replaced = run(from, play(5), Fetched(5, listOf("x1")))
        assertEquals(false, (replaced.state.phase as Active).session.shuffled)
        assertStep(idle, MusicEvent.Enqueue(5, EnqueueMode.PlayNext, SRC_B, E1, "Song B"), idle.copy(pending = Pending.Fetch(5, E1, SRC_B)), LaunchFetch(5, SRC_B))
    }

    @Test
    fun `an enqueue while Starting lands after the start row`() {
        val from = MusicFlow(Starting(session(1, "t1", "t2", "t3").copy(start = 1), ticket = 1), lastSid = 1, lastTicket = 1)
        val run = run(from, EnqueueFetched(1, EnqueueMode.AddToQueue, listOf("u1")))
        assertEquals(listOf("t1", "t2", "u1", "t3"), (run.state.phase as Starting).session.items.map { it.item })
    }

    @Test
    fun `ServiceStartFailed for the current ticket goes Idle, any other is ignored`() {
        assertStep(starting.copy(pending = fetch(5)), ServiceStartFailed(1), idle.copy(pending = fetch(5), notice = MusicNotice.StartFailed(1)), CancelSession(1))
        assertIgnored(ServiceStartFailed(STALE), starting)
        assertIgnored(ServiceStartFailed(1), idle, active)
    }

    @Test
    fun `a starting or playing session holds playback`() {
        assertEquals(listOf(false, true, true), columns.map { it.holdsPlayback })
    }

    /**
     * Every sequence of up to four events from five seeds, over every event with its current tag and a stale
     * one; [advance] checks the invariants after every step.
     */
    @Test
    fun `every event sequence up to length four keeps the invariants`() {
        val lost = Active(s1, G, 0, Net.Lost)
        val seeds = listOf(
            MusicFlow() to Path(nextR = 1, nextG = G),
            MusicFlow(pending = fetch(1)) to Path(nextR = 2, nextG = G, fetches = setOf(1)),
            MusicFlow(Starting(s1, 1), fetch(2), lastSid = 1, lastTicket = 1) to Path(nextR = 3, nextG = G, fetches = setOf(2), scopes = setOf(1)),
            MusicFlow(Active(s1, G, 0, Net.Online), lastSid = 1, lastTicket = 1) to activePath(),
            MusicFlow(lost, Pending.Refused(2, Refusal.Failed, SRC), lastSid = 1, lastTicket = 1) to activePath(),
        )
        var steps = 0
        seeds.forEach { (seed, path) ->
            var deepest = 0
            fun walk(state: MusicFlow, path: Path, depth: Int) {
                deepest = maxOf(deepest, depth)
                if (depth == 4) return
                for (event in alphabet(state, path)) {
                    val step = reduce(state, event)
                    steps++
                    walk(step.state, advance(path, state, event, step), depth + 1)
                }
            }
            walk(seed, path, 0)
            assertEquals("seed $seed reaches depth four", 4, deepest)
        }
        println("music sequences: $steps steps")
    }

    /** What one path carries: ids to mint, live services, open track reports, fetches in flight, live session scopes, tickets used. */
    private data class Path(
        val nextR: Long,
        val nextG: Long,
        val held: Set<Long> = emptySet(),
        val open: Set<TrackRef> = emptySet(),
        val started: Set<TrackRef> = emptySet(),
        val fetches: Set<Long> = emptySet(),
        val scopes: Set<Long> = emptySet(),
        val tickets: Set<Long> = emptySet(),
    )

    private fun activePath() = Path(nextR = 3, nextG = G + 1, held = setOf(G), open = setOf(TrackRef(1, 0)), started = setOf(TrackRef(1, 0)), scopes = setOf(1), tickets = setOf(1))

    private fun alphabet(state: MusicFlow, path: Path): List<MusicEvent> {
        val r = when (val p = state.pending) {
            is Pending.Fetch -> p.r
            is Pending.Refused -> p.r
            Pending.None -> STALE
        }
        val sid = state.session?.sid ?: STALE
        val ticket = (state.phase as? Starting)?.ticket ?: state.lastTicket
        val g = (state.phase as? Active)?.g
        return listOfNotNull(
            play(path.nextR),
            MusicEvent.Play(path.nextR, SRC_B, E1, shuffled = true),
            MusicEvent.Enqueue(path.nextR, EnqueueMode.AddToQueue, SRC_B, E1, "Song B"),
            MusicEvent.Enqueue(path.nextR, EnqueueMode.PlayNext, SRC_B, E2, "Song C"),
            MusicEvent.CancelFetch(r),
            MusicEvent.CancelFetch(STALE),
            Stop,
            VideoStarting,
            UserPlay,
            Transport(TransportOp.Next),
            Transport(TransportOp.Pause),
            Fetched(r, listOf("x1", "x2"), startIndex = 1),
            Fetched(STALE, listOf("x1")),
            FetchEmpty(r),
            FetchFailed(r),
            FetchFailed(STALE),
            EnqueueFetched(sid, EnqueueMode.PlayNext, listOf("u1")),
            EnqueueFetched(STALE, EnqueueMode.PlayNext, listOf("u1")),
            EnqueueFailed(sid, STALE),
            MusicEvent.Confirm(MusicNotice.Queued(path.nextR, EnqueueMode.AddToQueue, "Song A")),
            state.notice?.let { NoticeShown(it) } ?: NoticeShown(MusicNotice.StartFailed(STALE)),
            ServiceReady(path.nextG, ticket),
            ServiceReady(path.nextG, STALE),
            ServiceStartFailed(ticket),
            ServiceStartFailed(STALE),
            g?.let { ServiceReady(it, ticket) },
            g?.let { ServiceDestroyed(it, 40) },
            ServiceDestroyed(STALE, 40),
            TrackChanged(sid, "t2", 30),
            TrackChanged(STALE, "t2", 30),
            QueueEnded(sid, 50),
            QueueEnded(STALE, 50),
            NetworkLost(sid),
            NetworkLost(STALE),
            PlayerFailed(sid, isLast = false),
            PlayerFailed(sid, isLast = true),
            PlayerFailed(STALE, isLast = true),
            EpochsChanged(E1, null),
            EpochsChanged(E2, null),
            EpochsChanged(E2, E1),
        )
    }

    /** Checks the invariants for one step and returns the path after it. */
    private fun advance(path: Path, before: MusicFlow, event: MusicEvent, step: MusicStep): Path {
        val after = step.state
        val effects = step.effects
        val label = { "$event from $before gave $step" }
        fun holds(ok: Boolean, rule: String) {
            if (!ok) throw AssertionError("$rule; ${label()}")
        }
        val beforeActive = before.phase as? Active
        val afterActive = after.phase as? Active
        val beforeSession = before.session
        val afterSession = after.session

        // A stale tag changes nothing; only an orphan service is released.
        if (isStale(before, event)) {
            holds(after == before, "a stale result changes nothing")
            holds(effects.all { it == Release((event as? ServiceReady)?.g ?: -1) }, "a stale result only releases an orphan service")
        }

        // A service instance is held only in Active, and every service effect addresses that instance.
        var held = path.held
        if (event is ServiceReady) held = held + event.g
        if (event is ServiceDestroyed) held = held - event.g
        effects.filterIsInstance<Release>().forEach { holds(it.g in held, "only a live service is released"); held = held - it.g }
        holds(held == setOfNotNull(afterActive?.g), "a service is held only in Active")
        effects.mapNotNull { serviceTarget(it) }.forEach { holds(it == afterActive?.g, "a player effect addresses the Active service") }

        // Every Active exit releases its service, except the service's own destroy.
        if (beforeActive != null && afterActive?.g != beforeActive.g) {
            val destroyed = event is ServiceDestroyed && event.g == beforeActive.g
            holds((Release(beforeActive.g) in effects) == !destroyed, "an Active exit releases its service unless it was destroyed")
        }

        // Each StartTrack is ended exactly once; only the playing track is open.
        var open = path.open
        var started = path.started
        effects.forEach { e ->
            when (e) {
                is StartTrack -> {
                    e.ends?.let { holds(it.ref in open, "a track ends once, after its start"); open = open - it.ref }
                    holds(e.ref !in started, "a track report starts once")
                    started = started + e.ref
                    open = open + e.ref
                }
                is End -> {
                    holds(e.end.ref in open, "a track ends once, after its start")
                    open = open - e.end.ref
                }
                else -> Unit
            }
        }
        holds(open == setOfNotNull(afterActive?.ref), "only the playing track's report is open")

        // The queue is replaced, and a session minted, only by the current Fetched.
        val currentResult = (before.pending as? Pending.Fetch)?.r?.takeIf { r ->
            (event is Fetched && event.r == r) || (event is FetchEmpty && event.r == r) || (event is FetchFailed && event.r == r)
        }
        val currentFetched = event is Fetched && currentResult != null
        if (effects.any { it is ReplaceQueue }) holds(currentFetched, "the queue is replaced only by the current Fetched")
        val newSession = afterSession != null && afterSession.sid != beforeSession?.sid
        if (newSession) holds(currentFetched && afterSession!!.sid == before.lastSid + 1, "a session is minted only by the current Fetched")
        if (newSession) holds(afterSession!!.shuffled == (before.pending as Pending.Fetch).shuffled, "a session is shuffled exactly when its request was")
        if (afterSession != null && beforeSession?.sid == afterSession.sid) holds(afterSession.shuffled == beforeSession.shuffled, "a session keeps its shuffled start")
        if (afterSession != null && beforeSession?.sid == afterSession.sid && afterSession.items != beforeSession.items) {
            holds(event is EnqueueFetched && before.phase is Starting, "a session's items change only by an insert while Starting")
        }

        // The only fetch in flight is the waiting request's; none survives Stop, a video start or an account change.
        var fetches = path.fetches
        if (currentResult != null) fetches = fetches - currentResult
        effects.forEach { e ->
            when (e) {
                is LaunchFetch -> { holds(e.r !in fetches, "a fetch launches once"); fetches = fetches + e.r }
                is MusicEffect.CancelFetch -> { holds(e.r in fetches, "only a live fetch is cancelled"); fetches = fetches - e.r }
                else -> Unit
            }
        }
        holds(fetches == setOfNotNull((after.pending as? Pending.Fetch)?.r), "the only fetch in flight is the waiting request's")
        if (event == Stop || event == VideoStarting) holds(after.pending !is Pending.Fetch, "no fetch survives")
        if (event is EpochsChanged) {
            val live = setOfNotNull(event.current, event.parked)
            val kept = (before.pending as? Pending.Fetch)?.takeIf { it.epoch in live }
            holds(after.pending == (kept ?: Pending.None), "an account change drops every waiting request but a fetch on a live account")
            val epoch = beforeSession?.epoch
            holds(if (epoch != null && epoch !in live) after.phase == Idle else after.phase == before.phase, "an account change stops only a session on neither account")
        }

        // An enqueue joins only the session on its own account; any other plays as a new queue on that account.
        if (event is MusicEvent.Enqueue) {
            val joins = beforeSession?.epoch == event.epoch
            holds(effects.any { it is LaunchEnqueue } == joins, "an enqueue joins only the session on its own account")
            if (!joins) holds(after.pending == Pending.Fetch(event.r, event.epoch, event.source), "an enqueue with no session on its account waits as a new queue")
        }

        // Next or previous while the network is lost reconnect and play the new track.
        if (event is Transport && (event.op == TransportOp.Next || event.op == TransportOp.Previous) && beforeActive?.net == Net.Lost) {
            holds(effects == listOf(Apply(beforeActive.g, event.op), RetryPrepare(beforeActive.g)) && afterActive?.net == Net.Online, "a skip while lost reconnects")
        }

        // A session's scope, and its enqueue fetches, live exactly as long as the session.
        var scopes = path.scopes
        if (newSession) scopes = scopes + afterSession!!.sid
        effects.forEach { e ->
            when (e) {
                is CancelSession -> { holds(e.sid in scopes, "a scope is cancelled once"); scopes = scopes - e.sid }
                is LaunchEnqueue -> holds(e.sid == afterSession?.sid, "an enqueue fetch runs in the live session's scope")
                else -> Unit
            }
        }
        holds(scopes == setOfNotNull(afterSession?.sid), "a session's scope ends with it")

        // A service start: only from Idle, once per ticket, after any video is closed.
        var tickets = path.tickets
        effects.filterIsInstance<StartService>().forEach { s ->
            holds(before.phase == Idle && (after.phase as? Starting)?.ticket == s.ticket && s.ticket !in tickets, "a service starts once per session start")
            holds(effects.indexOf(CloseVideo) in 0 until effects.indexOf(s), "a video closes before the service starts")
            tickets = tickets + s.ticket
        }
        holds((CloseVideo in effects) == effects.any { it is StartService }, "a video closes only for a service start")
        if (after.phase is Starting && before.phase is Starting) holds((after.phase as Starting).ticket == (before.phase as Starting).ticket, "a restart keeps its ticket")

        holds(after.holdsPlayback == (after.phase != Idle), "a session holds playback while it starts or plays")

        // The current start's failure, a failed enqueue in the live session and a confirmation while there is one each leave their notice; only its own NoticeShown clears it.
        val expectedNotice = when {
            event is MusicEvent.Enqueue && beforeSession?.epoch == event.epoch -> MusicNotice.Queued(event.r, event.mode, event.title)
            event is ServiceStartFailed && !isStale(before, event) -> MusicNotice.StartFailed(event.ticket)
            event is EnqueueFailed && !isStale(before, event) -> MusicNotice.EnqueueFailed(event.r)
            event is MusicEvent.Confirm && before.phase != Idle -> event.notice
            event is NoticeShown && event.notice == before.notice -> null
            else -> before.notice
        }
        holds(after.notice == expectedNotice, "a notice is left by its own cause and cleared only by its own NoticeShown")

        // Settled: a stale result finds nothing to do.
        holds(reduce(after, TrackChanged(STALE, "t9", 1)) == MusicStep(after), "settled")

        return Path(
            nextR = if (event is MusicEvent.Play || event is MusicEvent.Enqueue) path.nextR + 1 else path.nextR,
            nextG = if (event is ServiceReady && event.g == path.nextG) path.nextG + 1 else path.nextG,
            held = held,
            open = open,
            started = started,
            fetches = fetches,
            scopes = scopes,
            tickets = tickets,
        )
    }

    /** The spec's stale rule, restated per tag: the request, session, ticket or instance an event names isn't the current one. */
    private fun isStale(state: MusicFlow, event: MusicEvent): Boolean {
        val fetchR = (state.pending as? Pending.Fetch)?.r
        val pendingR = fetchR ?: (state.pending as? Pending.Refused)?.r
        val sid = state.session?.sid
        val g = (state.phase as? Active)?.g
        return when (event) {
            is Fetched -> event.r != fetchR
            is FetchEmpty -> event.r != fetchR
            is FetchFailed -> event.r != fetchR
            is MusicEvent.CancelFetch -> event.r != pendingR
            is EnqueueFetched -> event.sid != sid
            is EnqueueFailed -> event.sid != sid
            is NoticeShown -> event.notice != state.notice
            is TrackChanged -> event.sid != sid
            is QueueEnded -> event.sid != sid
            is NetworkLost -> event.sid != sid
            is PlayerFailed -> event.sid != sid
            is ServiceReady -> (state.phase as? Starting)?.ticket != event.ticket && g != event.g
            is ServiceStartFailed -> (state.phase as? Starting)?.ticket != event.ticket
            is ServiceDestroyed -> event.g != g
            else -> false
        }
    }

    private fun serviceTarget(effect: MusicEffect): Long? = when (effect) {
        is BuildPlayer -> effect.g
        is ReplaceQueue -> effect.g
        is Insert -> effect.g
        is MusicEffect.Pause -> effect.g
        is MusicEffect.Play -> effect.g
        is RetryPrepare -> effect.g
        is SkipNext -> effect.g
        is Apply -> effect.g
        else -> null
    }

    private val MusicFlow.session: MusicSession?
        get() = when (val p = phase) {
            Idle -> null
            is Starting -> p.session
            is Active -> p.session
        }

    private data class Run(val state: MusicFlow, val effects: List<MusicEffect>)

    private fun run(from: MusicFlow, vararg events: MusicEvent): Run = events.fold(Run(from, emptyList())) { acc, event ->
        val step = reduce(acc.state, event)
        Run(step.state, acc.effects + step.effects)
    }

    private companion object {
        const val G = 7L
        const val STALE = 999L
        val E1 = 1uL
        val E2 = 2uL
        val SRC = MusicSource.Album("album-a")
        val SRC_B = MusicSource.Album("album-b")
        val MIX = MusicSource.Playlist("mix-a")

        fun session(sid: Long, vararg items: String = arrayOf("t1", "t2")) =
            MusicSession(sid, E1, SRC, items.map { QueueEntry(it, QueueOrigin.Source) })

        fun fetch(r: Long, source: MusicSource = SRC, shuffled: Boolean = false) = Pending.Fetch(r, E1, source, shuffled)

        fun play(r: Long, source: MusicSource = SRC) = MusicEvent.Play(r, source, E1)
    }
}
