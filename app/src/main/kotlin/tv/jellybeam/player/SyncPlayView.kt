package tv.jellybeam.player

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.watchtogether.logText
import uniffi.jellybeam_core.SyncPlayEndCause
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayStage

/** docs/28 §1: the screen that shows a membership's live notice. */
internal enum class NoticeSurface { Lobby, Player }

/** How a membership ends its screen, and the one line that screen shows. */
internal sealed interface GroupOutcome {
    /** The create or join never became a group; [notice] says why. */
    data class EntryFailed(val notice: SyncPlayNotice) : GroupOutcome
    data class Ended(val notice: SyncPlayNotice) : GroupOutcome
    /** docs/28 §1: a guest left because the core saw the host leave the lobby. */
    data object HostLeft : GroupOutcome
    data object Silent : GroupOutcome
}

/** docs/28 §1: the lobby shows notices until a player loads the group's item; a Gone group shows none. */
internal fun noticeSurface(stage: Stage): NoticeSurface? = when (stage) {
    Stage.Lobby, is Stage.Launching -> NoticeSurface.Lobby
    is Stage.Loading, is Stage.Attached -> NoticeSurface.Player
    Stage.Gone -> null
}

/** docs/28 §1: a member is in the lobby before its first title and after the queue ended. */
internal val SyncPlayGroup.Member.inLobby: Boolean get() = stage != SyncPlayStage.PLAYING

/** docs/28 §1: only a connected host in the lobby starts. */
internal fun startAllowed(group: SyncPlayGroup): Boolean =
    group is SyncPlayGroup.Member && group.role == SyncPlayRole.HOST && group.inLobby && group.connected

/**
 * docs/28 §1 (late join): a guest that joined a room already playing waits for the core's Load, which the
 * first clock sample releases; until then the lobby says it is joining.
 */
internal fun inTransit(exec: GroupExec): Boolean {
    val group = exec.group as? SyncPlayGroup.Member ?: return false
    return group.role == SyncPlayRole.GUEST && group.stage == SyncPlayStage.LOBBY && group.phase != SyncPlayPhase.IDLE && exec.lastSeq == 0uL
}

/** docs/28 §1: a terminal notice ends the membership's screen, so it is kept until that screen reads it. */
internal fun isTerminal(notice: SyncPlayNotice): Boolean = when (notice) {
    SyncPlayNotice.StartFailed, SyncPlayNotice.NoSuchGroup, SyncPlayNotice.NoAccess, is SyncPlayNotice.GroupEnded,
    SyncPlayNotice.HostLeft, SyncPlayNotice.CreateRefused, SyncPlayNotice.JoinRefused -> true
    SyncPlayNotice.Joined, is SyncPlayNotice.UserJoined, is SyncPlayNotice.UserLeft, SyncPlayNotice.Reconnecting -> false
}

/** The live notice on screen now, oldest first. */
internal fun liveNotice(notices: List<SyncPlayNotice>): SyncPlayNotice? = notices.firstOrNull { !isTerminal(it) }

/** The first terminal notice in arrival order decides. */
internal fun outcome(notices: List<SyncPlayNotice>): GroupOutcome {
    val notice = notices.firstOrNull(::isTerminal) ?: return GroupOutcome.Silent
    return when (notice) {
        SyncPlayNotice.StartFailed, SyncPlayNotice.NoSuchGroup, SyncPlayNotice.NoAccess,
        SyncPlayNotice.CreateRefused, SyncPlayNotice.JoinRefused -> GroupOutcome.EntryFailed(notice)
        is SyncPlayNotice.GroupEnded -> GroupOutcome.Ended(notice)
        SyncPlayNotice.HostLeft -> GroupOutcome.HostLeft
        SyncPlayNotice.Joined, is SyncPlayNotice.UserJoined, is SyncPlayNotice.UserLeft, SyncPlayNotice.Reconnecting -> GroupOutcome.Silent
    }
}

/** docs/28 §1: the one line each notice shows; names are the server's, verbatim. */
internal fun noticeText(strings: UiStrings, notice: SyncPlayNotice): String = when (notice) {
    SyncPlayNotice.CreateRefused -> strings.get(R.string.wt_notice_create_refused)
    SyncPlayNotice.JoinRefused -> strings.get(R.string.wt_room_unavailable)
    SyncPlayNotice.StartFailed -> strings.get(R.string.wt_notice_start_failed)
    SyncPlayNotice.NoSuchGroup -> strings.get(R.string.wt_notice_no_such_group)
    SyncPlayNotice.NoAccess -> strings.get(R.string.wt_notice_no_access)
    SyncPlayNotice.Joined -> strings.get(R.string.wt_notice_joined)
    is SyncPlayNotice.UserJoined -> strings.get(R.string.wt_notice_user_joined, notice.name)
    is SyncPlayNotice.UserLeft -> strings.get(R.string.wt_notice_user_left, notice.name)
    SyncPlayNotice.HostLeft -> strings.get(R.string.wt_notice_host_left)
    SyncPlayNotice.Reconnecting -> strings.get(R.string.wt_notice_reconnecting)
    is SyncPlayNotice.GroupEnded -> strings.get(
        when (notice.cause) {
            SyncPlayEndCause.REMOVED -> R.string.wt_notice_ended_removed
            SyncPlayEndCause.GONE -> R.string.wt_notice_ended_gone
            SyncPlayEndCause.AUTH -> R.string.wt_notice_ended_auth
            SyncPlayEndCause.LOST -> R.string.wt_notice_ended_lost
        },
    )
}

