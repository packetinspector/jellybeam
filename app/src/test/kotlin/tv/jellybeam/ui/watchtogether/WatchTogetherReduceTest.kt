package tv.jellybeam.ui.watchtogether

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.player.GroupEvent
import tv.jellybeam.player.GroupOutcome
import tv.jellybeam.player.JoinTarget
import tv.jellybeam.player.NoticeSurface
import tv.jellybeam.player.SyncPlayExecutor
import uniffi.jellybeam_core.SyncPlayEndCause
import uniffi.jellybeam_core.SyncPlayNotice

private const val EPOCH = 1uL
private const val RESUME = 42_000_000_000L
private val itemA = HeldItem("item-a", RESUME)
private val itemB = HeldItem("item-b", 0L)
private val roomOne = JoinTarget("room-1", "member-a's group")

/** The room list a join was picked from; a fresh one, so an end lands on the same list a drawer arrival shows. */
private val roomsBehind = TogetherState.Rooms(EPOCH, RoomList.Loading, RoomsPoll.Off, null)

@OptIn(ExperimentalCoroutinesApi::class)
private val testScope = TestScope()

/** Unstarted executors are identities only; every defaulted seam is passed so nothing touches AppGraph. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun exec(epoch: ULong = EPOCH) =
    SyncPlayExecutor(FakeCoreGateway(), epoch, {}, testScope, StandardTestDispatcher(testScope.testScheduler)) { 0L }

private val running = exec()
private val otherAccount = exec(epoch = 2uL)
private val stale = exec()

/** The entries a sweep path made and how many were answered, so each answer names its own entry's executor. */
private data class Env(val entries: List<SyncPlayExecutor> = emptyList(), val answered: Int = 0) {
    fun current(): SyncPlayExecutor? = entries.getOrNull(answered - 1)
}

/** One letter: the event it sends in [Env] and the world after, or `null` where the world can't send it. */
private class Letter(val name: String, val event: (Env) -> Pair<TogetherEvent, Env>?)

private fun plain(name: String, event: TogetherEvent) = Letter(name) { env -> event to env }

/** An entry's answer exists only while an entry is in flight. */
private fun answer(name: String, result: (SyncPlayExecutor) -> EntryResult) = Letter(name) { env ->
    env.entries.getOrNull(env.answered)?.let { TogetherEvent.EntryDone(result(it)) to env.copy(answered = env.answered + 1) }
}

private fun ended(name: String, notices: List<SyncPlayNotice>) =
    Letter(name) { env -> env.current()?.let { TogetherEvent.ExecEnded(it, notices) to env } }

/** A sighting of the current entry's executor. */
private fun seen(name: String, live: SyncPlayNotice?, loadItem: String?, atMs: Long) =
    Letter(name) { env -> env.current()?.let { TogetherEvent.Observed(it, live, loadItem, atMs) to env } }

private val alphabet = listOf(
    plain("activate", TogetherEvent.Activate(null, EPOCH)),
    plain("activate-running", TogetherEvent.Activate(running, EPOCH)),
    plain("activate-other-account", TogetherEvent.Activate(otherAccount, EPOCH)),
    answer("started") { EntryResult.Started(it) },
    answer("refused-pip") { EntryResult.Refused(pip = true) },
    answer("refused") { EntryResult.Refused(pip = false) },
    answer("failed") { EntryResult.Failed },
    ended("ended-start-failed", listOf(SyncPlayNotice.StartFailed)),
    ended("ended-gone", listOf(SyncPlayNotice.Joined, SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE))),
    ended("ended-silent", emptyList()),
    ended("ended-host-left", listOf(SyncPlayNotice.HostLeft)),
    plain("running-ended", TogetherEvent.ExecEnded(running, listOf(SyncPlayNotice.HostLeft))),
    plain("stale-ended", TogetherEvent.ExecEnded(stale, listOf(SyncPlayNotice.NoAccess))),
    plain("start", TogetherEvent.Start(0L)),
    plain("start-resume", TogetherEvent.Start(RESUME)),
    plain("hold", TogetherEvent.Hold(itemB)),
    seen("seen-notice", SyncPlayNotice.UserJoined("member-b"), null, 1_000L),
    seen("seen-load", null, "item-a", 2_000L),
    plain("running-seen", TogetherEvent.Observed(running, SyncPlayNotice.Joined, null, 3_000L)),
    plain("stale-seen", TogetherEvent.Observed(stale, SyncPlayNotice.UserLeft("member-b"), "item-b", 4_000L)),
    plain("notice-shown", TogetherEvent.NoticeShown),
    plain("line-shown", TogetherEvent.LineShown),
    plain("back", TogetherEvent.Back),
    plain("stop", TogetherEvent.Stop),
    plain("cancel", TogetherEvent.Cancel),
)

