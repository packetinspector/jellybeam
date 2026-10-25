package tv.jellybeam.ui.watchtogether

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.player.GroupEvent
import tv.jellybeam.player.GroupOutcome
import tv.jellybeam.player.JoinTarget
import tv.jellybeam.player.SyncPlayExecutor
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRoom

private const val EPOCH = 1uL
private val itemA = HeldItem("item-a", 0L)
private val roomA = SyncPlayRoom("room-a", "sp-alice's group", listOf("sp-alice", "sp-bob"), SyncPlayPhase.IDLE)
private val joinA = JoinTarget("room-a", "sp-alice's group")
private val roomB = SyncPlayRoom("room-b", "", listOf("sp-carol"), SyncPlayPhase.PLAYING)
private val roomC = SyncPlayRoom("room-c", "", listOf("sp-dave"), null)

@OptIn(ExperimentalCoroutinesApi::class)
private val testScope = TestScope()

@OptIn(ExperimentalCoroutinesApi::class)
private fun exec() = SyncPlayExecutor(FakeCoreGateway(), EPOCH, {}, testScope, StandardTestDispatcher(testScope.testScheduler)) { 0L }

private val running = exec()

/** The world a sweep path built: entries made and answered, whether the screen shows, and the one live poll job. */
private data class RoomWorld(
    val entries: List<SyncPlayExecutor> = emptyList(),
    val answered: Int = 0,
    val shown: Boolean = false,
    val job: PollTag? = null,
) {
    fun current(): SyncPlayExecutor? = entries.getOrNull(answered - 1)
}

/** One letter: the event it sends in [RoomWorld] and the world after it is sent, or `null` where it can't happen. */
private class RoomLetter(val name: String, val event: (RoomWorld) -> Pair<TogetherEvent, RoomWorld>?)

/** A viewer's action, which only a screen on top can receive. */
private fun user(name: String, event: TogetherEvent) = RoomLetter(name) { w -> (event to w).takeIf { w.shown } }

/** The live poll's answer, which ends its job. */
private fun fetched(name: String, fetch: RoomsFetch) = RoomLetter(name) { w -> w.job?.let { TogetherEvent.Fetched(it, fetch) to w.copy(job = null) } }

private fun answer(name: String, result: (SyncPlayExecutor) -> EntryResult) = RoomLetter(name) { w ->
    w.entries.getOrNull(w.answered)?.let { TogetherEvent.EntryDone(result(it), w.shown) to w.copy(answered = w.answered + 1) }
}

private fun ended(name: String, vararg notices: SyncPlayNotice) =
    RoomLetter(name) { w -> w.current()?.let { TogetherEvent.ExecEnded(it, notices.toList(), w.shown) to w } }

/** docs/28 §1, §8: the room-list alphabet. */
private val alphabet = listOf(
    RoomLetter("activate") { w -> TogetherEvent.Activate(null, EPOCH) to w.copy(shown = true) },
    RoomLetter("activate-running") { w -> TogetherEvent.Activate(running, EPOCH) to w.copy(shown = true) },
    RoomLetter("deactivate") { w -> TogetherEvent.Deactivate to w.copy(shown = false) },
    fetched("fetched-listed", RoomsFetch.Listed(listOf(roomA, roomB))),
    fetched("fetched-failed", RoomsFetch.Failed),
    fetched("fetched-no-access", RoomsFetch.NoAccess),
    fetched("fetched-account-changed", RoomsFetch.AccountChanged),
    RoomLetter("fetched-stale") { w -> TogetherEvent.Fetched(PollTag(), RoomsFetch.Listed(listOf(roomC))) to w },
    user("pick-room", TogetherEvent.PickRoom("room-a", "sp-alice's group")),
    answer("started") { EntryResult.Started(it) },
    answer("failed") { EntryResult.Failed },
    ended("ended-gone", SyncPlayNotice.NoSuchGroup),
    ended("ended-host-left", SyncPlayNotice.Joined, SyncPlayNotice.HostLeft),
    user("start", TogetherEvent.Start(0L)),
    user("hold", TogetherEvent.Hold(itemA)),
    user("back", TogetherEvent.Back),
    RoomLetter("line-expired") { w -> TogetherEvent.LineExpired to w },
)