/** docs/28 §1: the line a finished screen shows, or `null` for none. */
internal fun outcomeText(strings: UiStrings, outcome: GroupOutcome): String? = when (outcome) {
    is GroupOutcome.EntryFailed -> noticeText(strings, outcome.notice)
    is GroupOutcome.Ended -> noticeText(strings, outcome.notice)
    GroupOutcome.HostLeft -> strings.get(R.string.wt_notice_host_left)
    GroupOutcome.Silent -> null
}

/** One name on the player's member line; [host] marks it "(host)". */
internal data class OsdMember(val name: String, val host: Boolean)

/**
 * docs/28 §1, §6: what the player shows of its group. [name] is the room's name, `null` until known; [prompt] is the stall prompt;
 * [live] holds the live notices, oldest first, while the player is the screen that shows them; [dropped] is the executor's count of dropped notices.
 */
internal data class GroupOsd(
    val name: String?,
    val members: List<OsdMember>,
    val waiting: Boolean,
    val prompt: Boolean,
    val reconnecting: Boolean,
    val live: List<SyncPlayNotice>,
    val dropped: Int,
)

/**
 * docs/28 §1, §6: the player's group, or `null` unless a title is playing; names stay in server order. The core
 * names the host ([SyncPlayHost]); a host marks its own row only by [ownName], the account's user name.
 */
internal fun groupOsd(exec: GroupExec, ownName: String?): GroupOsd? {
    val group = exec.group as? SyncPlayGroup.Member ?: return null
    if (group.stage != SyncPlayStage.PLAYING) return null
    val host = hostName(group.host, ownName)
    return GroupOsd(
        name = exec.name,
        members = group.participants.map { OsdMember(it, it == host) },
        waiting = group.phase == SyncPlayPhase.WAITING,
        prompt = group.promptShown,
        reconnecting = !group.connected,
        live = if (noticeSurface(exec.stage) == NoticeSurface.Player) exec.notices.filterNot(::isTerminal) else emptyList(),
        dropped = exec.dropped,
    )
}

/** docs/28 §1: the host's user name as shown: ours for [SyncPlayHost.Me], the latched name for Known, else none. */
internal fun hostName(host: SyncPlayHost, ownName: String?): String? = when (host) {
    SyncPlayHost.Me -> ownName
    is SyncPlayHost.Known -> host.name
    SyncPlayHost.Among, SyncPlayHost.Unknown -> null
}

/** [readAt] is the executor's dropped count when the player read the head, `null` before any read; [fresh] is a notice to read now. */
internal data class NoticeTurn(val readAt: Int?, val fresh: SyncPlayNotice?)

/**
 * docs/28 §1: the player reads each live notice once. Only a drop removes the head, so while [dropped] is
 * still the count at the last read the head is the one read, even when an equal notice queues behind it.
 */
internal fun noticeTurn(live: List<SyncPlayNotice>, dropped: Int, readAt: Int?): NoticeTurn = when {
    live.isEmpty() || dropped == readAt -> NoticeTurn(readAt, null)
    else -> NoticeTurn(dropped, live.first())
}

/** A notice the player read and when, in wall-clock ms. */
internal data class ReadNotice(val notice: SyncPlayNotice, val atMs: Long)

/** docs/28 §1 (R1, R2): the readout's one event line: reconnecting while it lasts, else the latest join or leave; a reconnect that landed is old news. */
internal fun groupEventLine(strings: UiStrings, osd: GroupOsd, latest: ReadNotice?, time: (Long) -> String): String? = when {
    osd.reconnecting -> strings.get(R.string.wt_log_reconnecting)
    latest == null || latest.notice == SyncPlayNotice.Reconnecting -> null
    else -> "${time(latest.atMs)}  ${logText(strings, latest.notice)}"
}

/** What of the group readout is on screen. */
internal enum class ReadoutShows { All, EventOnly, Nothing }

/** docs/28 §1: the readout shows with the OSD; without it a fresh event or a reconnect shows alone; a sheet in its corner hides it. */
internal fun readoutShows(osdVisible: Boolean, sheetOpen: Boolean, eventFresh: Boolean, reconnecting: Boolean): ReadoutShows = when {
    sheetOpen -> ReadoutShows.Nothing
    osdVisible -> ReadoutShows.All
    eventFresh || reconnecting -> ReadoutShows.EventOnly
    else -> ReadoutShows.Nothing
}

/** The group helper line's one showing per player: not yet, on this OSD reveal, or done. */
internal enum class HelperLine { Pending, Showing, Done }

/** docs/28 §1: in a group the helper line shows on the first OSD reveal and never again. */
internal fun helperAfter(helper: HelperLine, osdVisible: Boolean): HelperLine = when {
    helper == HelperLine.Pending && osdVisible -> HelperLine.Showing
    helper == HelperLine.Showing && !osdVisible -> HelperLine.Done
    else -> helper
}

/** Whether the helper line is drawn now; it shows from the reveal's first frame, before [helperAfter] marks it. */
internal fun helperShown(helper: HelperLine, osdVisible: Boolean): Boolean = osdVisible && helper != HelperLine.Done
