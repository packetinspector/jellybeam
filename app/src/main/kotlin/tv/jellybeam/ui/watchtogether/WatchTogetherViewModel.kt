package tv.jellybeam.ui.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.player.GroupEntry
import tv.jellybeam.player.GroupExec
import tv.jellybeam.player.GroupEvent
import tv.jellybeam.player.GroupOutcome
import tv.jellybeam.player.JoinTarget
import tv.jellybeam.player.NoticeSurface
import tv.jellybeam.player.Stage
import tv.jellybeam.player.SyncPlayExecutor
import tv.jellybeam.player.liveNotice
import tv.jellybeam.player.noticeSurface
import tv.jellybeam.player.outcome
import tv.jellybeam.player.playbackActivityTracker
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayRoom
import uniffi.jellybeam_core.SyncPlayRooms

/** docs/28 §1: the title a host holds locally until Start; [resumeTicks] 0 means it has no resume position. */
internal data class HeldItem(val itemId: String, val resumeTicks: Long)

/** What this screen asks the executor to enter. */
internal sealed interface Entry {
    data class Create(val held: HeldItem) : Entry
    /** [behind] is the room list the room was picked from, shown while the join runs and again when it ends. */
    data class Join(val target: JoinTarget, val behind: TogetherState.Rooms) : Entry
}

/** How the screen came to its membership; only a join's failures return to the room list. */
internal sealed interface Origin {
    data class Created(val held: HeldItem) : Origin
    /** [behind] is the room list the join was picked from, shown again when it ends. */
    data class Joined(val target: JoinTarget, val behind: TogetherState.Rooms) : Origin
    /** A running membership of this account found on arrival; [held] is the title this screen was opened with. */
    data class Adopted(val held: HeldItem?) : Origin
}

/** docs/28 §1: a membership reached from the drawer (a join, or one adopted without a title) returns to the room list. */
internal fun Origin.fromDrawer(): Boolean = this is Origin.Joined || (this is Origin.Adopted && held == null)

/** docs/28 §1: the room list as last fetched; a failed refresh keeps [Listed]. */
internal sealed interface RoomList {
    data object Loading : RoomList
    data class Listed(val rooms: List<SyncPlayRoom>) : RoomList
    data object NoAccess : RoomList
    data object Failed : RoomList
}

/** One fetch's answer; [AccountChanged] means the account it was for is gone. */
internal sealed interface RoomsFetch {
    data class Listed(val rooms: List<SyncPlayRoom>) : RoomsFetch
    data object NoAccess : RoomsFetch
    data object Failed : RoomsFetch
    data object AccountChanged : RoomsFetch
}

/** docs/28 §8: its identity is the tag, so every poll is new and a result outlives no other poll. */
internal class PollTag

/** Whether the room list is refreshing: [On] names the one poll whose result counts. */
internal sealed interface RoomsPoll {
    data class On(val tag: PollTag) : RoomsPoll
    data object Off : RoomsPoll
}

/** The room list's one line: why a picked room couldn't be joined, or how the last membership ended. */
internal sealed interface RoomsLine {
    data class JoinFailed(val error: JoinError) : RoomsLine
    data class Ended(val outcome: GroupOutcome) : RoomsLine
}

/** docs/28 §1: why a picked room couldn't be joined; [Gone] is a room that ended before the join landed. */
internal enum class JoinError { Gone, NoAccess, Unavailable, Failed }

/** A create or join's answer; [Refused.pip] is whether a PiP player was playing when it came back (docs/28 §1 refusals). */
internal sealed interface EntryResult {
    data class Started(val exec: SyncPlayExecutor) : EntryResult
    data class Refused(val pip: Boolean) : EntryResult
    data object Failed : EntryResult
}

internal sealed interface TogetherState {
    data class Idle(val held: HeldItem?) : TogetherState
    /** docs/28 §1: the drawer's room list, fetched only while [poll] is On. */
    data class Rooms(val epoch: ULong, val list: RoomList, val poll: RoomsPoll, val line: RoomsLine?) : TogetherState
    data class Entering(val entry: Entry, val epoch: ULong) : TogetherState
    /** docs/28 §1: a PiP player blocks hosting [held]; Stop ends it and hosts. */
    data class CreateRefused(val held: HeldItem, val epoch: ULong) : TogetherState
    /** [leaving]: Back already asked [exec] to leave; the screen moves on only at its end. */
    data class Member(val exec: SyncPlayExecutor, val origin: Origin, val memory: LobbyMemory = LobbyMemory(), val leaving: Boolean = false) : TogetherState
    /** Terminal: [line]'s text is shown once ([lineShown]) and the screen leaves when top. */
    data class Done(val line: GroupOutcome, val lineShown: Boolean = false) : TogetherState
}

