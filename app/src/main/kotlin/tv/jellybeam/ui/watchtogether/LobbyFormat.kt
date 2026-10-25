package tv.jellybeam.ui.watchtogether

import androidx.annotation.StringRes
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.GroupExec
import tv.jellybeam.player.hostName
import tv.jellybeam.player.inTransit
import tv.jellybeam.player.NoticeSurface
import tv.jellybeam.player.PlaybackTimeFormat
import tv.jellybeam.player.isTerminal
import tv.jellybeam.player.liveNotice
import tv.jellybeam.player.noticeSurface
import tv.jellybeam.player.noticeText
import tv.jellybeam.player.outcomeText
import tv.jellybeam.player.startAllowed
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayRoom
import uniffi.jellybeam_core.SyncPlayStage

private const val TICKS_PER_MS = 10_000L

internal enum class LobbyPhase { Starting, Members, Leaving }

/**
 * What the lobby shows for one executor state. [startTicks] are the host's Start choices (none for a guest or
 * without a held title); [startActive] false keeps them inert. [noticeLine] is the live notice on screen and
 * [pendingNotices] how many wait to be cleared from the lobby. [hostName] is the host's user name when known,
 * [self] our own row's name when known, [transit] a late joiner waiting for the playing title, [log] the
 * latest stamped lobby notice, [cardItem] the title the card shows and [finished] whether the host is back
 * from playing the held title.
 */
internal data class LobbyView(
    val phase: LobbyPhase,
    val host: Boolean,
    val name: String?,
    val members: List<String>,
    val connected: Boolean,
    val startTicks: List<Long>,
    val startActive: Boolean,
    val chooseTitle: Boolean,
    val noticeLine: SyncPlayNotice?,
    val pendingNotices: Int,
    val hostName: String? = null,
    val self: String? = null,
    val transit: Boolean = false,
    val log: Stamped? = null,
    val cardItem: String? = null,
    val finished: Boolean = false,
)

/**
 * docs/28 §1: the lobby for [exec]; a host may pick again once the queue ended. [ownName] is the account's user name;
 * [leaving] is a Back already sent, shown as Leaving before the core's snapshot says so.
 */
internal fun lobbyView(exec: GroupExec, origin: Origin, memory: LobbyMemory = LobbyMemory(), ownName: String? = null, leaving: Boolean = false): LobbyView {
    val held = when (origin) {
        is Origin.Created -> origin.held
        is Origin.Adopted -> origin.held
        is Origin.Joined -> null
    }
    val onLobby = noticeSurface(exec.stage) == NoticeSurface.Lobby
    val live = if (onLobby) liveNotice(exec.notices) else null
    val group = exec.group
    val member = (group as? SyncPlayGroup.Member)?.takeUnless { leaving }
    val host = member?.role?.let { it == SyncPlayRole.HOST } ?: (origin !is Origin.Joined)
    val connected = member?.connected ?: true
    val chooseTitle = member != null && host && member.stage == SyncPlayStage.FINISHED
    val self = member?.let { selfName(it.participants, it.host, ownName) }
    return LobbyView(
        phase = if (leaving) LobbyPhase.Leaving else when (group) {
            SyncPlayGroup.Idle, is SyncPlayGroup.Starting -> LobbyPhase.Starting
            is SyncPlayGroup.Member -> LobbyPhase.Members
            is SyncPlayGroup.Leaving -> LobbyPhase.Leaving
        },
        host = host,
        // docs/28 §1: every member sees the room's name, the host's from the core and a guest's from its list row.
        name = exec.name,
        members = member?.participants.orEmpty(),
        connected = connected,
        startTicks = if (member != null && host && held != null) startChoices(held) else emptyList(),
        startActive = startAllowed(group),
        chooseTitle = chooseTitle,
        // The reconnecting status line already says it while disconnected.
        noticeLine = live.takeUnless { it == SyncPlayNotice.Reconnecting && !connected },
        pendingNotices = if (onLobby) exec.notices.count { !isTerminal(it) } else 0,
        // docs/28 §1: the core names the host; our own name stands for it only when we host.
        hostName = member?.let { hostName(it.host, self) },
        self = self,
        transit = inTransit(exec),
        log = memory.log,
        // docs/28 §1 (R3): a guest learns the title only once it played.
        cardItem = if (host) held?.itemId ?: memory.lastPlayed else memory.lastPlayed,
        finished = chooseTitle && (memory.lastPlayed == null || held?.itemId == memory.lastPlayed),
    )
}