class RoomsReduceTest {

    private fun step(state: TogetherState, event: TogetherEvent) = reduce(state, event)

    private fun rooms(list: RoomList, poll: RoomsPoll = RoomsPoll.Off, line: RoomsLine? = null) = TogetherState.Rooms(EPOCH, list, poll, line)

    private fun polling(list: RoomList, line: RoomsLine? = null): Pair<TogetherState.Rooms, PollTag> = PollTag().let { rooms(list, RoomsPoll.On(it), line) to it }

    /** The poll a step started, if it started one. */
    private fun pollOf(step: TogetherStep) = step.effects.filterIsInstance<TogetherEffect.Poll>().singleOrNull()

    /** Asserts [step] lands on [expected] polling at once (its fresh tag), with [also] before the poll. */
    private fun assertRepolls(expected: TogetherState.Rooms, step: TogetherStep, delayMs: Long = 0L, vararg also: TogetherEffect) {
        val poll = pollOf(step) ?: throw AssertionError("no poll in ${step.effects}")
        assertEquals(TogetherStep(expected.copy(poll = RoomsPoll.On(poll.tag)), also.toList() + TogetherEffect.Poll(poll.tag, EPOCH, delayMs)), step)
    }

    // -- docs/28 §1, §8 sweep: every sequence up to length 4, three seeds ----------------------

    @Test
    fun `every sequence up to four events keeps the room list invariants`() {
        val listed = rooms(RoomList.Listed(listOf(roomA, roomB)))
        val seeds = listOf(
            TogetherState.Idle(null) to RoomWorld(),
            TogetherState.Idle(itemA) to RoomWorld(),
            TogetherState.Member(running, Origin.Joined(joinA, listed)) to RoomWorld(listOf(running), 1, shown = true),
        )
        var paths = 0
        fun walk(state: TogetherState, world: RoomWorld, path: List<String>) {
            if (path.size == 4) return
            for (letter in alphabet) {
                val (event, sent) = letter.event(world) ?: continue
                val now = path + letter.name
                val next = step(state, event)
                paths++
                val after = check(state, event, next, world, sent, now)
                walk(next.state, after, now)
            }
        }
        seeds.forEach { (state, world) -> walk(state, world, emptyList()) }
        assertTrue("sweep ran", paths > 20_000)
    }