/** A lobby notice and when this screen first saw it, in wall-clock ms. */
internal data class Stamped(val notice: SyncPlayNotice, val atMs: Long)

/**
 * What a member screen remembers of its executor between states: [live] the lobby notice last seen, [log] the
 * latest one stamped (it outlives the notice's turn), [lastPlayed] the item the group last loaded.
 */
internal data class LobbyMemory(val live: SyncPlayNotice? = null, val log: Stamped? = null, val lastPlayed: String? = null)

internal sealed interface TogetherEvent {
    /** The screen became top: the running membership and the account epoch, read now. */
    data class Activate(val running: SyncPlayExecutor?, val epoch: ULong) : TogetherEvent
    data object Deactivate : TogetherEvent
    /** The answer of the poll [tag]; any other tag is stale. */
    data class Fetched(val tag: PollTag, val fetch: RoomsFetch) : TogetherEvent
    /** docs/28 §1: [name] is the row's [listedRoomName]. */
    data class PickRoom(val groupId: String, val name: String?) : TogetherEvent
    /** [shown]: the screen was top when the answer came, so a room list it lands on may poll. */
    data class EntryDone(val result: EntryResult, val shown: Boolean = true) : TogetherEvent
    /** [exec] reached Gone holding [notices]; any other membership's end is stale. [shown] as [EntryDone]. */
    data class ExecEnded(val exec: SyncPlayExecutor, val notices: List<SyncPlayNotice>, val shown: Boolean = true) : TogetherEvent
    /** The host's Start, from [ticks]. */
    data class Start(val ticks: Long) : TogetherEvent
    /** docs/28 §1: a title chosen above the lobby replaces the held one. */
    data class Hold(val item: HeldItem) : TogetherEvent
    /** [exec]'s state seen at [atMs]: its live lobby notice and the item a launch or load names. */
    data class Observed(val exec: SyncPlayExecutor, val live: SyncPlayNotice?, val loadItem: String?, val atMs: Long) : TogetherEvent
    data object NoticeShown : TogetherEvent
    data object LineShown : TogetherEvent
    /** The room list's line had its time on screen. */
    data object LineExpired : TogetherEvent
    data object Back : TogetherEvent
    data object Stop : TogetherEvent
    data object Cancel : TogetherEvent
}

internal sealed interface TogetherEffect {
    /** [afterPip]: the PiP player is stopped and its close awaited first, so the create isn't refused again. */
    data class Enter(val entry: Entry, val epoch: ULong, val afterPip: Boolean = false) : TogetherEffect
    data object CancelEntry : TogetherEffect
    data class Watch(val exec: SyncPlayExecutor) : TogetherEffect
    data class Send(val exec: SyncPlayExecutor, val event: GroupEvent) : TogetherEffect
    /** docs/28 §8: fetch the room list for [epoch] after [delayMs], replacing any poll still waiting. */
    data class Poll(val tag: PollTag, val epoch: ULong, val delayMs: Long) : TogetherEffect
    data object StopPoll : TogetherEffect
}

/** docs/28 §8: the room list refreshes this often, and only while it shows. */
internal const val ROOMS_POLL_MS = 5_000L

/** docs/28 §1: how long the room list's line stays unless a Select clears it first. */
internal const val ROOMS_LINE_MS = 10_000L

internal data class TogetherStep(val state: TogetherState, val effects: List<TogetherEffect> = emptyList())

/** docs/28 §1: the watch-together screen's one transition function. */
internal fun reduce(state: TogetherState, event: TogetherEvent): TogetherStep {
    val started = ((event as? TogetherEvent.EntryDone)?.result as? EntryResult.Started)?.exec
    // docs/28 §3.4: a group that starts for a screen no longer entering has no owner, so it leaves.
    if (started != null && state !is TogetherState.Entering) return TogetherStep(state, listOf(TogetherEffect.Send(started, GroupEvent.UserLeave)))
    return when (state) {
        is TogetherState.Idle -> idle(state, event)
        is TogetherState.Rooms -> rooms(state, event)
        is TogetherState.Entering -> entering(state, event)
        is TogetherState.CreateRefused -> when (event) {
            TogetherEvent.Stop -> {
                val entry = Entry.Create(state.held)
                TogetherStep(TogetherState.Entering(entry, state.epoch), listOf(TogetherEffect.Enter(entry, state.epoch, afterPip = true)))
            }
            TogetherEvent.Cancel, TogetherEvent.Back -> TogetherStep(TogetherState.Done(GroupOutcome.Silent))
            else -> TogetherStep(state)
        }
        is TogetherState.Member -> member(state, event)
        is TogetherState.Done -> if (event == TogetherEvent.LineShown) TogetherStep(state.copy(lineShown = true)) else TogetherStep(state)
    }
}