/** docs/28 §1 (Q1): a held title with a resume position starts from the beginning or from there. */
internal fun startChoices(held: HeldItem): List<Long> = if (held.resumeTicks > 0) listOf(0L, held.resumeTicks) else listOf(0L)

// -- Members --------------------------------------------------------------------------------------

internal enum class MemberTag(@StringRes val label: Int) {
    Host(R.string.wt_tag_host),
    You(R.string.wt_tag_you),
    HostYou(R.string.wt_tag_host_you),
    Joined(R.string.wt_tag_joined),
    InGroup(R.string.wt_tag_in_group),
}

internal data class MemberRow(val name: String, val tag: MemberTag) {
    val own: Boolean get() = tag == MemberTag.You || tag == MemberTag.HostYou
}

/**
 * docs/28 §1: our own row, never from list order: the account's name when listed, else the one name left
 * once the core's host is set aside; `null` when it can't tell.
 */
internal fun selfName(participants: List<String>, host: SyncPlayHost, account: String?): String? = when {
    account != null && account in participants -> account
    host == SyncPlayHost.Me && participants.size == 1 -> participants.first()
    host is SyncPlayHost.Known && participants.size == 2 && host.name in participants -> participants.firstOrNull { it != host.name }
    else -> null
}

/** The host first, then join order (the server's); JOINED marks whoever came after [self]. */
internal fun memberRows(participants: List<String>, host: String?, self: String?): List<MemberRow> {
    val selfAt = self?.let(participants::indexOf) ?: -1
    return participants.withIndex().sortedBy { (i, name) -> if (name == host) -1 else i }.map { (i, name) ->
        val tag = when {
            name == host && name == self -> MemberTag.HostYou
            name == host -> MemberTag.Host
            name == self -> MemberTag.You
            selfAt >= 0 && i > selfAt -> MemberTag.Joined
            else -> MemberTag.InGroup
        }
        MemberRow(name, tag)
    }
}

// -- Log line -------------------------------------------------------------------------------------

internal enum class LogTone { Waiting, Event, Alert }

internal data class LogLine(val text: String, val tone: LogTone)

/** The lobby's one log line; [time] formats an arrival in the device's 12/24 h style. */
internal fun logLine(strings: UiStrings, view: LobbyView, time: (Long) -> String): LogLine? = when (view.phase) {
    LobbyPhase.Starting -> LogLine(strings.get(if (view.host) R.string.wt_starting else R.string.wt_joining).uppercaseUi(), LogTone.Waiting)
    LobbyPhase.Leaving -> LogLine(strings.get(R.string.wt_leaving).uppercaseUi(), LogTone.Waiting)
    LobbyPhase.Members -> {
        // A reconnect that already landed is old news, and the core's Joined to the creator is not a member's event.
        val log = view.log?.takeUnless { (it.notice == SyncPlayNotice.Reconnecting && view.connected) || (view.host && it.notice == SyncPlayNotice.Joined) }
        when {
            !view.connected -> LogLine(strings.get(R.string.wt_log_reconnecting), LogTone.Alert)
            log != null -> LogLine("${time(log.atMs)}  ${logText(strings, log.notice)}", LogTone.Event)
            view.host && view.members.size <= 1 -> LogLine(strings.get(R.string.wt_log_waiting), LogTone.Waiting)
            else -> null
        }
    }
}

