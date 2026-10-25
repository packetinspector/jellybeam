package tv.jellybeam.player

import androidx.media3.common.Player
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlaySeverity

/** docs/28 §5: who drives the player, the viewer alone or the group through [exec] for its load [loadSeq]. */
internal sealed interface PlayerMode {
    data object Solo : PlayerMode

    /**
     * [pendingSeeks]: the seeks asked of the group whose server Seek hasn't run, oldest first; the next tap adds to the newest.
     * [loadIssued]: the group's load reached the player, so a READY is its landing rather than the old item's.
     */
    data class Group(val exec: SyncPlayExecutor, val loadSeq: ULong, val pendingSeeks: List<Long> = emptyList(), val loadIssued: Boolean = false) : PlayerMode
}

/** docs/28 §4: this player's port for one membership; it acts only while it is the one attached. */
internal class AttachedPort(val exec: SyncPlayExecutor, private val onCall: (AttachedPort, PortCall) -> Unit) : GroupPort {
    override fun execute(call: PortCall) = onCall(this, call)
}

/** Tells the group a fact about the load this mode plays. */
internal fun PlayerMode.Group.send(event: (tag: ULong) -> GroupEvent.Tagged) = exec.send(event(loadSeq))

/** docs/28 §4: a transport press asks the group; the player moves only on the server's directive. */
internal fun PlayerMode.Group.request(input: SyncPlayInput) = send { GroupEvent.UserTransport(it, input) }

/** docs/28 §5: READY answers the issued load or a stall, a stall while meaning to play is buffering, and the end is the group's to handle. */
internal fun PlayerMode.Group.reportState(playbackState: Int, player: PlaybackPlayer) {
    when (playbackState) {
        Player.STATE_READY -> if (loadIssued) send { GroupEvent.PlayerReady(it, player.currentPositionTicks()) }
        Player.STATE_BUFFERING -> if (player.playWhenReady) send(GroupEvent::PlayerBuffering)
        Player.STATE_ENDED -> if (player.playbackState == Player.STATE_ENDED) send(GroupEvent::PlayerEnded)
    }
}

/**
 * docs/28 §4: the server runs our seeks in the order asked (the core may skip superseded ones), so its Seek to one
 * of ours retires that ask and every older one; any other target is someone else's and starts the taps over.
 */
internal fun seeksAfterGroupSeek(pending: List<Long>, ranTicks: Long): List<Long> {
    val ours = pending.indexOf(ranTicks)
    return if (ours < 0) emptyList() else pending.drop(ours + 1)
}

/** docs/28 §5: a group never renegotiates a transcode, so it refuses a stream-row pick and any pick off a burned-in stream. */
internal fun groupRefusesSubtitlePick(mode: PlayerMode, plan: PlaybackPlan?, streamRow: Boolean): Boolean =
    mode is PlayerMode.Group && (streamRow || plan?.playMethod == PlayMethodFfi.TRANSCODE && plan.burnedSubtitleIndex != null)

/**
 * docs/28 §1: a skip in a group is one group seek, so only the host's own setting may auto-skip; a guest gets the
 * pill instead ([role] null = solo, unchanged).
 */
internal fun groupSegmentDecision(decision: SegmentDecision, role: SyncPlayRole?): SegmentDecision = when {
    role == null || role == SyncPlayRole.HOST -> decision
    decision == SegmentDecision.AUTO_SKIP -> SegmentDecision.PILL
    else -> decision
}

/** The player's own role in [mode]'s group, or `null` solo or before the core says. */
internal fun groupRole(mode: PlayerMode): SyncPlayRole? =
    ((mode as? PlayerMode.Group)?.exec?.state?.value?.group as? SyncPlayGroup.Member)?.role

/** docs/28 §4: every exit returns the player to solo controls; the video's play state is left as it is. */
internal fun PlaybackViewModel.leaveGroupMode() {
    mode = PlayerMode.Solo
    attached = null
}

/** docs/28 §4: a fatal error leaves the group from the player, which then takes the solo fatal path. */
internal fun PlaybackViewModel.failGroupMode() {
    val group = mode as? PlayerMode.Group ?: return
    group.send { GroupEvent.PlayerError(it, SyncPlaySeverity.FATAL) }
    leaveGroupMode()
}

/** docs/28 §1, §4: a transcode fallback or other content the viewer picks leaves the group first. */
internal fun PlaybackViewModel.leaveGroup() {
    val group = mode as? PlayerMode.Group ?: return
    group.exec.send(GroupEvent.UserLeave)
    leaveGroupMode()
}

/** docs/28 §5: the executor's port; a membership this player left, or never attached to, reaches nothing. */
internal fun PlaybackViewModel.executePort(port: AttachedPort, call: PortCall) {
    if (port !== attached) return
    when (call) {
        is PortCall.Load -> {
            mode = PlayerMode.Group(port.exec, call.load.seq)
            replace(call.load.itemId, startFromBeginning = true, opening = LoadStart.GroupExactPaused(call.load.startTicks))
        }
        is PortCall.Pause -> {
            playerHolder.pause()
            call.freezeTicks?.let(::alignSilently)
        }
        // Aligned while still paused, so the align's buffering is not reported as a playing stall.
        is PortCall.Play -> {
            call.positionTicks?.let(::alignSilently)
            playerHolder.play()
        }
        // The tag is the load this seek was issued for, so a landing after the next Load is stale.
        is PortCall.SeekExact -> {
            val group = mode as? PlayerMode.Group ?: return
            // A playing correction is drift, nobody's ask, so only a group Seek retires asks.
            if (!call.playing) mode = group.copy(pendingSeeks = seeksAfterGroupSeek(group.pendingSeeks, call.positionTicks))
            if (!call.playing) playerHolder.pause()
            playerHolder.seekGroup(PlaybackTicks.ticksToMs(call.positionTicks)) { landed, latencyMs ->
                group.exec.send(GroupEvent.SeekLanded(group.loadSeq, landed, latencyMs))
            }
        }
        PortCall.Solo -> leaveGroupMode()
        PortCall.CloseToLobby -> {
            leaveGroupMode()
            finishAfterStop(reason = "lobby")
        }
        PortCall.Close -> {
            leaveGroupMode()
            finishAfterStop(reason = "group_closed")
        }
    }
}

/** docs/28 §5: a pause or play lands on the group's position only when the player is out of tolerance, and reports nothing. */
private fun PlaybackViewModel.alignSilently(targetTicks: Long) {
    if (groupSeekNeeded(playerHolder.currentPositionTicks(), targetTicks)) playerHolder.seekGroup(PlaybackTicks.ticksToMs(targetTicks), null)
}