private fun idle(state: TogetherState.Idle, event: TogetherEvent): TogetherStep = when (event) {
    is TogetherEvent.Activate -> {
        val running = event.running?.takeIf { it.accountEpoch == event.epoch }
        when {
            running != null -> TogetherStep(TogetherState.Member(running, Origin.Adopted(state.held)), listOf(TogetherEffect.Watch(running)))
            state.held != null -> enter(Entry.Create(state.held), event.epoch)
            else -> roomList(event.epoch, null)
        }
    }
    is TogetherEvent.Hold -> TogetherStep(state.copy(held = event.item))
    TogetherEvent.Back -> TogetherStep(TogetherState.Done(GroupOutcome.Silent))
    else -> TogetherStep(state)
}

private fun enter(entry: Entry, epoch: ULong, vararg also: TogetherEffect) =
    TogetherStep(TogetherState.Entering(entry, epoch), also.toList() + TogetherEffect.Enter(entry, epoch))

/**
 * docs/28 §1, §8: the room list again, [behind] as it was left or a fresh one, refreshing at once only while
 * the screen shows.
 */
private fun roomList(epoch: ULong, behind: TogetherState.Rooms?, line: RoomsLine? = null, shown: Boolean = true, vararg also: TogetherEffect): TogetherStep {
    val rooms = behind?.copy(poll = RoomsPoll.Off, line = line) ?: TogetherState.Rooms(epoch, RoomList.Loading, RoomsPoll.Off, line)
    return if (shown) repoll(rooms, 0L, *also) else TogetherStep(rooms, also.toList())
}

/** docs/28 §1, §8: the room list polls only while shown; every way out stops its poll. */
private fun rooms(state: TogetherState.Rooms, event: TogetherEvent): TogetherStep {
    val stop = TogetherEffect.StopPoll
    return when (event) {
        // docs/28 §1: no access is final for this screen, so it never asks again.
        is TogetherEvent.Activate -> if (state.poll == RoomsPoll.Off && state.list != RoomList.NoAccess) repoll(state, 0L) else TogetherStep(state)
        TogetherEvent.Deactivate -> if (state.poll is RoomsPoll.On) TogetherStep(state.copy(poll = RoomsPoll.Off), listOf(stop)) else TogetherStep(state)
        is TogetherEvent.Fetched -> if ((state.poll as? RoomsPoll.On)?.tag !== event.tag) TogetherStep(state) else fetched(state, event.fetch)
        // A Select clears the line, so the list behind a join keeps none.
        is TogetherEvent.PickRoom ->
            enter(Entry.Join(JoinTarget(event.groupId, event.name), state.copy(poll = RoomsPoll.Off, line = null)), state.epoch, stop)
        // docs/28 §1: a title's Watch together reaching this screen starts hosting it.
        is TogetherEvent.Hold -> enter(Entry.Create(event.item), state.epoch, stop)
        TogetherEvent.LineExpired -> TogetherStep(state.copy(line = null))
        TogetherEvent.Back -> TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(stop))
        else -> TogetherStep(state)
    }
}

private fun repoll(state: TogetherState.Rooms, delayMs: Long, vararg also: TogetherEffect): TogetherStep {
    val tag = PollTag()
    return TogetherStep(state.copy(poll = RoomsPoll.On(tag)), also.toList() + TogetherEffect.Poll(tag, state.epoch, delayMs))
}

/** The current poll's answer: a list or a failure polls again; no access stops; another account's leaves. */
private fun fetched(state: TogetherState.Rooms, fetch: RoomsFetch): TogetherStep = when (fetch) {
    is RoomsFetch.Listed -> repoll(state.copy(list = RoomList.Listed(fetch.rooms)), ROOMS_POLL_MS)
    RoomsFetch.Failed -> repoll(state.copy(list = state.list as? RoomList.Listed ?: RoomList.Failed), ROOMS_POLL_MS)
    RoomsFetch.NoAccess -> TogetherStep(state.copy(list = RoomList.NoAccess, poll = RoomsPoll.Off))
    RoomsFetch.AccountChanged -> TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(TogetherEffect.StopPoll))
}