    /** Checks one step and returns the world after its effects ran. */
    private fun check(before: TogetherState, event: TogetherEvent, next: TogetherStep, world: RoomWorld, sent: RoomWorld, path: List<String>): RoomWorld {
        val after = next.state
        val polls = next.effects.filterIsInstance<TogetherEffect.Poll>()
        var job = sent.job
        for (effect in next.effects) {
            when (effect) {
                TogetherEffect.StopPoll -> job = null
                is TogetherEffect.Poll -> {
                    ensure(job == null, "at most one poll job", path)
                    job = effect.tag
                }
                else -> Unit
            }
        }
        val entered = next.effects.count { it is TogetherEffect.Enter }
        val result = sent.copy(job = job, entries = if (entered > 0) sent.entries + exec() else sent.entries)
        ensure(polls.size <= 1, "one poll per step", path)
        val on = ((after as? TogetherState.Rooms)?.poll as? RoomsPoll.On)?.tag
        ensure(on === job, "a poll job exists exactly while the room list is polling, under its tag", path)
        ensure(on == null || result.shown, "the room list polls only while shown", path)
        if (before is TogetherState.Rooms && after !is TogetherState.Rooms) ensure(TogetherEffect.StopPoll in next.effects, "every exit from the room list stops its poll", path)
        if (event is TogetherEvent.Fetched && event.tag !== world.job) ensure(next == TogetherStep(before), "a stale result is inert", path)
        if (before is TogetherState.Member && before.exec !== (after as? TogetherState.Member)?.exec) {
            if (before.origin.fromDrawer()) ensure(after !is TogetherState.Done || (before.leaving && event == TogetherEvent.Back), "a drawer membership ends the screen only by a second back", path)
            else ensure(after !is TogetherState.Rooms, "a title's membership never returns to the room list", path)
        }
        if (entered > 0) ensure(entered == 1 && before !is TogetherState.Entering && after is TogetherState.Entering, "one entry per Entering", path)
        next.effects.filterIsInstance<TogetherEffect.Send>().filter { it.event is GroupEvent.UserPick }.forEach { _ ->
            val origin = (before as? TogetherState.Member)?.origin
            ensure(event is TogetherEvent.Start && (origin is Origin.Created || (origin is Origin.Adopted && origin.held != null)), "a join never picks", path)
        }
        if (before is TogetherState.Done) ensure(after is TogetherState.Done, "done is terminal", path)
        if (before is TogetherState.Member && event == TogetherEvent.Back) {
            // R2-B1: a second Back while leaving gives up waiting and closes the screen, sending nothing.
            if (before.leaving) ensure(after == TogetherState.Done(GroupOutcome.Silent) && next.effects.isEmpty(), "R2-B1 a second back closes without a second leave", path)
            else ensure(after is TogetherState.Member && after.exec === before.exec, "R1-B1 back keeps the membership until it ends", path)
        }
        val started = ((event as? TogetherEvent.EntryDone)?.result as? EntryResult.Started)?.exec
        if (started != null) {
            val adopted = after is TogetherState.Member && after.exec === started && TogetherEffect.Watch(started) in next.effects
            val left = next.effects.any { it == TogetherEffect.Send(started, GroupEvent.UserLeave) }
            ensure(adopted != left, "a started group ends in a member screen or its leave", path)
        }
        return result
    }

    private fun ensure(ok: Boolean, invariant: String, path: List<String>) {
        if (!ok) throw AssertionError("invariant $invariant broken by ${path.joinToString(" > ")}")
    }

    // -- Named rows --------------------------------------------------------------------------

    @Test
    fun `the list polls again five seconds after each answer while shown`() {
        val (loading, tag) = polling(RoomList.Loading)
        assertRepolls(rooms(RoomList.Listed(listOf(roomA))), step(loading, TogetherEvent.Fetched(tag, RoomsFetch.Listed(listOf(roomA)))), ROOMS_POLL_MS)
    }

    @Test
    fun `a stale result changes nothing`() {
        val (listed, _) = polling(RoomList.Listed(listOf(roomA)))
        assertEquals(TogetherStep(listed), step(listed, TogetherEvent.Fetched(PollTag(), RoomsFetch.Listed(listOf(roomB)))))
        val hidden = rooms(RoomList.Listed(listOf(roomA)))
        assertEquals(TogetherStep(hidden), step(hidden, TogetherEvent.Fetched(PollTag(), RoomsFetch.NoAccess)))
    }

    @Test
    fun `hiding the list stops its poll and showing it polls at once`() {
        val (listed, _) = polling(RoomList.Listed(listOf(roomA)))
        val hidden = step(listed, TogetherEvent.Deactivate)
        assertEquals(TogetherStep(rooms(RoomList.Listed(listOf(roomA))), listOf(TogetherEffect.StopPoll)), hidden)
        assertRepolls(rooms(RoomList.Listed(listOf(roomA))), step(hidden.state, TogetherEvent.Activate(null, EPOCH)))
        assertEquals("a second show starts no second poll", TogetherStep(listed), step(listed, TogetherEvent.Activate(null, EPOCH)))
    }