private fun TogetherEffect.leaveOf(): SyncPlayExecutor? = (this as? TogetherEffect.Send)?.takeIf { it.event == GroupEvent.UserLeave }?.exec

class WatchTogetherReduceTest {

    private fun step(state: TogetherState, event: TogetherEvent) = reduce(state, event)

    private fun member(origin: Origin, exec: SyncPlayExecutor = running) = TogetherState.Member(exec, origin)

    private fun rooms(list: RoomList, poll: RoomsPoll, line: RoomsLine? = null) = TogetherState.Rooms(EPOCH, list, poll, line)

    /** The tag of the poll a step started. */
    private fun tagOf(step: TogetherStep): PollTag = (step.effects.filterIsInstance<TogetherEffect.Poll>().single()).tag

    private fun endHidden(origin: Origin, vararg notices: SyncPlayNotice) = step(member(origin), TogetherEvent.ExecEnded(running, notices.toList(), shown = false)).state

    // -- docs/28 §1 sweep: every sequence up to length 4, both seeds ---------------

    @Test
    fun `every sequence up to four events keeps the screen invariants for a create and a join screen`() {
        var paths = 0
        for (seed in listOf(itemA, null)) {
            fun walk(state: TogetherState, env: Env, path: List<String>, enters: Int, leaves: Map<SyncPlayExecutor, Int>, watches: Map<SyncPlayExecutor, Int>) {
                if (path.size == 4) return
                for (letter in alphabet) {
                    val (event, world) = letter.event(env) ?: continue
                    val now = path + letter.name
                    val next = step(state, event)
                    paths++
                    check(seed, state, event, next, now)
                    // docs/28 §1: a PiP refusal's Stop retries the same create once, so it isn't a second entry of its own.
                    val entered = next.effects.count { it is TogetherEffect.Enter && !it.afterPip }
                    val newLeaves = leaves.toMutableMap().apply { next.effects.mapNotNull { it.leaveOf() }.forEach { merge(it, 1, Int::plus) } }
                    val newWatches = watches.toMutableMap().apply { next.effects.filterIsInstance<TogetherEffect.Watch>().forEach { merge(it.exec, 1, Int::plus) } }
                    ensure(seed == null || enters + entered <= 1, "6 a create screen enters once", now)
                    ensure(newLeaves.values.all { it <= 1 }, "6 at most one leave per membership", now)
                    ensure(newWatches.values.all { it <= 1 }, "6 one watch per membership", now)
                    val nextWorld = if (entered > 0) world.copy(entries = world.entries + exec()) else world
                    walk(next.state, nextWorld, now, enters + entered, newLeaves, newWatches)
                }
            }
            walk(TogetherState.Idle(seed), Env(), emptyList(), 0, emptyMap(), emptyMap())
        }
        assertTrue("sweep ran", paths > 100_000)
    }