private fun entering(state: TogetherState.Entering, event: TogetherEvent): TogetherStep {
    val entry = state.entry
    val shown = (event as? TogetherEvent.EntryDone)?.shown ?: true
    fun failed(error: JoinError): TogetherStep = when (entry) {
        is Entry.Create -> TogetherStep(TogetherState.Done(GroupOutcome.EntryFailed(SyncPlayNotice.StartFailed)))
        is Entry.Join -> roomList(state.epoch, entry.behind, RoomsLine.JoinFailed(error), shown)
    }
    val cancel = TogetherEffect.CancelEntry
    return when (event) {
        TogetherEvent.Back -> when (entry) {
            is Entry.Join -> roomList(state.epoch, entry.behind, null, true, cancel)
            is Entry.Create -> TogetherStep(TogetherState.Done(GroupOutcome.Silent), listOf(cancel))
        }
        is TogetherEvent.EntryDone -> when (val result = event.result) {
            is EntryResult.Started -> {
                val origin = when (entry) {
                    is Entry.Create -> Origin.Created(entry.held)
                    is Entry.Join -> Origin.Joined(entry.target, entry.behind)
                }
                TogetherStep(TogetherState.Member(result.exec, origin), listOf(TogetherEffect.Watch(result.exec)))
            }
            // docs/28 §1: the Stop dialog only when a PiP player is what blocks the create.
            is EntryResult.Refused ->
                if (entry is Entry.Create && result.pip) TogetherStep(TogetherState.CreateRefused(entry.held, state.epoch)) else failed(JoinError.Unavailable)
            EntryResult.Failed -> failed(JoinError.Failed)
        }
        else -> TogetherStep(state)
    }
}

private fun member(state: TogetherState.Member, event: TogetherEvent): TogetherStep {
    val exec = state.exec
    val held = when (val origin = state.origin) {
        is Origin.Created -> origin.held
        is Origin.Adopted -> origin.held
        is Origin.Joined -> null
    }
    return when (event) {
        // docs/28 §1: the held title reaches the group only at the host's Start; the executor checks the lobby.
        is TogetherEvent.Start ->
            if (held != null) TogetherStep(state, listOf(TogetherEffect.Send(exec, GroupEvent.UserPick(held.itemId, event.ticks)))) else TogetherStep(state)
        is TogetherEvent.Hold -> when (state.origin) {
            is Origin.Created -> TogetherStep(state.copy(origin = Origin.Created(event.item)))
            is Origin.Adopted -> TogetherStep(state.copy(origin = Origin.Adopted(event.item)))
            is Origin.Joined -> TogetherStep(state)
        }
        TogetherEvent.NoticeShown -> TogetherStep(state, listOf(TogetherEffect.Send(exec, GroupEvent.NoticesShown(NoticeSurface.Lobby))))
        // docs/28 §1: the screen stays until the group's end, so no entry can adopt the group being left.
        // A second Back gives up waiting on a leave the network holds; the executor still finishes it.
        TogetherEvent.Back -> if (state.leaving) TogetherStep(TogetherState.Done(GroupOutcome.Silent)) else TogetherStep(state.copy(leaving = true), listOf(TogetherEffect.Send(exec, GroupEvent.UserLeave)))
        is TogetherEvent.ExecEnded -> if (event.exec !== exec) TogetherStep(state) else ended(state, outcome(event.notices), event.shown)
        is TogetherEvent.Observed -> if (event.exec !== exec) TogetherStep(state) else TogetherStep(state.copy(memory = memoryAfter(state.memory, event)))
        else -> TogetherStep(state)
    }
}

/** What [TogetherEvent.Observed] reads off one executor state. */
internal fun observed(exec: SyncPlayExecutor, state: GroupExec, atMs: Long) = TogetherEvent.Observed(
    exec = exec,
    live = if (noticeSurface(state.stage) == NoticeSurface.Lobby) liveNotice(state.notices) else null,
    loadItem = when (val stage = state.stage) {
        is Stage.Launching -> stage.load.itemId
        is Stage.Loading -> stage.load.itemId
        Stage.Lobby, is Stage.Attached, Stage.Gone -> null
    },
    atMs = atMs,
)