/** docs/28 §1 (R1): only the log's own words are upper case; a user name is the server's, verbatim. */
internal fun logText(strings: UiStrings, notice: SyncPlayNotice): String = when (notice) {
    SyncPlayNotice.Joined -> strings.get(R.string.wt_log_you_joined)
    is SyncPlayNotice.UserJoined -> strings.get(R.string.wt_log_user_joined, notice.name)
    is SyncPlayNotice.UserLeft -> strings.get(R.string.wt_log_user_left, notice.name)
    SyncPlayNotice.Reconnecting -> strings.get(R.string.wt_log_reconnecting)
    SyncPlayNotice.StartFailed, SyncPlayNotice.NoSuchGroup, SyncPlayNotice.NoAccess, is SyncPlayNotice.GroupEnded,
    SyncPlayNotice.HostLeft, SyncPlayNotice.CreateRefused, SyncPlayNotice.JoinRefused -> noticeText(strings, notice).uppercaseUi()
}

// -- Labels ---------------------------------------------------------------------------------------

/** A string resource and its one argument. */
internal data class Copy(@StringRes val id: Int, val arg: String? = null) {
    fun text(strings: UiStrings): String = if (arg == null) strings.get(id) else strings.get(id, arg)
}

internal fun lobbyRole(view: LobbyView): Int = if (view.host) R.string.wt_role_hosting else R.string.wt_role_joined

/** docs/28 §1 (R5): a guest waits for the host the core names, else for "the host"; a late joiner is joining. */
internal fun lobbyHeading(view: LobbyView): Copy = when {
    view.host && view.finished -> Copy(R.string.wt_finished_title)
    view.host -> Copy(R.string.wt_host_title)
    view.transit -> Copy(R.string.wt_joining)
    view.hostName != null -> Copy(R.string.wt_waiting_title, view.hostName)
    else -> Copy(R.string.wt_waiting_host_title)
}

internal sealed interface LobbyButton {
    val key: String

    data class Start(val index: Int, val ticks: Long, val label: Copy) : LobbyButton {
        override val key: String get() = "start-$index"
    }

    data object Choose : LobbyButton {
        override val key: String get() = "choose"
    }

    data class Exit(val label: Copy) : LobbyButton {
        override val key: String get() = "cancel"
    }
}

/** The lobby's buttons in focus order; a finished host chooses another title first. */
internal fun lobbyButtons(view: LobbyView): List<LobbyButton> {
    val starts = view.startTicks.mapIndexed { index, ticks -> LobbyButton.Start(index, ticks, startLabel(view, ticks)) }
    val choose = listOfNotNull(LobbyButton.Choose.takeIf { view.chooseTitle })
    val exit = LobbyButton.Exit(
        Copy(
            when {
                !view.host -> R.string.wt_leave
                view.finished -> R.string.wt_end_group
                else -> R.string.wt_cancel
            },
        ),
    )
    return if (view.finished) choose + starts + exit else starts + choose + exit
}

private fun startLabel(view: LobbyView, ticks: Long): Copy = when {
    view.finished && ticks == 0L -> Copy(R.string.wt_play_again)
    view.startTicks.size == 1 -> Copy(R.string.wt_start)
    ticks == 0L -> Copy(R.string.wt_start_beginning)
    else -> Copy(R.string.wt_start_resume, PlaybackTimeFormat.format(ticks / TICKS_PER_MS))
}

/** The title card's caption: what the host holds, or what the group just watched (R3). */
internal fun cardCaption(view: LobbyView): Int = when {
    !view.host || view.finished -> R.string.wt_card_just_watched
    view.chooseTitle -> R.string.wt_card_up_next
    else -> R.string.wt_card_now_showing
}

// -- Room list ------------------------------------------------------------------------------------

/** docs/28 §1: names shown on a room's row; past these the row counts the rest. */
private const val ROOM_NAMES_SHOWN = 4

/** A room row's title: [names] joined in server order, then [more] not shown (0 for none). */
internal data class RoomNames(val names: String, val more: Int)

/** docs/28 §1: the first four members verbatim in server order, the rest counted; the list never says who hosts. */
internal fun roomNames(participants: List<String>): RoomNames =
    RoomNames(participants.take(ROOM_NAMES_SHOWN).joinToString(", "), (participants.size - ROOM_NAMES_SHOWN).coerceAtLeast(0))

/** docs/28 §1: a room's listed name, verbatim; `null` when blank, so neither its row nor its lobby shows an empty line. */
internal fun listedRoomName(room: SyncPlayRoom): String? = room.name.takeUnless { it.isBlank() }