    private fun check(seed: HeldItem?, before: TogetherState, event: TogetherEvent, next: TogetherStep, path: List<String>) {
        val after = next.state
        // 3: the held item reaches the group only through the host's Start, never from a join screen.
        next.effects.filterIsInstance<TogetherEffect.Send>().filter { it.event is GroupEvent.UserPick }.forEach { send ->
            val pick = send.event as GroupEvent.UserPick
            val held = when (val origin = (before as? TogetherState.Member)?.origin) {
                is Origin.Created -> origin.held
                is Origin.Adopted -> origin.held
                is Origin.Joined, null -> null
            }
            ensure(event is TogetherEvent.Start && held != null, "3 pick only at the host's Start", path)
            ensure(pick.itemId == held!!.itemId && pick.startTicks == (event as TogetherEvent.Start).ticks, "3 pick is the held item", path)
            ensure(send.exec === (before as TogetherState.Member).exec, "3 pick goes to the screen's group", path)
        }
        // 4: every Started ends in a member screen watching it, or in its Leave.
        val started = ((event as? TogetherEvent.EntryDone)?.result as? EntryResult.Started)?.exec
        if (started != null) {
            val adopted = after is TogetherState.Member && after.exec === started && TogetherEffect.Watch(started) in next.effects
            val left = next.effects.any { it.leaveOf() === started }
            ensure(adopted != left, "4 started ends in member or leave", path)
        }
        // 5: an end or sighting of another membership is inert; Done is terminal.
        val named = (event as? TogetherEvent.ExecEnded)?.exec ?: (event as? TogetherEvent.Observed)?.exec
        if (named != null && (before as? TogetherState.Member)?.exec !== named) {
            ensure(next == TogetherStep(before), "5 stale end or sighting inert", path)
        }
        // 7: a sighting only updates the member screen's memory, with no effect.
        if (event is TogetherEvent.Observed && before is TogetherState.Member && before.exec === event.exec) {
            ensure(next.effects.isEmpty() && after is TogetherState.Member && after.exec === before.exec && after.origin == before.origin, "7 sighting is memory only", path)
            val memory = (after as TogetherState.Member).memory
            ensure(memory.live == event.live, "7 the last sighting's notice is remembered", path)
            ensure(event.loadItem == null || (memory.lastPlayed == event.loadItem && memory.log == null), "7 a load clears the log", path)
        }
        if (before is TogetherState.Done) {
            ensure(after is TogetherState.Done && after.line == before.line, "5 done terminal", path)
            ensure(next.effects.all { it.leaveOf() != null }, "5 done only leaves a late group", path)
        }
        // 6: a retry after the PiP player stops comes only from that refusal's Stop.
        if (next.effects.any { it is TogetherEffect.Enter && it.afterPip }) {
            ensure(before is TogetherState.CreateRefused && event == TogetherEvent.Stop, "6 a PiP retry only from the refusal's Stop", path)
        }
        // 6: an entry starts only from the room list or the first activation, never over one in flight.
        if (next.effects.any { it is TogetherEffect.Enter }) {
            ensure(before !is TogetherState.Entering && after is TogetherState.Entering, "6 one entry in flight", path)
        }
        // R1-B1: Back in a group only asks to leave; the screen moves on at the group's end.
        if (before is TogetherState.Member && event == TogetherEvent.Back) {
            // R2-B1: a second Back while leaving gives up waiting and closes the screen, sending nothing.
            if (before.leaving) ensure(after == TogetherState.Done(GroupOutcome.Silent) && next.effects.isEmpty(), "R2-B1 a second back closes without a second leave", path)
            else ensure(after is TogetherState.Member && after.exec === before.exec, "R1-B1 back keeps the membership until it ends", path)
        }
    }

    private fun ensure(ok: Boolean, invariant: String, path: List<String>) {
        if (!ok) throw AssertionError("invariant $invariant broken by ${path.joinToString(" > ")}")
    }

    // -- Named rows ---------------------------------------------------------------------

    @Test
    fun `arriving with a running membership of this account adopts it and watches it`() {
        val next = step(TogetherState.Idle(itemA), TogetherEvent.Activate(running, EPOCH))
        assertEquals(TogetherStep(member(Origin.Adopted(itemA)), listOf(TogetherEffect.Watch(running))), next)
    }

    @Test
    fun `arriving with a title and no membership creates one`() {
        val next = step(TogetherState.Idle(itemA), TogetherEvent.Activate(null, EPOCH))
        assertEquals(TogetherStep(TogetherState.Entering(Entry.Create(itemA), EPOCH), listOf(TogetherEffect.Enter(Entry.Create(itemA), EPOCH))), next)
    }

    @Test
    fun `arriving from the drawer lists the rooms and polls at once, even over another account's membership`() {
        for (other in listOf(null, otherAccount)) {
            val next = step(TogetherState.Idle(null), TogetherEvent.Activate(other, EPOCH))
            val tag = tagOf(next)
            assertEquals(TogetherStep(rooms(RoomList.Loading, RoomsPoll.On(tag)), listOf(TogetherEffect.Poll(tag, EPOCH, 0L))), next)
        }
    }