/** A new load clears the lobby's log; a newly seen live notice is stamped and stays the log until the next. */
internal fun memoryAfter(memory: LobbyMemory, seen: TogetherEvent.Observed): LobbyMemory {
    val loaded = seen.loadItem?.takeIf { it != memory.lastPlayed }
    val base = if (loaded != null) memory.copy(lastPlayed = loaded, log = null) else memory
    val fresh = seen.live?.takeIf { it != memory.live }
    return base.copy(live = seen.live, log = fresh?.let { Stamped(it, seen.atMs) } ?: base.log)
}

/**
 * docs/28 §1: a join that never became a group returns to where it was picked; any other end of a drawer
 * membership returns to the room list with its line, and of a title's membership leaves the screen.
 */
private fun ended(state: TogetherState.Member, outcome: GroupOutcome, shown: Boolean): TogetherStep {
    val origin = state.origin
    val epoch = state.exec.accountEpoch
    val settled = if (outcome is GroupOutcome.EntryFailed) {
        val (error, line) = when (outcome.notice) {
            SyncPlayNotice.NoSuchGroup -> JoinError.Gone to SyncPlayNotice.StartFailed
            SyncPlayNotice.NoAccess -> JoinError.NoAccess to SyncPlayNotice.NoAccess
            SyncPlayNotice.JoinRefused, SyncPlayNotice.CreateRefused -> JoinError.Unavailable to SyncPlayNotice.StartFailed
            else -> JoinError.Failed to SyncPlayNotice.StartFailed
        }
        if (origin is Origin.Joined) return roomList(epoch, origin.behind, RoomsLine.JoinFailed(error), shown)
        GroupOutcome.EntryFailed(line)
    } else {
        outcome
    }
    if (!origin.fromDrawer()) return TogetherStep(TogetherState.Done(settled))
    return roomList(epoch, origin.behind(), RoomsLine.Ended(settled).takeUnless { settled == GroupOutcome.Silent }, shown)
}

private fun Origin.behind(): TogetherState.Rooms? = (this as? Origin.Joined)?.behind

/**
 * docs/28 §1: one watch-together screen's owner and the only writer of its state. The entry and poll jobs die
 * with the screen (the executor's cancelled entry leaves any group it got); in a group it holds only the executor.
 */