/** A room's state tag; `null` when the server's state is absent or unknown. */
@StringRes
internal fun roomTag(phase: SyncPlayPhase?): Int? = when (phase) {
    SyncPlayPhase.IDLE -> R.string.wt_room_in_lobby
    SyncPlayPhase.PLAYING -> R.string.wt_room_playing
    SyncPlayPhase.PAUSED -> R.string.wt_room_paused
    SyncPlayPhase.WAITING -> R.string.wt_room_waiting
    null -> null
}

/** The room list's line: a picked room's failure in amber, or the line the last membership ended on. */
internal fun roomsLineText(strings: UiStrings, line: RoomsLine): LogLine? = when (line) {
    is RoomsLine.JoinFailed -> LogLine(
        strings.get(
            when (line.error) {
                JoinError.Gone -> R.string.wt_room_gone
                JoinError.NoAccess -> R.string.wt_room_no_library
                JoinError.Unavailable, JoinError.Failed -> R.string.wt_room_unreachable
            },
        ),
        LogTone.Alert,
    )
    is RoomsLine.Ended -> outcomeText(strings, line.outcome)?.let { LogLine(it, LogTone.Event) }
}

/**
 * docs/28 §1: where focus goes when a refresh lands, tracked by room id: the focused room wherever it moved,
 * else the room now at its old [prevIndex], else the last room; `null` when no room is left.
 */
internal fun roomFocusAfter(prevFocusId: String, prevIndex: Int, rooms: List<SyncPlayRoom>): String? = when {
    rooms.any { it.groupId == prevFocusId } -> prevFocusId
    rooms.isEmpty() -> null
    else -> rooms[prevIndex.coerceIn(0, rooms.lastIndex)].groupId
}

/** What a room-list refresh does to focus; [Move.key] is the focus key to take it to. */
internal sealed interface RoomRefocus {
    data object Keep : RoomRefocus
    data object Forget : RoomRefocus
    data class Move(val key: String) : RoomRefocus
}

/**
 * docs/28 §1: a refresh that drops the focused room moves focus to the nearest row only while focus is in the
 * pane on the top screen; elsewhere, or with no room left, the room is forgotten and nothing takes focus.
 */
internal fun roomRefocus(prevFocusId: String, prevIndex: Int, rooms: List<SyncPlayRoom>, focusInPane: Boolean, isTop: Boolean): RoomRefocus = when {
    rooms.any { it.groupId == prevFocusId } -> RoomRefocus.Keep
    !focusInPane || !isTop -> RoomRefocus.Forget
    else -> roomFocusAfter(prevFocusId, prevIndex, rooms)?.let { RoomRefocus.Move(roomKey(it)) } ?: RoomRefocus.Forget
}

/** docs/28 §1: a row's top rule, which the first row, the focused row and the row under it go without. */
internal fun roomRowRuled(index: Int, focusedIndex: Int?): Boolean = index > 0 && index != focusedIndex && index - 1 != focusedIndex

/** What the room list shows: [rooms] as listed, and [joining] the room a join is under way for, which holds the list still. */
internal data class RoomsPaneView(val rooms: TogetherState.Rooms, val joining: String?)

/**
 * docs/28 §1: the room list shows in Rooms, and stays up while a picked room is joined, until that membership
 * reaches its lobby or its title ([joinPending]: still starting, or a late joiner in transit).
 */
internal fun roomsPane(state: TogetherState, joinPending: Boolean): RoomsPaneView? = when (state) {
    is TogetherState.Rooms -> RoomsPaneView(state, null)
    is TogetherState.Entering -> (state.entry as? Entry.Join)?.let { RoomsPaneView(it.behind, it.target.groupId) }
    // A room left with Back shows the list at once, nothing joining, while the leave finishes (picks wait for its end).
    is TogetherState.Member -> (state.origin as? Origin.Joined)?.takeIf { joinPending || state.leaving }?.let { origin ->
        RoomsPaneView(origin.behind, origin.target.groupId.takeUnless { state.leaving })
    }
    else -> null
}