    @Test
    fun `a later activation changes nothing`() {
        val lobby = member(Origin.Created(itemA))
        assertEquals(TogetherStep(lobby), step(lobby, TogetherEvent.Activate(null, EPOCH)))
    }

    @Test
    fun `back during an entry leaves the screen and cancels the entry`() {
        assertEquals(
            TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(TogetherEffect.CancelEntry)),
            step(TogetherState.Entering(Entry.Create(itemA), EPOCH), TogetherEvent.Back),
        )
    }

    @Test
    fun `a membership that starts after the screen left is left`() {
        val late = exec()
        assertEquals(
            TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(TogetherEffect.Send(late, GroupEvent.UserLeave))),
            step(TogetherState.Done(GroupOutcome.Silent), TogetherEvent.EntryDone(EntryResult.Started(late))),
        )
    }

    @Test
    fun `a started entry becomes the screen's membership`() {
        val group = exec()
        assertEquals(
            TogetherStep(member(Origin.Created(itemA), group), listOf(TogetherEffect.Watch(group))),
            step(TogetherState.Entering(Entry.Create(itemA), EPOCH), TogetherEvent.EntryDone(EntryResult.Started(group))),
        )
        assertEquals(
            TogetherStep(member(Origin.Joined(roomOne, roomsBehind), group), listOf(TogetherEffect.Watch(group))),
            step(TogetherState.Entering(Entry.Join(roomOne, roomsBehind), EPOCH), TogetherEvent.EntryDone(EntryResult.Started(group))),
        )
    }

    @Test
    fun `a refused create asks to stop only while a PiP player plays`() {
        val creating = TogetherState.Entering(Entry.Create(itemA), EPOCH)
        assertEquals(TogetherStep(TogetherState.CreateRefused(itemA, EPOCH)), step(creating, TogetherEvent.EntryDone(EntryResult.Refused(pip = true))))
        assertEquals(
            TogetherStep(TogetherState.Done(GroupOutcome.EntryFailed(SyncPlayNotice.StartFailed))),
            step(creating, TogetherEvent.EntryDone(EntryResult.Refused(pip = false))),
        )
    }

    @Test
    fun `a failed create ends the screen with its reason`() {
        val creating = TogetherState.Entering(Entry.Create(itemA), EPOCH)
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.EntryFailed(SyncPlayNotice.StartFailed))), step(creating, TogetherEvent.EntryDone(EntryResult.Failed)))
    }

    @Test
    fun `a failed join returns to the list behind it with its line`() {
        val behind = rooms(RoomList.Listed(emptyList()), RoomsPoll.Off)
        val joining = TogetherState.Entering(Entry.Join(roomOne, behind), EPOCH)
        val rows = mapOf(
            EntryResult.Refused(pip = true) to JoinError.Unavailable,
            EntryResult.Refused(pip = false) to JoinError.Unavailable,
            EntryResult.Failed to JoinError.Failed,
        )
        rows.forEach { (result, error) ->
            val next = step(joining, TogetherEvent.EntryDone(result))
            val tag = tagOf(next)
            assertEquals(TogetherStep(behind.copy(poll = RoomsPoll.On(tag), line = RoomsLine.JoinFailed(error)), listOf(TogetherEffect.Poll(tag, EPOCH, 0L))), next)
        }
    }

    @Test
    fun `stop on the refusal hosts the held title once the PiP player is stopped, cancel only leaves`() {
        val refused = TogetherState.CreateRefused(itemA, EPOCH)
        val entry = Entry.Create(itemA)
        assertEquals(
            TogetherStep(TogetherState.Entering(entry, EPOCH), listOf(TogetherEffect.Enter(entry, EPOCH, afterPip = true))),
            step(refused, TogetherEvent.Stop),
        )
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.Silent)), step(refused, TogetherEvent.Cancel))
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.Silent)), step(refused, TogetherEvent.Back))
    }

    @Test
    fun `the host's Start sends the held title from the chosen position, a guest's sends nothing`() {
        val lobby = member(Origin.Created(itemA))
        assertEquals(
            TogetherStep(lobby, listOf(TogetherEffect.Send(running, GroupEvent.UserPick("item-a", 0L)))),
            step(lobby, TogetherEvent.Start(0L)),
        )
        assertEquals(
            TogetherStep(lobby, listOf(TogetherEffect.Send(running, GroupEvent.UserPick("item-a", RESUME)))),
            step(lobby, TogetherEvent.Start(RESUME)),
        )
        val guest = member(Origin.Joined(roomOne, roomsBehind))
        assertEquals(TogetherStep(guest), step(guest, TogetherEvent.Start(0L)))
        val bare = member(Origin.Adopted(null))
        assertEquals(TogetherStep(bare), step(bare, TogetherEvent.Start(0L)))
    }

    @Test
    fun `a title chosen above the lobby replaces the held one without a second entry`() {
        val lobby = member(Origin.Created(itemA))
        val held = step(lobby, TogetherEvent.Hold(itemB))
        assertEquals(TogetherStep(member(Origin.Created(itemB))), held)
        assertEquals(listOf(TogetherEffect.Send(running, GroupEvent.UserPick("item-b", 0L))), step(held.state, TogetherEvent.Start(0L)).effects)
        assertEquals(TogetherStep(member(Origin.Adopted(itemB))), step(member(Origin.Adopted(null)), TogetherEvent.Hold(itemB)))
        val guest = member(Origin.Joined(roomOne, roomsBehind))
        assertEquals(TogetherStep(guest), step(guest, TogetherEvent.Hold(itemB)))
        assertEquals(TogetherStep(TogetherState.Idle(itemB)), step(TogetherState.Idle(itemA), TogetherEvent.Hold(itemB)))
    }

    @Test
    fun `back in a title's lobby leaves the group and the screen goes only once the group ended`() {
        val lobby = member(Origin.Created(itemA))
        val back = step(lobby, TogetherEvent.Back)
        assertEquals(TogetherStep(lobby.copy(leaving = true), listOf(TogetherEffect.Send(running, GroupEvent.UserLeave))), back)
        assertEquals("a second back gives up waiting and sends no second leave", TogetherStep(TogetherState.Done(GroupOutcome.Silent)), step(back.state, TogetherEvent.Back))
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.Silent)), step(back.state, TogetherEvent.ExecEnded(running, emptyList())))
    }

    @Test
    fun `a shown lobby notice is cleared from the lobby's surface`() {
        val lobby = member(Origin.Joined(roomOne, roomsBehind))
        assertEquals(
            TogetherStep(lobby, listOf(TogetherEffect.Send(running, GroupEvent.NoticesShown(NoticeSurface.Lobby)))),
            step(lobby, TogetherEvent.NoticeShown),
        )
    }

    @Test
    fun `a lobby notice is stamped when first seen and stays the log after its turn`() {
        val joined = SyncPlayNotice.UserJoined("member-b")
        val first = step(member(Origin.Created(itemA)), TogetherEvent.Observed(running, joined, null, 1_000L))
        assertEquals(TogetherStep(TogetherState.Member(running, Origin.Created(itemA), LobbyMemory(joined, Stamped(joined, 1_000L)))), first)
        val again = step(first.state, TogetherEvent.Observed(running, joined, null, 2_000L)).state as TogetherState.Member
        assertEquals(Stamped(joined, 1_000L), again.memory.log)
        val cleared = step(again, TogetherEvent.Observed(running, null, null, 5_000L)).state as TogetherState.Member
        assertEquals(LobbyMemory(null, Stamped(joined, 1_000L)), cleared.memory)
        val left = SyncPlayNotice.UserLeft("member-b")
        assertEquals(Stamped(left, 6_000L), (step(cleared, TogetherEvent.Observed(running, left, null, 6_000L)).state as TogetherState.Member).memory.log)
    }

    @Test
    fun `a load clears the lobby log and names the title last played`() {
        val logged = TogetherState.Member(running, Origin.Joined(roomOne, roomsBehind), LobbyMemory(null, Stamped(SyncPlayNotice.Joined, 1_000L)))
        val loading = step(logged, TogetherEvent.Observed(running, null, "item-a", 2_000L))
        assertEquals(TogetherStep(TogetherState.Member(running, Origin.Joined(roomOne, roomsBehind), LobbyMemory(lastPlayed = "item-a"))), loading)
        assertEquals(loading, step(loading.state, TogetherEvent.Observed(running, null, "item-a", 3_000L)))
    }

    @Test
    fun `another membership's sighting and a sighting off the lobby change nothing`() {
        val lobby = member(Origin.Created(itemA))
        assertEquals(TogetherStep(lobby), step(lobby, TogetherEvent.Observed(stale, SyncPlayNotice.Joined, "item-b", 1_000L)))
        val list = rooms(RoomList.Listed(emptyList()), RoomsPoll.Off)
        assertEquals(TogetherStep(list), step(list, TogetherEvent.Observed(running, SyncPlayNotice.Joined, null, 1_000L)))
    }

    @Test
    fun `a membership's end decides where the screen goes`() {
        fun end(origin: Origin, vararg notices: SyncPlayNotice) = step(member(origin), TogetherEvent.ExecEnded(running, notices.toList())).state
        val gone = SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE)
        // docs/28 §1: a join that never became a group returns to the room list with why, a vanished room as gone.
        mapOf(
            SyncPlayNotice.NoSuchGroup to JoinError.Gone,
            SyncPlayNotice.NoAccess to JoinError.NoAccess,
            SyncPlayNotice.JoinRefused to JoinError.Unavailable,
            SyncPlayNotice.StartFailed to JoinError.Failed,
        ).forEach { (notice, error) -> assertEquals(rooms(RoomList.Loading, RoomsPoll.Off, RoomsLine.JoinFailed(error)), endHidden(Origin.Joined(roomOne, roomsBehind), notice)) }
        assertEquals(TogetherState.Done(GroupOutcome.EntryFailed(SyncPlayNotice.StartFailed)), end(Origin.Created(itemA), SyncPlayNotice.StartFailed))
        assertEquals(TogetherState.Done(GroupOutcome.EntryFailed(SyncPlayNotice.NoAccess)), end(Origin.Created(itemA), SyncPlayNotice.NoAccess))
        assertEquals(TogetherState.Done(GroupOutcome.Ended(gone)), end(Origin.Adopted(itemA), SyncPlayNotice.Joined, gone))
        assertEquals(TogetherState.Done(GroupOutcome.HostLeft), end(Origin.Created(itemA), SyncPlayNotice.HostLeft))
        assertEquals(TogetherState.Done(GroupOutcome.Silent), end(Origin.Created(itemA), SyncPlayNotice.UserLeft("member-b")))
        // docs/28 §1: a drawer membership's end returns to the room list with its line.
        assertEquals(rooms(RoomList.Loading, RoomsPoll.Off, RoomsLine.Ended(GroupOutcome.EntryFailed(SyncPlayNotice.StartFailed))), endHidden(Origin.Adopted(null), SyncPlayNotice.NoSuchGroup))
        assertEquals(rooms(RoomList.Loading, RoomsPoll.Off, RoomsLine.Ended(GroupOutcome.Ended(gone))), endHidden(Origin.Joined(roomOne, roomsBehind), SyncPlayNotice.Joined, gone))
    }

    @Test
    fun `a guest told the host left reads the host-left line`() {
        val next = step(member(Origin.Adopted(itemA)), TogetherEvent.ExecEnded(running, listOf(SyncPlayNotice.Joined, SyncPlayNotice.HostLeft)))
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.HostLeft)), next)
        val drawer = step(member(Origin.Joined(roomOne, roomsBehind)), TogetherEvent.ExecEnded(running, listOf(SyncPlayNotice.HostLeft), shown = false))
        assertEquals(TogetherStep(rooms(RoomList.Loading, RoomsPoll.Off, RoomsLine.Ended(GroupOutcome.HostLeft))), drawer)
        assertEquals("The host left", roomsLineText(ResourceUiStrings.default, RoomsLine.Ended(GroupOutcome.HostLeft))?.text)
    }

    @Test
    fun `the terminal line is shown once and nothing leaves done`() {
        val done = TogetherState.Done(GroupOutcome.Ended(SyncPlayNotice.HostLeft))
        val shown = step(done, TogetherEvent.LineShown)
        assertEquals(TogetherStep(TogetherState.Done(GroupOutcome.Ended(SyncPlayNotice.HostLeft), lineShown = true)), shown)
        assertEquals(shown, step(shown.state, TogetherEvent.LineShown))
        assertEquals(TogetherStep(done), step(done, TogetherEvent.Activate(null, EPOCH)))
    }
}

