package tv.jellybeam.player

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.PlayerFlow.Player

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackActivityTrackerTest {
    /** A stand-in Activity: its finishAndRemoveTask reaches the tracker as Leaving, as the real override does. */
    private class TrackedPlayer(private val tracker: PlaybackActivityTracker, private val item: String) {
        var g = 0L
        var closes = 0
        val handle = PlayerHandle(finishAndRemoveTask = { closes++; tracker.send(PlayerEvent.Leaving(g)) }, liveItemId = { item })

        fun create() = apply { g = tracker.created(handle) }
    }

    @Test
    fun `a group stop with no player returns at once`() = runTest {
        val tracker = PlaybackActivityTracker()
        val stop = async { tracker.stopForGroup() }
        runCurrent()
        assertTrue(stop.isCompleted)
    }

    @Test
    fun `a player stays active until every instance, an older one tearing down included, is destroyed`() {
        val tracker = PlaybackActivityTracker()
        val first = TrackedPlayer(tracker, "item-a").create()
        val second = TrackedPlayer(tracker, "item-b").create()

        tracker.destroyed(second.g)
        assertTrue(tracker.isActive)
        tracker.destroyed(first.g)
        assertFalse(tracker.isActive)
    }

    /** docs/28 §1: a group stop closes a fullscreen, shrinking, PiP or closing player at most once and returns only at its destroy. */
    @Test
    fun `a group stop closes any live player once and returns only at its destroy`() = runTest {
        val setups: List<Pair<String, PlaybackActivityTracker.(Long) -> Unit>> = listOf(
            "fullscreen" to {},
            "shrinking" to { send(PlayerEvent.PipRequested(it)) },
            "pip" to { send(PlayerEvent.PipRequested(it)); send(PlayerEvent.PipEntered(it)) },
            "closing" to { send(PlayerEvent.Leaving(it)) },
        )
        setups.forEach { (name, setup) ->
            val tracker = PlaybackActivityTracker()
            val player = TrackedPlayer(tracker, "item-a").create()
            tracker.setup(player.g)
            val stop = async { tracker.stopForGroup() }
            runCurrent()
            assertEquals(name, if (name == "closing") 0 else 1, player.closes)
            assertFalse(name, stop.isCompleted)

            // The platform's PiP exit that the close causes closes nothing more.
            tracker.send(PlayerEvent.PipExited(player.g, started = false))
            runCurrent()
            assertEquals(name, if (name == "closing") 0 else 1, player.closes)
            assertFalse(name, stop.isCompleted)

            tracker.destroyed(player.g)
            runCurrent()
            assertTrue(name, stop.isCompleted)
        }
    }

    /** docs/28 §1: the stop waits for the instance it stopped, which a newer create only moves to draining. */
    @Test
    fun `a group stop still waits for its player once a newer one takes the slot`() = runTest {
        val tracker = PlaybackActivityTracker()
        val older = TrackedPlayer(tracker, "item-a").create()
        val stop = async { tracker.stopForGroup() }
        runCurrent()
        val newer = TrackedPlayer(tracker, "item-b").create()
        runCurrent()
        assertFalse(stop.isCompleted)

        tracker.destroyed(older.g)
        runCurrent()
        assertTrue(stop.isCompleted)
        assertEquals(0, newer.closes)
    }

    /** docs/28 §3.3: a group create waits on this, so it must not open before the stop counted in onStop. */
    @Test
    fun `a stopped player is gone only at its destroy, after its final stop is counted`() = runTest {
        val tracker = PlaybackActivityTracker()
        val player = TrackedPlayer(tracker, "item-a").create()
        tracker.send(PlayerEvent.PipEntered(player.g))
        val gone = async { tracker.stopForGroup() }
        tracker.onFinalStopLaunched()
        runCurrent()
        assertFalse(gone.isCompleted)

        tracker.destroyed(player.g)
        runCurrent()
        assertTrue(gone.isCompleted)
        val stops = async { tracker.awaitFinalStops() }
        runCurrent()
        assertFalse(stops.isCompleted)
        tracker.onFinalStopHandedOff()
        runCurrent()
        assertTrue(stops.isCompleted)
    }

    @Test
    fun `final stops are awaited until each has reached the core`() = runTest {
        val tracker = PlaybackActivityTracker()
        tracker.onFinalStopLaunched()
        tracker.onFinalStopLaunched()
        val idle = async { tracker.awaitFinalStops() }
        runCurrent()
        assertFalse(idle.isCompleted)

        tracker.onFinalStopHandedOff()
        runCurrent()
        assertFalse(idle.isCompleted)

        tracker.onFinalStopHandedOff()
        runCurrent()
        assertTrue(idle.isCompleted)
    }

    /** docs/17 §3: an older instance's onDestroy lands after its replacement's create and PiP entry. */
    @Test
    fun `a destroyed older player never clears the newer player's PiP state or handle`() {
        val tracker = PlaybackActivityTracker()
        val older = TrackedPlayer(tracker, "item-old").create()
        tracker.send(PlayerEvent.PipEntered(older.g))
        tracker.send(PlayerEvent.Leaving(older.g))
        val newer = TrackedPlayer(tracker, "item-new").create()
        tracker.send(PlayerEvent.PipEntered(newer.g))

        tracker.destroyed(older.g)

        assertEquals(Player.Pip(newer.g), tracker.state.value.player)
        assertTrue(tracker.inPip)
        tracker.send(HostEvent.GroupStop)
        assertEquals(1, newer.closes)
        assertEquals(0, older.closes)
    }

    /** docs/17 §3: a PiP dismissal closes through the player's own handle; the Leaving it causes, and a later group stop, close nothing more. */
    @Test
    fun `a PiP dismissal closes its player exactly once`() {
        val tracker = PlaybackActivityTracker()
        val player = TrackedPlayer(tracker, "item-a").create()
        tracker.send(PlayerEvent.PipEntered(player.g))

        tracker.send(PlayerEvent.PipExited(player.g, started = false))
        tracker.send(PlayerEvent.PipExited(player.g, started = false))
        tracker.send(HostEvent.GroupStop)

        assertEquals(1, player.closes)
        assertEquals(Player.Closing(player.g), tracker.state.value.player)
        assertFalse(tracker.inPip)
        assertTrue(tracker.isActive)
    }

    /** docs/17 §3: a Fresh request reads the live item from the slot's player, never from an older one, whose destroy detaches only its own handle. */
    @Test
    fun `a fresh request reads the live item from the slot's player, not an older one`() {
        listOf("item-new" to 0, "item-old" to 1).forEach { (item, closes) ->
            val tracker = PlaybackActivityTracker().apply { send(HostEvent.RootChanged(PlayerFlow.Root.Home)) }
            RecordingHost(tracker).also { tracker.attachHost(it.host) }
            val older = TrackedPlayer(tracker, "item-old").create()
            tracker.send(PlayerEvent.Leaving(older.g))
            val newer = TrackedPlayer(tracker, "item-new").create()
            tracker.destroyed(older.g)
            tracker.arrive(Req.Kind.Play(item), Req.Origin.External)
            tracker.send(PlayerEvent.PipRequested(newer.g))
            tracker.send(PlayerEvent.PipEntered(newer.g))

            assertEquals(item, closes, newer.closes)
        }
    }

    /** A host that records each start and toast; [fails] makes the platform call throw, reported as LaunchFailed. */
    private class RecordingHost(private val tracker: PlaybackActivityTracker, var fails: Boolean = false) {
        val started = mutableListOf<Req<Req.Kind.Play>>()
        var toasts = 0
        var leaves = 0
        val host = RequestHost(
            start = { if (fails) tracker.send(HostEvent.LaunchFailed(it.id)) else started += it },
            signInRequired = { toasts++ },
            leaveGroup = { leaves++ },
        )
    }

    private fun PlaybackActivityTracker.onHome() = apply {
        send(HostEvent.RootChanged(PlayerFlow.Root.Home))
        send(HostEvent.MainShown)
    }

    @Test
    fun `an arriving request launches through the host with its own minted id`() {
        val tracker = PlaybackActivityTracker().onHome()
        val host = RecordingHost(tracker).also { tracker.attachHost(it.host) }
        tracker.arrive(Req.Kind.Play("item-a", Req.Start.Beginning), Req.Origin.External)

        assertEquals(listOf(Req(1, Req.Kind.Play("item-a", Req.Start.Beginning), Req.Origin.External)), host.started)
        assertEquals(Player.Launching(1), tracker.state.value.player)
    }

    @Test
    fun `a start that fails, or finds no host, leaves the slot empty`() {
        val tracker = PlaybackActivityTracker().onHome()
        tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.External)
        assertEquals("no host attached", Player.None, tracker.state.value.player)

        val host = RecordingHost(tracker, fails = true).also { tracker.attachHost(it.host) }
        tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.External)
        assertEquals(Player.None, tracker.state.value.player)
        assertEquals(emptyList<Req<*>>(), host.started)
    }

    /** docs/17 §3: a PLAY that shrank a fullscreen player keeps it for the item its handle reports, and closes it for another. */
    @Test
    fun `a request over a covering player reads the live item from the PiP player's handle`() {
        listOf("item-a" to 0, "item-b" to 1).forEach { (item, closes) ->
            val tracker = PlaybackActivityTracker()
            tracker.send(HostEvent.RootChanged(PlayerFlow.Root.Home))
            RecordingHost(tracker).also { tracker.attachHost(it.host) }
            val player = TrackedPlayer(tracker, "item-a").create()
            tracker.arrive(Req.Kind.Play(item), Req.Origin.External)
            tracker.send(PlayerEvent.PipRequested(player.g))
            tracker.send(PlayerEvent.PipEntered(player.g))

            assertEquals(item, closes, player.closes)
        }
    }

    @Test
    fun `a signed-out external PLAY asks the host for sign-in feedback`() {
        val tracker = PlaybackActivityTracker()
        val host = RecordingHost(tracker).also { tracker.attachHost(it.host) }
        tracker.send(HostEvent.RootChanged(PlayerFlow.Root.SignedOut))
        tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.External)

        assertEquals(1, host.toasts)
        assertEquals(emptyList<Req<*>>(), host.started)
    }

    /** docs/28 §4: a group load needs no Home root, and its start carries the group extra; any other origin's does not. */
    @Test
    fun `a group launch intent carries the group extra`() {
        val tracker = PlaybackActivityTracker().apply { send(HostEvent.MainShown) }
        val host = RecordingHost(tracker).also { tracker.attachHost(it.host) }
        tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.Group)

        assertEquals(listOf(Req(1, Req.Kind.Play("item-a"), Req.Origin.Group)), host.started)
        assertEquals(PlayerLaunch("item-a", fromBeginning = false, startMs = null, group = true), playerLaunch(host.started.single()))
        Req.Origin.entries.filter { it != Req.Origin.Group }.forEach { origin ->
            assertFalse("$origin", playerLaunch(Req(2, Req.Kind.Play("item-b"), origin)).group)
        }
        assertEquals(PlayerLaunch("item-b", true, null, false), playerLaunch(Req(3, Req.Kind.Play("item-b", Req.Start.Beginning), Req.Origin.InApp)))
        assertEquals(PlayerLaunch("item-b", false, 90_000L, false), playerLaunch(Req(4, Req.Kind.Play("item-b", Req.Start.At(90_000L)), Req.Origin.External)))
    }

    /** docs/28 §4: the host leaves the group when an in-app Play replaces the group's load waiting on a closing player. */
    @Test
    fun `replacing a waiting group load asks the host to leave the group`() {
        val tracker = PlaybackActivityTracker().onHome()
        val host = RecordingHost(tracker).also { tracker.attachHost(it.host) }
        val player = TrackedPlayer(tracker, "item-a").create()
        tracker.send(PlayerEvent.Leaving(player.g))
        tracker.arrive(Req.Kind.Play("item-b"), Req.Origin.Group)
        assertEquals(0, host.leaves)

        tracker.arrive(Req.Kind.Play("item-c"), Req.Origin.InApp)
        assertEquals(1, host.leaves)
        tracker.destroyed(player.g)
        assertEquals(listOf(Req(2, Req.Kind.Play("item-c"), Req.Origin.InApp)), host.started)
    }

    /** docs/17 §3: an in-app Play is a request to the process's owner, not a direct Activity start. */
    @Test
    fun `an in-app launch arrives at the owner as an in-app request`() {
        val tracker = PlaybackActivityTracker()
        fun waiting() = (tracker.state.value.request as PlayerFlow.Request.Waiting).req
        PlaybackActivity.launch("item-a", startFromBeginning = true, tracker = tracker)
        assertEquals(Req(1, Req.Kind.Play("item-a", Req.Start.Beginning), Req.Origin.InApp), waiting())
        PlaybackActivity.launch("item-b", tracker = tracker)
        assertEquals(Req(2, Req.Kind.Play("item-b", Req.Start.Resume), Req.Origin.InApp), waiting())
    }

    /** A host whose platform call runs later, as the update owner may defer it; [runDeferred] runs each start the owner still confirms. */
    private class DeferringHost(private val tracker: PlaybackActivityTracker) {
        private val pending = mutableListOf<Req<Req.Kind.Play>>()
        val started = mutableListOf<Req<Req.Kind.Play>>()
        val host = RequestHost(start = { pending += it }, signInRequired = {}, leaveGroup = {})

        fun runDeferred(order: (List<Req<Req.Kind.Play>>) -> List<Req<Req.Kind.Play>> = { it }) {
            val due = order(pending.toList()).also { pending.clear() }
            due.forEach { if (tracker.confirmStart(it.id)) started += it }
        }
    }

    /** docs/18 §2.1, docs/17 §3: a launch withdrawn before its deferred start runs never starts, and the slot is free again. */
    @Test
    fun `a deferred launch withdrawn by an account change, an app exit or a hidden MainActivity never starts`() {
        listOf(HostEvent.AccountReset, HostEvent.AppExit, HostEvent.MainHidden).forEach { withdraw ->
            val tracker = PlaybackActivityTracker().onHome()
            val host = DeferringHost(tracker).also { tracker.attachHost(it.host) }
            tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.External)
            assertEquals("$withdraw", Player.Launching(1), tracker.state.value.player)

            tracker.send(withdraw)
            host.runDeferred()
            assertEquals("$withdraw", emptyList<Req<*>>(), host.started)
            assertEquals("$withdraw", Player.None, tracker.state.value.player)

            tracker.onHome()
            assertEquals("$withdraw: a reopen starts nothing", emptyList<Req<*>>(), host.started)
            tracker.arrive(Req.Kind.Play("item-b"), Req.Origin.InApp)
            host.runDeferred()
            assertEquals("$withdraw: the next request starts", listOf(Req(2, Req.Kind.Play("item-b"), Req.Origin.InApp)), host.started)
        }
    }

    /** docs/17 §3: of two deliveries still waiting to start, only the newer one runs, whichever callback comes first. */
    @Test
    fun `of two deferred deliveries only the newer starts`() {
        listOf<(List<Req<Req.Kind.Play>>) -> List<Req<Req.Kind.Play>>>({ it }, { it.reversed() }).forEach { order ->
            val tracker = PlaybackActivityTracker().apply { send(HostEvent.RootChanged(PlayerFlow.Root.Home)) }
            val host = DeferringHost(tracker).also { tracker.attachHost(it.host) }
            val player = TrackedPlayer(tracker, "item-a").create()
            tracker.arrive(Req.Kind.Play("item-b"), Req.Origin.InApp)
            tracker.arrive(Req.Kind.Play("item-c"), Req.Origin.InApp)

            host.runDeferred(order)
            assertEquals(listOf(Req(2, Req.Kind.Play("item-c"), Req.Origin.InApp)), host.started)
            assertEquals(Player.Fullscreen(player.g), tracker.state.value.player)
        }
    }

    /** docs/28 §1: a create refused over a player shrinking into, in, or expanding out of PiP offers to stop it. */
    @Test
    fun `a player shrinking into, in, or expanding out of PiP counts as in PiP`() {
        val tracker = PlaybackActivityTracker().onHome()
        DeferringHost(tracker).also { tracker.attachHost(it.host) }
        val player = TrackedPlayer(tracker, "item-a").create()
        assertFalse("fullscreen", tracker.inPip)
        tracker.send(PlayerEvent.PipRequested(player.g))
        assertTrue("shrinking", tracker.inPip)
        tracker.send(PlayerEvent.PipEntered(player.g))
        assertTrue("pip", tracker.inPip)
        tracker.arrive(Req.Kind.Play("item-b"), Req.Origin.InApp)
        assertTrue("expanding", tracker.state.value.player is Player.Expanding)
        assertTrue("expanding", tracker.inPip)
        tracker.send(PlayerEvent.Leaving(player.g))
        assertFalse("closing", tracker.inPip)
    }

    /** The handle's live item reads a view model set after the Activity's create, so it is read only when a Fresh request meets its PiP. */
    @Test
    fun `a player's live item is never read unless a fresh request settles against its PiP`() {
        val tracker = PlaybackActivityTracker().onHome()
        RecordingHost(tracker).also { tracker.attachHost(it.host) }
        val g = tracker.created(PlayerHandle(finishAndRemoveTask = {}, liveItemId = { error("view model not set") }))
        tracker.arrive(Req.Kind.Play("item-a"), Req.Origin.External)
        tracker.send(PlayerEvent.PipRequested(g))
        tracker.send(PlayerEvent.PipEntered(g))
        tracker.send(PlayerEvent.Resumed(g))
        tracker.send(HostEvent.MainHidden)
        tracker.send(PlayerEvent.Leaving(g))
        tracker.destroyed(g)

        assertEquals(Player.None, tracker.state.value.player)
    }
}