    @Test
    fun `no access stops polling for good`() {
        val (loading, tag) = polling(RoomList.Loading)
        val denied = step(loading, TogetherEvent.Fetched(tag, RoomsFetch.NoAccess))
        assertEquals(TogetherStep(rooms(RoomList.NoAccess)), denied)
        assertEquals(TogetherStep(denied.state), step(denied.state, TogetherEvent.Activate(null, EPOCH)))
    }

    @Test
    fun `a failed refresh keeps the list and a failed first fetch says so, both polling again`() {
        val (listed, tag) = polling(RoomList.Listed(listOf(roomA)))
        assertRepolls(rooms(RoomList.Listed(listOf(roomA))), step(listed, TogetherEvent.Fetched(tag, RoomsFetch.Failed)), ROOMS_POLL_MS)
        val (loading, first) = polling(RoomList.Loading)
        assertRepolls(rooms(RoomList.Failed), step(loading, TogetherEvent.Fetched(first, RoomsFetch.Failed)), ROOMS_POLL_MS)
    }

    @Test
    fun `an account change mid-fetch leaves the screen`() {
        val (loading, tag) = polling(RoomList.Loading)
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(TogetherEffect.StopPoll)), step(loading, TogetherEvent.Fetched(tag, RoomsFetch.AccountChanged)))
    }

    @Test
    fun `pressing a room joins it by its listed name, stops the poll and keeps the list behind without its line`() {
        val (listed, _) = polling(RoomList.Listed(listOf(roomA)), RoomsLine.JoinFailed(JoinError.Gone))
        val behind = rooms(RoomList.Listed(listOf(roomA)))
        val entry = Entry.Join(joinA, behind)
        assertEquals(
            TogetherStep(TogetherState.Entering(entry, EPOCH), listOf(TogetherEffect.StopPoll, TogetherEffect.Enter(entry, EPOCH))),
            step(listed, TogetherEvent.PickRoom("room-a", "sp-alice's group")),
        )
    }

    @Test
    fun `the room list's focus lands afresh only between empty and not`() {
        val empty = focusReloadKey(Pane.Rooms, listedEmpty = true, primary = "none")
        val first = focusReloadKey(Pane.Rooms, listedEmpty = false, primary = roomKey("room-a"))
        val reordered = focusReloadKey(Pane.Rooms, listedEmpty = false, primary = roomKey("room-b"))
        assertNotEquals("the first room to appear is a fresh landing", empty, first)
        assertEquals("a refresh that reorders rooms is not", first, reordered)
        assertNotEquals("a lobby lands on its first button", focusReloadKey(Pane.Lobby, false, "start-0"), focusReloadKey(Pane.Lobby, false, "cancel"))
    }

    @Test
    fun `a blank listed room name is no name and any other is kept verbatim`() {
        assertNull(listedRoomName(roomB))
        assertNull(listedRoomName(roomA.copy(name = " \t ")))
        assertEquals(" room One ", listedRoomName(roomA.copy(name = " room One ")))
        val (listed, _) = polling(RoomList.Listed(listOf(roomB)))
        val entering = step(listed, TogetherEvent.PickRoom("room-b", listedRoomName(roomB))).state as TogetherState.Entering
        assertEquals(JoinTarget("room-b", null), (entering.entry as Entry.Join).target)
    }

    @Test
    fun `back while joining a room returns to the list as it was`() {
        val behind = rooms(RoomList.Listed(listOf(roomA)))
        val joining = TogetherState.Entering(Entry.Join(joinA, behind), EPOCH)
        assertRepolls(behind, step(joining, TogetherEvent.Back), 0L, TogetherEffect.CancelEntry)
    }

    @Test
    fun `a room that vanished returns to the list with the failure's line`() {
        val behind = rooms(RoomList.Listed(listOf(roomA)))
        val joined = TogetherState.Member(running, Origin.Joined(joinA, behind))
        val rows = mapOf(
            SyncPlayNotice.NoSuchGroup to JoinError.Gone,
            SyncPlayNotice.NoAccess to JoinError.NoAccess,
            SyncPlayNotice.StartFailed to JoinError.Failed,
        )
        rows.forEach { (notice, error) ->
            assertRepolls(behind.copy(line = RoomsLine.JoinFailed(error)), step(joined, TogetherEvent.ExecEnded(running, listOf(notice))))
        }
        val joining = TogetherState.Entering(Entry.Join(joinA, behind), EPOCH)
        assertEquals(
            "hidden, it lands without polling",
            TogetherStep(behind.copy(line = RoomsLine.JoinFailed(JoinError.Unavailable))),
            step(joining, TogetherEvent.EntryDone(EntryResult.Refused(pip = false), shown = false)),
        )
    }

    @Test
    fun `leaving a joined room returns to the list only once the group ended, and no room is entered meanwhile`() {
        val behind = rooms(RoomList.Listed(listOf(roomA)))
        val joined = TogetherState.Member(running, Origin.Joined(joinA, behind))
        val back = step(joined, TogetherEvent.Back)
        assertEquals(TogetherStep(joined.copy(leaving = true), listOf(TogetherEffect.Send(running, GroupEvent.UserLeave))), back)
        assertEquals("a re-pick while leaving enters nothing", TogetherStep(back.state), step(back.state, TogetherEvent.PickRoom("room-a", "sp-alice's group")))
        assertRepolls(behind, step(back.state, TogetherEvent.ExecEnded(running, emptyList())))
        val adopted = step(TogetherState.Member(running, Origin.Adopted(null)), TogetherEvent.Back)
        assertEquals(listOf(TogetherEffect.Send(running, GroupEvent.UserLeave)), adopted.effects)
        assertRepolls(rooms(RoomList.Loading), step(adopted.state, TogetherEvent.ExecEnded(running, emptyList())))
    }

    @Test
    fun `a title's watch together reaching the list starts hosting it`() {
        val (listed, _) = polling(RoomList.Listed(listOf(roomA)))
        val entry = Entry.Create(itemA)
        assertEquals(TogetherStep(TogetherState.Entering(entry, EPOCH), listOf(TogetherEffect.StopPoll, TogetherEffect.Enter(entry, EPOCH))), step(listed, TogetherEvent.Hold(itemA)))
    }

    @Test
    fun `the list's line clears when its time is up`() {
        val (listed, _) = polling(RoomList.Listed(listOf(roomA)), RoomsLine.JoinFailed(JoinError.Gone))
        assertEquals(TogetherStep(listed.copy(line = null)), step(listed, TogetherEvent.LineExpired))
    }

    // -- Pure functions --------------------------------------------------------------------------

    @Test
    fun `focus follows the room by id, else the row at its index, else the last row, else nothing`() {
        val rows = listOf(
            Triple("room-b", 1, listOf(roomA, roomC, roomB)) to "room-b",
            Triple("room-b", 1, listOf(roomA, roomC)) to "room-c",
            Triple("room-c", 2, listOf(roomA, roomB)) to "room-b",
            Triple("room-a", 0, emptyList<SyncPlayRoom>()) to null,
        )
        rows.forEach { (input, expected) -> assertEquals("$input", expected, roomFocusAfter(input.first, input.second, input.third)) }
    }

    @Test
    fun `a refresh dropping the focused room moves focus only while focus is in the pane on the top screen`() {
        val dropped = listOf(roomA, roomC)
        // (focus in pane, top) for a refresh that dropped room-b from index 1
        val rows = listOf(
            (true to true) to RoomRefocus.Move(roomKey("room-c")),
            (false to true) to RoomRefocus.Forget,
            (true to false) to RoomRefocus.Forget,
            (false to false) to RoomRefocus.Forget,
        )
        rows.forEach { (where, expected) -> assertEquals("$where", expected, roomRefocus("room-b", 1, dropped, where.first, where.second)) }
        for (inPane in listOf(true, false)) for (top in listOf(true, false)) {
            assertEquals("the last room gone leaves no target", RoomRefocus.Forget, roomRefocus("room-b", 1, emptyList(), inPane, top))
        }
        for (inPane in listOf(true, false)) for (top in listOf(true, false)) {
            assertEquals("a listed room keeps focus where it is", RoomRefocus.Keep, roomRefocus("room-a", 0, listOf(roomB, roomA), inPane, top))
        }
    }

    @Test
    fun `a row's top rule skips the first row, the focused row and the one under it`() {
        assertEquals(listOf(false, true, true, true), (0..3).map { roomRowRuled(it, null) })
        assertEquals(listOf(false, true, false, false), (0..3).map { roomRowRuled(it, 2) })
        assertEquals(listOf(false, false, true, true), (0..3).map { roomRowRuled(it, 0) })
    }

    @Test
    fun `a row names four members verbatim in server order and counts the rest`() {
        assertEquals(RoomNames("sp-alice, sp-Bob", 0), roomNames(listOf("sp-alice", "sp-Bob")))
        val eight = (1..8).map { "guest-0$it" }
        assertEquals(RoomNames("guest-01, guest-02, guest-03, guest-04", 4), roomNames(eight))
    }

    @Test
    fun `each join failure has its own line and an ended membership keeps its line`() {
        val strings = ResourceUiStrings.default
        assertEquals("That room ended before you joined.", roomsLineText(strings, RoomsLine.JoinFailed(JoinError.Gone))?.text)
        assertEquals("That room is playing something not in your libraries.", roomsLineText(strings, RoomsLine.JoinFailed(JoinError.NoAccess))?.text)
        assertEquals("Couldn't reach the server. Try again.", roomsLineText(strings, RoomsLine.JoinFailed(JoinError.Unavailable))?.text)
        assertEquals("Couldn't reach the server. Try again.", roomsLineText(strings, RoomsLine.JoinFailed(JoinError.Failed))?.text)
        assertEquals(LogLine("The host left", LogTone.Event), roomsLineText(strings, RoomsLine.Ended(GroupOutcome.HostLeft)))
    }

    @Test
    fun `the list stays up while a picked room is joined, until its lobby or title`() {
        val behind = rooms(RoomList.Listed(listOf(roomA)))
        val joining = TogetherState.Entering(Entry.Join(joinA, behind), EPOCH)
        assertEquals(RoomsPaneView(behind, "room-a"), roomsPane(joining, joinPending = false))
        val joined = TogetherState.Member(running, Origin.Joined(joinA, behind))
        assertEquals(RoomsPaneView(behind, "room-a"), roomsPane(joined, joinPending = true))
        assertNull("the lobby arrived", roomsPane(joined, joinPending = false))
        assertEquals("Back while joining or in the lobby shows the list at once", RoomsPaneView(behind, null), roomsPane(joined.copy(leaving = true), joinPending = true))
        assertEquals(RoomsPaneView(behind, null), roomsPane(joined.copy(leaving = true), joinPending = false))
        assertNull("a create keeps no list up", roomsPane(TogetherState.Entering(Entry.Create(itemA), EPOCH), joinPending = true))
        assertEquals(RoomsPaneView(behind, null), roomsPane(behind, joinPending = false))
    }

    @Test
    fun `a room's state reads in its tag and an unknown state shows none`() {
        assertEquals(listOf("IN LOBBY", "PLAYING", "PAUSED", "WAITING"), listOf(SyncPlayPhase.IDLE, SyncPlayPhase.PLAYING, SyncPlayPhase.PAUSED, SyncPlayPhase.WAITING).map { ResourceUiStrings.default.get(roomTag(it)!!) })
        assertNull(roomTag(null))
    }
}