internal class WatchTogetherViewModel(
    held: HeldItem?,
    private val running: () -> SyncPlayExecutor?,
    private val epoch: () -> ULong,
    private val enter: suspend (Entry, ULong) -> GroupEntry,
    private val listRooms: suspend (ULong) -> SyncPlayRooms,
    private val inPip: () -> Boolean,
    /** Stops the PiP player and returns once it is destroyed. */
    private val stopPip: suspend () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val cardById: suspend (String) -> Card? = { null },
    private val seriesEpisodes: suspend (String) -> List<Card> = { emptyList() },
    private val accountName: suspend () -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle(held))
    val state: StateFlow<TogetherState> = _state.asStateFlow()
    private var entryJob: Job? = null
    private var pollJob: Job? = null
    /** docs/28 §2: the last Start's queue read; a newer Start cancels it, so the latest choice is the one sent. */
    private var pickJob: Job? = null

    /** Whether the screen is top; only [setActive] writes it, and async answers carry it into [reduce]. */
    private var shown = false

    /** ruling K1: nothing runs before the first activation; the room list polls only between these. */
    fun setActive(active: Boolean) {
        shown = active
        send(if (active) TogetherEvent.Activate(running(), epoch()) else TogetherEvent.Deactivate)
    }

    fun send(event: TogetherEvent) {
        var effects = emptyList<TogetherEffect>()
        _state.update { reduce(it, event).also { step -> effects = step.effects }.state }
        effects.forEach(::run)
    }

    private fun run(effect: TogetherEffect) {
        when (effect) {
            is TogetherEffect.Enter -> entryJob = viewModelScope.launch {
                if (effect.afterPip) stopPip()
                send(TogetherEvent.EntryDone(result(effect), shown))
            }
            TogetherEffect.CancelEntry -> entryJob?.cancel()
            is TogetherEffect.Watch -> viewModelScope.launch {
                // Every state before Gone reaches the lobby's memory; Gone ends the membership.
                val gone = effect.exec.state.first { exec ->
                    if (exec.stage != Stage.Gone) send(observed(effect.exec, exec, nowMs()))
                    exec.stage == Stage.Gone
                }
                send(TogetherEvent.ExecEnded(effect.exec, gone.notices, shown))
            }
            is TogetherEffect.Poll -> {
                pollJob?.cancel()
                pollJob = viewModelScope.launch {
                    delay(effect.delayMs)
                    send(TogetherEvent.Fetched(effect.tag, fetch(effect.epoch)))
                }
            }
            TogetherEffect.StopPoll -> pollJob?.cancel()
            is TogetherEffect.Send -> when (val pick = effect.event) {
                is GroupEvent.UserPick -> {
                    pickJob?.cancel()
                    pickJob = viewModelScope.launch { effect.exec.send(pick.copy(queue = pickQueue(pick.itemId))) }
                }
                else -> effect.exec.send(pick)
            }
        }
    }

    /** docs/28 §1: the title card's item from the local mirror, never the network; `null` when it isn't there. */
    suspend fun card(itemId: String): Card? = runCatchingCancellable { cardById(itemId) }.getOrNull()

    /** The signed-in account's user name, which marks our own row. */
    suspend fun ownName(): String? = runCatchingCancellable { accountName() }.getOrNull()

    /** docs/28 §2: the queue a pick sends, read from the local mirror; any failure sends the title alone. */
    private suspend fun pickQueue(itemId: String): List<String> {
        val card = card(itemId)
        val episodes = card?.seriesId?.let { runCatchingCancellable { seriesEpisodes(it) }.getOrNull() }.orEmpty()
        return groupQueue(itemId, card, episodes)
    }

    private suspend fun fetch(epoch: ULong): RoomsFetch = try {
        when (val rooms = listRooms(epoch)) {
            is SyncPlayRooms.Listed -> RoomsFetch.Listed(rooms.rooms)
            SyncPlayRooms.NoAccess -> RoomsFetch.NoAccess
        }
    } catch (e: CoreException.AccountChanged) {
        RoomsFetch.AccountChanged
    } catch (e: CoreException) {
        // ffi() already recorded it; the list keeps what it showed and polls again.
        RoomsFetch.Failed
    }

    private suspend fun result(effect: TogetherEffect.Enter): EntryResult = try {
        when (val entry = enter(effect.entry, effect.epoch)) {
            is GroupEntry.Started -> EntryResult.Started(entry.executor)
            GroupEntry.Refused -> EntryResult.Refused(pip = inPip())
        }
    } catch (e: CoreException) {
        // ffi() already recorded it; the screen only says the entry failed.
        EntryResult.Failed
    }

    companion object {
        /** The app's seams: the process slot, the account epoch, the executor's entries and the PiP player. */
        fun factory(held: HeldItem?): ViewModelProvider.Factory {
            val build = SyncPlayExecutor.forApp()
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = WatchTogetherViewModel(
                    held = held,
                    running = { SyncPlayExecutor.current.value },
                    epoch = { AppGraph.gateway.accountEpochs.value.epoch },
                    enter = { entry, epoch ->
                        when (entry) {
                            is Entry.Create -> SyncPlayExecutor.create(epoch, AppGraph.gateway, build)
                            is Entry.Join -> SyncPlayExecutor.join(epoch, entry.target, build)
                        }
                    },
                    listRooms = { AppGraph.gateway.syncPlayListRooms(it) },
                    inPip = { playbackActivityTracker.inPip },
                    stopPip = playbackActivityTracker::stopForGroup,
                    cardById = { AppGraph.gateway.cardById(it) },
                    seriesEpisodes = { AppGraph.gateway.seriesEpisodes(it) },
                    accountName = { activeUserName(AppGraph.gateway) },
                ) as T
            }
        }
    }
}

/**
 * docs/28 §2: jellyfin-web expands a lone episode into it and every later real episode of its series, then pairs
 * its list with ours by position, so an episode pick sends that same list; anything else goes alone.
 */
internal fun groupQueue(itemId: String, card: Card?, seriesEpisodes: List<Card>): List<String> {
    if (card?.itemType != "Episode" || card.seriesId == null) return listOf(itemId)
    return seriesEpisodes.filterNot { it.isVirtual }.dropWhile { it.id != itemId }.map { it.id }.ifEmpty { listOf(itemId) }
}

/** docs/28 §1: the signed-in account's user name, which marks our own row and, when we host, the host's. */
internal suspend fun activeUserName(gateway: CoreGateway): String? =
    gateway.activeAccountIndex()?.let { gateway.listAccounts().getOrNull(it.toInt())?.userName }
