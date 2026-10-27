package tv.jellybeam.ui.watchtogether

import kotlinx.coroutines.CompletableDeferred
import uniffi.jellybeam_core.Card
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.player.GroupEvent
import tv.jellybeam.player.GroupOutcome
import tv.jellybeam.player.PlaybackActivityTracker
import tv.jellybeam.player.PlayerEvent
import tv.jellybeam.player.PlayerHandle
import tv.jellybeam.player.SyncPlayExecutor
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayRoom
import uniffi.jellybeam_core.SyncPlayRooms
import uniffi.jellybeam_core.SyncPlayStage
import uniffi.jellybeam_core.SyncPlayUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherViewModelTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val hostInLobby = SyncPlayGroup.Member(
        role = SyncPlayRole.HOST,
        stage = SyncPlayStage.FINISHED,
        host = SyncPlayHost.Me,
        participants = listOf("member-a", "member-b"),
        phase = SyncPlayPhase.WAITING,
        connected = true,
        promptShown = false,
    )

    private class Rig(
        scope: TestScope,
        inPip: () -> Boolean = { false },
        stopPip: suspend () -> Unit = {},
        cardById: suspend (String) -> Card? = { null },
    ) {
        val gateway = FakeCoreGateway().apply { syncPlayGroup = SyncPlayGroup.Starting(name = "member-a's group") }
        var creates = 0
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val build: (ULong) -> SyncPlayExecutor = { epoch ->
            SyncPlayExecutor(gateway, epoch, {}, scope.backgroundScope, dispatcher) { 0L }
        }
        val viewModel = WatchTogetherViewModel(
            held = HeldItem("item-a", 0L),
            running = { SyncPlayExecutor.current.value },
            epoch = { 1uL },
            enter = { entry, epoch ->
                creates++
                check(entry is Entry.Create)
                SyncPlayExecutor.create(epoch, gateway, build)
            },
            listRooms = { gateway.syncPlayListRooms(it) },
            inPip = inPip,
            stopPip = stopPip,
            nowMs = { 77L },
            cardById = cardById,
        )
    }

    /** Ends whatever membership the body left, so the process slot is empty for the next test. */
    private fun vmTest(body: suspend TestScope.(Rig) -> Unit) = vmTest({ Rig(it) }, body)

    private fun vmTest(rigOf: (TestScope) -> Rig, body: suspend TestScope.(Rig) -> Unit) = runTest {
        val rig = rigOf(this)
        try {
            body(rig)
        } finally {
            SyncPlayExecutor.onEpochMoved()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a title chosen above the lobby is the one Start sends, from the same group`() = vmTest { rig ->
        rig.viewModel.setActive(true)
        advanceUntilIdle()
        val exec = (rig.viewModel.state.value as TogetherState.Member).exec
        rig.gateway.syncPlayGroup = hostInLobby
        rig.gateway.syncPlayListener!!.onStep(SyncPlayUpdate(emptyList(), emptyList(), changed = true))
        advanceUntilIdle()

        // The browse above the lobby hides it, and its return makes it top again.
        rig.viewModel.setActive(false)
        rig.viewModel.send(TogetherEvent.Hold(HeldItem("item-b", 9_000L)))
        rig.viewModel.setActive(true)
        rig.viewModel.send(TogetherEvent.Start(9_000L))
        advanceUntilIdle()

        assertEquals(1, rig.creates)
        assertEquals(exec, (rig.viewModel.state.value as TogetherState.Member).exec)
        assertEquals(listOf(SyncPlayInput.PickItem(listOf("item-b"), 0, 9_000L)), rig.gateway.syncPlayInputs.filterIsInstance<SyncPlayInput.PickItem>())
    }

    /** docs/28 §1: the stopped player's final stop is counted at its destroy, so the create waits for that, not its PiP exit. */
    @Test
    fun `stop on a PiP refusal hosts the held title only after the stopped player is destroyed`() {
        val tracker = PlaybackActivityTracker()
        var g = 0L
        var closes = 0
        // A stand-in player: its finishAndRemoveTask reaches the tracker as Leaving, as the real override does.
        g = tracker.created(PlayerHandle(finishAndRemoveTask = { closes++; tracker.send(PlayerEvent.Leaving(g)) }, liveItemId = { "item-b" }))
        tracker.send(PlayerEvent.PipRequested(g))
        tracker.send(PlayerEvent.PipEntered(g))
        vmTest({ scope ->
            Rig(scope, inPip = { tracker.inPip }, stopPip = tracker::stopForGroup).also { it.gateway.syncPlayGroup = SyncPlayGroup.Idle }
        }) { rig ->
            rig.viewModel.setActive(true)
            advanceUntilIdle()
            assertEquals(TogetherState.CreateRefused(HeldItem("item-a", 0L), 1uL), rig.viewModel.state.value)

            rig.gateway.syncPlayGroup = SyncPlayGroup.Starting(name = "member-a's group")
            rig.viewModel.send(TogetherEvent.Stop)
            advanceUntilIdle()
            assertEquals(1, closes)
            assertEquals(1, rig.creates)

            tracker.send(PlayerEvent.PipExited(g, started = false))
            advanceUntilIdle()
            assertEquals("the PiP exit closes nothing more", 1, closes)
            assertEquals("no create before the destroy", 1, rig.creates)

            tracker.destroyed(g)
            advanceUntilIdle()
            assertEquals(2, rig.creates)
            assertTrue(rig.viewModel.state.value is TogetherState.Member)
        }
    }

    @Test
    fun `a second Start while the first one's queue is still read sends only the second choice`() {
        val slow = CompletableDeferred<Unit>()
        var reads = 0
        vmTest({ scope -> Rig(scope, cardById = { if (reads++ == 0) slow.await(); null }) }) { rig ->
            rig.viewModel.setActive(true)
            advanceUntilIdle()
            rig.gateway.syncPlayGroup = hostInLobby
            rig.gateway.syncPlayListener!!.onStep(SyncPlayUpdate(emptyList(), emptyList(), changed = true))
            advanceUntilIdle()

            rig.viewModel.send(TogetherEvent.Start(0L))
            advanceUntilIdle()
            rig.viewModel.send(TogetherEvent.Start(9_000L))
            advanceUntilIdle()
            slow.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(SyncPlayInput.PickItem(listOf("item-a"), 0, 9_000L)), rig.gateway.syncPlayInputs.filterIsInstance<SyncPlayInput.PickItem>())
        }
    }

    @Test
    fun `a notice reaching the lobby is stamped with the time it arrived`() = vmTest { rig ->
        rig.viewModel.setActive(true)
        advanceUntilIdle()
        rig.gateway.syncPlayGroup = hostInLobby
        rig.gateway.syncPlayListener!!.onStep(SyncPlayUpdate(emptyList(), listOf(SyncPlayNotice.UserJoined("member-b")), changed = true))
        advanceUntilIdle()

        val memory = (rig.viewModel.state.value as TogetherState.Member).memory
        assertEquals(Stamped(SyncPlayNotice.UserJoined("member-b"), 77L), memory.log)
    }

    @Test
    fun `back while the create is in flight leaves the group the core may already hold`() = vmTest { rig ->
        val gate = CompletableDeferred<Unit>()
        rig.gateway.syncPlayEntryGate = gate
        rig.viewModel.setActive(true)
        advanceUntilIdle()
        assertTrue(rig.viewModel.state.value is TogetherState.Entering)

        rig.viewModel.send(TogetherEvent.Back)
        advanceUntilIdle()
        runCurrent()

        assertEquals(TogetherState.Done(GroupOutcome.Silent), rig.viewModel.state.value)
        assertEquals(listOf<SyncPlayInput>(SyncPlayInput.Leave), rig.gateway.syncPlayInputs)
        assertEquals(null, SyncPlayExecutor.current.value)
    }

    @Test
    fun `the group's end reaches the lobby and ends the screen`() = vmTest { rig ->
        rig.viewModel.setActive(true)
        advanceUntilIdle()
        val exec = (rig.viewModel.state.value as TogetherState.Member).exec

        exec.send(GroupEvent.EpochMoved)
        advanceUntilIdle()

        assertEquals(TogetherState.Done(GroupOutcome.Silent), rig.viewModel.state.value)
    }

    // -- The room list (docs/28 §1, §8) ------------------------------------------------------

    /** A drawer-opened screen; its executor joins rooms on [gateway]. */
    private class DrawerRig(scope: TestScope) {
        val gateway = FakeCoreGateway().apply {
            syncPlayGroup = SyncPlayGroup.Starting(name = null)
            syncPlayRooms = SyncPlayRooms.Listed(listOf(SyncPlayRoom("room-a", "member-a's group", listOf("member-a"), SyncPlayPhase.IDLE)))
        }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val build: (ULong) -> SyncPlayExecutor = { epoch -> SyncPlayExecutor(gateway, epoch, {}, scope.backgroundScope, dispatcher) { 0L } }
        val viewModel = WatchTogetherViewModel(
            held = null,
            running = { SyncPlayExecutor.current.value },
            epoch = { 1uL },
            enter = { entry, epoch ->
                when (entry) {
                    is Entry.Join -> SyncPlayExecutor.join(epoch, entry.target, build)
                    is Entry.Create -> SyncPlayExecutor.create(epoch, gateway, build)
                }
            },
            listRooms = { gateway.syncPlayListRooms(it) },
            inPip = { false },
            stopPip = {},
        )
    }

    /** Never [advanceUntilIdle] while the list polls: each answer arms the next poll. */
    private fun drawerTest(body: suspend TestScope.(DrawerRig) -> Unit) = runTest {
        val rig = DrawerRig(this)
        try {
            body(rig)
        } finally {
            rig.viewModel.setActive(false)
            SyncPlayExecutor.onEpochMoved()
            advanceUntilIdle()
        }
    }

    @Test
    fun `an unshown room list costs nothing`() = drawerTest { rig ->
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, rig.gateway.syncPlayCalls)
    }

    @Test
    fun `the drawer's list fetches at once, every five seconds while shown, and never once hidden`() = drawerTest { rig ->
        rig.viewModel.setActive(true)
        runCurrent()
        assertEquals(1, rig.gateway.syncPlayCalls)
        assertTrue((rig.viewModel.state.value as TogetherState.Rooms).list is RoomList.Listed)
        advanceTimeBy(ROOMS_POLL_MS)
        runCurrent()
        assertEquals(2, rig.gateway.syncPlayCalls)

        rig.viewModel.setActive(false)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, rig.gateway.syncPlayCalls)

        rig.viewModel.setActive(true)
        runCurrent()
        assertEquals(3, rig.gateway.syncPlayCalls)
    }

    @Test
    fun `pressing a room joins that room under its listed name and stops the list's poll`() = drawerTest { rig ->
        rig.viewModel.setActive(true)
        runCurrent()
        rig.viewModel.send(TogetherEvent.PickRoom("room-a", "member-a's group"))
        runCurrent()
        assertEquals(listOf("room-a"), rig.gateway.syncPlayRoomJoins)
        val member = rig.viewModel.state.value as TogetherState.Member
        assertEquals("the guest's lobby names the room it picked", "member-a's group", lobbyView(member.exec.state.value, member.origin).name)
        val calls = rig.gateway.syncPlayCalls
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("no list fetch while in the room", calls, rig.gateway.syncPlayCalls)
    }
}
