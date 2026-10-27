package tv.jellybeam.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SyncPlayDirective
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlayListener
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlaySeverity
import uniffi.jellybeam_core.SyncPlaySnapshot
import uniffi.jellybeam_core.SyncPlayUpdate
import kotlin.math.abs

/** docs/28 §5: positions are 100 ns ticks. */
private const val TICKS_PER_MS = 10_000L

/** docs/28 §5: drift below this is in sync. */
private const val GROUP_SEEK_TOLERANCE_MS = 400L

/** docs/28 §5: drift is evaluated at most this often, so position samples are no denser. */
internal const val POSITION_SAMPLE_MS = 1_500L

/** docs/28 §5: a command whose time already passed starts as far in as the time it missed. */
internal fun unpauseTarget(positionTicks: Long, dueWallMs: Long, nowMs: Long): Long =
    positionTicks + maxOf(0L, nowMs - dueWallMs) * TICKS_PER_MS

/** docs/28 §5: whether a player at [currentTicks] is far enough from [targetTicks] to seek there. */
internal fun groupSeekNeeded(currentTicks: Long, targetTicks: Long): Boolean =
    abs(targetTicks - currentTicks) >= GROUP_SEEK_TOLERANCE_MS * TICKS_PER_MS

/** docs/28 §4: the item a group Load asks for; [seq] tags every player event about it. */
internal data class GroupLoad(val seq: ULong, val itemId: String, val playlistItemId: String, val startTicks: Long)

/** What the executor asks of the player, on the main thread; a port never reports a call to the group, the executor sends every input. */
internal sealed interface PortCall {
    data class Load(val load: GroupLoad) : PortCall
    /** `null` pauses where the player is. */
    data class Pause(val freezeTicks: Long?) : PortCall
    /** `null` plays from where the player is. */
    data class Play(val positionTicks: Long?) : PortCall
    data class SeekExact(val positionTicks: Long, val playing: Boolean) : PortCall
    data object Solo : PortCall
    data object CloseToLobby : PortCall
    data object Close : PortCall
}

/** The player side of a membership (docs/28 §5): the playback view model while a group plays. */
internal fun interface GroupPort {
    fun execute(call: PortCall)
}

internal sealed interface Motion {
    data class Steady(val sampledAtMs: Long) : Motion
    data object Stalled : Motion
    /** Owes the SeekLanded of directive [seq]. */
    data class Seeking(val seq: ULong) : Motion
}

/** The one armed timer, named by the directive that armed it. */
internal sealed interface Timed {
    data object None : Timed
    data class Pause(val seq: ULong, val freezeTicks: Long?) : Timed
    data class Unpause(val seq: ULong, val dueWallMs: Long, val positionTicks: Long) : Timed
    data class Hold(val seq: ULong) : Timed
}

internal sealed interface Stage {
    data object Lobby : Stage
    data class Launching(val load: GroupLoad) : Stage
    /** Owes ItemLoaded: the core holds every command until it lands (docs/28 §4). */
    data class Loading(val load: GroupLoad, val port: GroupPort) : Stage
    data class Attached(val loadSeq: ULong, val port: GroupPort, val motion: Motion, val timed: Timed) : Stage
    data object Gone : Stage
}

/**
 * One membership's executor state; [lastSeq] is the last directive applied (docs/28 §4). [name] is the
 * room's name, latched from the first Starting snapshot because Member carries none; `null` until known.
 * [dropped] counts live notices a screen showed and dropped, so a reader tells a new head from an equal one.
 */
internal data class GroupExec(
    val lastSeq: ULong = 0uL,
    val seekLatencyMs: Long = 0L,
    val group: SyncPlayGroup = SyncPlayGroup.Idle,
    val name: String? = null,
    val notices: List<SyncPlayNotice> = emptyList(),
    val stage: Stage = Stage.Lobby,
    val dropped: Int = 0,
)

internal sealed interface GroupEvent {
    data class Dir(val seq: ULong, val directive: SyncPlayDirective) : GroupEvent
    data class Notices(val notices: List<SyncPlayNotice>) : GroupEvent
    /** [by] showed the oldest live notice. */
    data class NoticesShown(val by: NoticeSurface) : GroupEvent
    /** The host chose what the group plays, from [startTicks]; [queue] starts with [itemId] (docs/28 §2). */
    data class UserPick(val itemId: String, val startTicks: Long, val queue: List<String> = listOf(itemId)) : GroupEvent
    data object Changed : GroupEvent
    data class SnapshotRead(val group: SyncPlayGroup) : GroupEvent
    data object AccountChanged : GroupEvent
    data object EpochMoved : GroupEvent
    data object UserLeave : GroupEvent
    data class Due(val seq: ULong) : GroupEvent
    data class PortAttached(val port: GroupPort) : GroupEvent

    /** A player or user event about the load whose seq is [tag]; any other tag is stale. */
    sealed interface Tagged : GroupEvent {
        val tag: ULong
    }

    data class PlayerReady(override val tag: ULong, val positionTicks: Long) : Tagged
    data class PlayerBuffering(override val tag: ULong) : Tagged
    data class SeekLanded(override val tag: ULong, val landedTicks: Long, val latencyMs: Long) : Tagged
    data class Position(override val tag: ULong, val positionTicks: Long) : Tagged
    data class PlayerEnded(override val tag: ULong) : Tagged
    data class PlayerError(override val tag: ULong, val severity: SyncPlaySeverity) : Tagged
    data class PlayerClosed(override val tag: ULong) : Tagged
    /** [input] is Pause, Unpause, Seek, Next or Prev. */
    data class UserTransport(override val tag: ULong, val input: SyncPlayInput) : Tagged
}

internal sealed interface GroupEffect {
    data class Input(val input: SyncPlayInput) : GroupEffect
    data object ReadSnapshot : GroupEffect
    data class Arm(val seq: ULong, val delayMs: Long) : GroupEffect
    data class Launch(val load: GroupLoad) : GroupEffect
    data class Port(val port: GroupPort, val call: PortCall) : GroupEffect
    data object End : GroupEffect
}

/** docs/28 §1: the room a join picked from the list; [name] is its listed name, verbatim, so a guest shows it too; `null` when blank. */
internal data class JoinTarget(val groupId: String, val name: String?)

/** How a create or join ended; Refused is a group the core didn't start for us (docs/28 §1 refusals). */
internal sealed interface GroupEntry {
    data class Started(val executor: SyncPlayExecutor) : GroupEntry
    data object Refused : GroupEntry
}

internal data class Step(val state: GroupExec, val effects: List<GroupEffect> = emptyList())

/** docs/28 §4-§5: the executor's one transition function; [nowMs] is the wall clock directives are due on. */
internal fun reduce(state: GroupExec, event: GroupEvent, nowMs: Long): Step {
    if (state.stage == Stage.Gone) return Step(state)
    return when (event) {
        is GroupEvent.Dir -> if (event.seq <= state.lastSeq) Step(state) else directive(state.copy(lastSeq = event.seq), event, nowMs)
        // docs/28 §1: each core step carries only its own notices, so they add up until shown.
        is GroupEvent.Notices -> Step(state.copy(notices = state.notices + event.notices))
        is GroupEvent.NoticesShown -> noticeShown(state, event.by)
        // docs/28 §1: the held item goes to the group only at the host's Start in the lobby.
        is GroupEvent.UserPick ->
            if (state.stage == Stage.Lobby && startAllowed(state.group)) {
                Step(state, listOf(input(SyncPlayInput.PickItem(event.queue, 0, event.startTicks))))
            } else {
                Step(state)
            }
        GroupEvent.Changed -> Step(state, listOf(GroupEffect.ReadSnapshot))
        is GroupEvent.SnapshotRead -> if (event.group == SyncPlayGroup.Idle) gone(state) else snapshot(state, event.group)
        GroupEvent.AccountChanged, GroupEvent.EpochMoved -> gone(state)
        GroupEvent.UserLeave -> Step(state, listOf(GroupEffect.Input(SyncPlayInput.Leave)))
        is GroupEvent.PortAttached -> attach(state, event.port)
        is GroupEvent.Due -> due(state, event.seq, nowMs)
        is GroupEvent.Tagged -> when (val stage = state.stage) {
            is Stage.Loading -> if (event.tag == stage.load.seq) loadingPlayer(state, stage, event, nowMs) else Step(state)
            is Stage.Attached -> if (event.tag == stage.loadSeq) attachedPlayer(state, stage, event, nowMs) else Step(state)
            Stage.Lobby, is Stage.Launching, Stage.Gone -> Step(state)
        }
    }
}

private fun portOf(stage: Stage): GroupPort? = when (stage) {
    is Stage.Loading -> stage.port
    is Stage.Attached -> stage.port
    Stage.Lobby, is Stage.Launching, Stage.Gone -> null
}

private fun GroupPort.call(call: PortCall) = GroupEffect.Port(this, call)

private fun input(input: SyncPlayInput) = GroupEffect.Input(input)

/** docs/28 §4: every exit returns a loaded player to solo before the executor ends. */
private fun gone(state: GroupExec) =
    Step(state.copy(stage = Stage.Gone), listOfNotNull(portOf(state.stage)?.call(PortCall.Solo), GroupEffect.End))

private fun snapshot(state: GroupExec, group: SyncPlayGroup) =
    Step(state.copy(group = group, name = state.name ?: (group as? SyncPlayGroup.Starting)?.name))

/** docs/28 §1: only the screen now showing notices clears one, and a terminal notice stays for the screen that ends. */
private fun noticeShown(state: GroupExec, by: NoticeSurface): Step {
    val live = state.notices.indexOfFirst { !isTerminal(it) }
    if (noticeSurface(state.stage) != by || live < 0) return Step(state)
    return Step(state.copy(notices = state.notices.filterIndexed { i, _ -> i != live }, dropped = state.dropped + 1))
}

private fun toLobby(state: GroupExec, report: SyncPlayInput) = Step(state.copy(stage = Stage.Lobby), listOf(input(report)))

/** A port is ours only by attaching to a launch; any other is closed, and ours re-attaching is a recreated Activity. */
private fun attach(state: GroupExec, port: GroupPort): Step = when (val stage = state.stage) {
    is Stage.Launching -> Step(state.copy(stage = Stage.Loading(stage.load, port)), listOf(port.call(PortCall.Load(stage.load))))
    Stage.Lobby, is Stage.Loading, is Stage.Attached, Stage.Gone ->
        if (portOf(stage) === port) Step(state) else Step(state, listOf(port.call(PortCall.Close)))
}

private fun directive(state: GroupExec, dir: GroupEvent.Dir, nowMs: Long): Step {
    val stage = state.stage
    return when (val d = dir.directive) {
        is SyncPlayDirective.Load -> {
            val load = GroupLoad(dir.seq, d.itemId, d.playlistItemId, d.startTicks)
            when (stage) {
                // docs/28 §4: a Load while Jellybeam is hidden waits until it is opened, and an interrupted launch is dropped, so a Load before any attach launches again.
                Stage.Lobby, is Stage.Launching -> Step(state.copy(stage = Stage.Launching(load)), listOf(GroupEffect.Launch(load)))
                is Stage.Loading -> Step(state.copy(stage = Stage.Loading(load, stage.port)), listOf(stage.port.call(PortCall.Load(load))))
                is Stage.Attached -> Step(state.copy(stage = Stage.Loading(load, stage.port)), listOf(stage.port.call(PortCall.Load(load))))
                Stage.Gone -> Step(state)
            }
        }
        SyncPlayDirective.Detach -> gone(state)
        SyncPlayDirective.ReturnToLobby ->
            if (stage == Stage.Lobby) Step(state) else Step(state.copy(stage = Stage.Lobby), listOfNotNull(portOf(stage)?.call(PortCall.CloseToLobby)))
        // docs/28 §4: the core holds these until the item landed, so only a playing item gets them.
        is SyncPlayDirective.Pause, is SyncPlayDirective.Unpause, is SyncPlayDirective.Seek,
        is SyncPlayDirective.Correct, is SyncPlayDirective.HoldFor ->
            if (stage is Stage.Attached) command(state, stage, dir.seq, d, nowMs) else Step(state)
    }
}

private fun command(state: GroupExec, stage: Stage.Attached, seq: ULong, d: SyncPlayDirective, nowMs: Long): Step {
    fun at(motion: Motion = stage.motion, timed: Timed = stage.timed, vararg effects: GroupEffect) =
        Step(state.copy(stage = stage.copy(motion = motion, timed = timed)), effects.toList())
    val port = stage.port
    return when (d) {
        is SyncPlayDirective.Pause ->
            if (d.dueWallMs <= nowMs) at(timed = Timed.None, effects = arrayOf(port.call(PortCall.Pause(d.freezeTicks))))
            else at(timed = Timed.Pause(seq, d.freezeTicks), effects = arrayOf(GroupEffect.Arm(seq, d.dueWallMs - nowMs)))
        is SyncPlayDirective.Unpause ->
            if (d.dueWallMs <= nowMs) at(timed = Timed.None, effects = arrayOf(port.call(PortCall.Play(unpauseTarget(d.positionTicks, d.dueWallMs, nowMs)))))
            else at(timed = Timed.Unpause(seq, d.dueWallMs, d.positionTicks), effects = arrayOf(GroupEffect.Arm(seq, d.dueWallMs - nowMs)))
        // docs/28 §5: group seeks land exactly, or the server re-sends Seek forever; the group waits paused for every
        // landing, so a timer armed before the seek is void, and firing it would play the member and drop its landing report.
        is SyncPlayDirective.Seek ->
            at(motion = Motion.Seeking(seq), timed = Timed.None, effects = arrayOf(port.call(PortCall.SeekExact(d.positionTicks, playing = false))))
        // docs/28 §5: a playing correction aims past the target by what the last seek took.
        is SyncPlayDirective.Correct -> at(
            motion = Motion.Seeking(seq),
            effects = arrayOf(port.call(PortCall.SeekExact(d.positionTicks + state.seekLatencyMs * TICKS_PER_MS, playing = true))),
        )
        is SyncPlayDirective.HoldFor ->
            at(timed = Timed.Hold(seq), effects = arrayOf(port.call(PortCall.Pause(null)), GroupEffect.Arm(seq, d.ms.toLong())))
        is SyncPlayDirective.Load, SyncPlayDirective.Detach, SyncPlayDirective.ReturnToLobby -> Step(state)
    }
}

/** A timer acts only while its directive still owns the slot. */
private fun due(state: GroupExec, seq: ULong, nowMs: Long): Step {
    val stage = state.stage as? Stage.Attached ?: return Step(state)
    val call = when (val t = stage.timed) {
        Timed.None -> null
        is Timed.Pause -> PortCall.Pause(t.freezeTicks).takeIf { t.seq == seq }
        is Timed.Unpause -> PortCall.Play(unpauseTarget(t.positionTicks, t.dueWallMs, nowMs)).takeIf { t.seq == seq }
        is Timed.Hold -> PortCall.Play(null).takeIf { t.seq == seq }
    } ?: return Step(state)
    return Step(state.copy(stage = stage.copy(timed = Timed.None)), listOf(stage.port.call(call)))
}

/** docs/28 §4: a loading item owes exactly one ItemLoaded, at the position it landed on. */
private fun loadingPlayer(state: GroupExec, stage: Stage.Loading, event: GroupEvent.Tagged, nowMs: Long): Step = when (event) {
    is GroupEvent.PlayerReady -> Step(
        state.copy(stage = Stage.Attached(stage.load.seq, stage.port, Motion.Steady(nowMs), Timed.None)),
        listOf(input(SyncPlayInput.ItemLoaded(stage.load.playlistItemId, event.positionTicks))),
    )
    is GroupEvent.PlayerError ->
        if (event.severity == SyncPlaySeverity.FATAL) toLobby(state, SyncPlayInput.PlaybackError(event.severity)) else Step(state)
    is GroupEvent.PlayerClosed -> toLobby(state, SyncPlayInput.PlayerClosed)
    is GroupEvent.UserTransport -> Step(state, listOf(input(event.input)))
    is GroupEvent.PlayerBuffering, is GroupEvent.SeekLanded, is GroupEvent.Position, is GroupEvent.PlayerEnded -> Step(state)
}

private fun attachedPlayer(state: GroupExec, stage: Stage.Attached, event: GroupEvent.Tagged, nowMs: Long): Step {
    fun moved(motion: Motion, report: SyncPlayInput, latencyMs: Long = state.seekLatencyMs) =
        Step(state.copy(seekLatencyMs = latencyMs, stage = stage.copy(motion = motion)), listOf(input(report)))
    val motion = stage.motion
    return when (event) {
        is GroupEvent.PlayerReady ->
            if (motion == Motion.Stalled) moved(Motion.Steady(nowMs), SyncPlayInput.BufferingEnded(event.positionTicks)) else Step(state)
        is GroupEvent.PlayerBuffering ->
            if (motion is Motion.Steady) moved(Motion.Stalled, SyncPlayInput.BufferingStarted) else Step(state)
        is GroupEvent.SeekLanded ->
            if (motion is Motion.Seeking) moved(Motion.Steady(nowMs), SyncPlayInput.SeekLanded(motion.seq, event.landedTicks), event.latencyMs) else Step(state)
        // docs/28 §5: drift is judged only on steady, untimed playback, at most every 1.5 s.
        is GroupEvent.Position ->
            if (motion is Motion.Steady && stage.timed == Timed.None && nowMs - motion.sampledAtMs >= POSITION_SAMPLE_MS) {
                moved(Motion.Steady(nowMs), SyncPlayInput.PositionSample(event.positionTicks))
            } else {
                Step(state)
            }
        is GroupEvent.PlayerEnded -> Step(state, listOf(input(SyncPlayInput.PlayerEnded)))
        is GroupEvent.PlayerError ->
            if (event.severity == SyncPlaySeverity.FATAL) toLobby(state, SyncPlayInput.PlaybackError(event.severity))
            else moved(if (motion is Motion.Steady) Motion.Stalled else motion, SyncPlayInput.PlaybackError(event.severity))
        is GroupEvent.PlayerClosed -> toLobby(state, SyncPlayInput.PlayerClosed)
        is GroupEvent.UserTransport -> Step(state, listOf(input(event.input)))
    }
}

/** docs/28 §4: a step's notices go first, since a Detach in the same step ends the executor and drops what follows. */
internal fun expand(update: SyncPlayUpdate): List<GroupEvent> = buildList {
    if (update.notices.isNotEmpty()) add(GroupEvent.Notices(update.notices))
    update.directives.forEach { add(GroupEvent.Dir(it.seq, it.directive)) }
    if (update.changed) add(GroupEvent.Changed)
}

/** docs/21 §2.1: a fixed word per variant, since R8 renames classes and ids never reach logs. */
private fun directiveKind(directive: SyncPlayDirective): String = when (directive) {
    is SyncPlayDirective.Load -> "load"
    is SyncPlayDirective.Pause -> "pause"
    is SyncPlayDirective.Unpause -> "unpause"
    is SyncPlayDirective.Seek -> "seek"
    is SyncPlayDirective.Correct -> "correct"
    is SyncPlayDirective.HoldFor -> "hold_for"
    SyncPlayDirective.Detach -> "detach"
    SyncPlayDirective.ReturnToLobby -> "return_to_lobby"
}

/**
 * docs/28 §4: runs one membership from create or join until the group is Idle, the only writer of
 * its state; its jobs live in one scope that End cancels.
 */
internal class SyncPlayExecutor(
    private val gateway: CoreGateway,
    val accountEpoch: ULong,
    private val launchPlayer: (GroupLoad) -> Unit,
    /** Where an abandoned entry's Leave runs, since it must outlive this executor's scope. */
    private val leaveScope: CoroutineScope = AppGraph.processScope,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val events = Channel<GroupEvent>(Channel.UNLIMITED)

    /** docs/28 §4: core calls run one at a time in event order. */
    private val io = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(GroupExec())
    val state: StateFlow<GroupExec> = _state.asStateFlow()

    /** Called on a core runtime thread, so it only queues; calling an export here could deadlock the core. */
    val listener: SyncPlayListener = object : SyncPlayListener {
        override fun onStep(update: SyncPlayUpdate) {
            expand(update).forEach { events.trySend(it) }
        }
    }

    fun send(event: GroupEvent) {
        events.trySend(event)
    }

    private fun start() {
        scope.launch { for (event in events) dispatch(event) }
        scope.launch { for (call in io) callCore(call) }
    }

    private fun dispatch(event: GroupEvent) {
        if (event is GroupEvent.Dir) AppGraph.diag.event("syncplay.directive") { tag("kind", directiveKind(event.directive)) }
        val now = clock()
        var effects = emptyList<GroupEffect>()
        _state.update { reduce(it, event, now).also { step -> effects = step.effects }.state }
        effects.forEach(::run)
    }

    private suspend fun callCore(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: CoreException.AccountChanged) {
            send(GroupEvent.AccountChanged)
        } catch (e: CoreException) {
            // ffi() already recorded it; the core's own step decides what a failed input means.
        }
    }

    private fun run(effect: GroupEffect) {
        when (effect) {
            is GroupEffect.Input -> io.trySend { gateway.syncPlayInput(accountEpoch, effect.input) }
            GroupEffect.ReadSnapshot -> io.trySend { send(GroupEvent.SnapshotRead(gateway.syncPlaySnapshot(accountEpoch).group)) }
            // Never cancelled on its own: a later directive retags the slot, so a late fire is inert.
            is GroupEffect.Arm -> scope.launch {
                delay(effect.delayMs)
                send(GroupEvent.Due(effect.seq))
            }
            is GroupEffect.Launch -> launchPlayer(effect.load)
            is GroupEffect.Port -> effect.port.execute(effect.call)
            GroupEffect.End -> end()
        }
    }

    private fun leaveBestEffort() {
        leaveScope.launch { runCatchingCancellable { gateway.syncPlayInput(accountEpoch, SyncPlayInput.Leave) } }
    }

    private fun end() {
        events.cancel()
        io.cancel()
        scope.cancel()
        slot.compareAndSet(this, null)
    }

    companion object {
        private val slot = MutableStateFlow<SyncPlayExecutor?>(null)

        /** docs/28 §8: the process's one membership; written only by an entry and by End. */
        val current: StateFlow<SyncPlayExecutor?> = slot.asStateFlow()

        /** docs/28 §4: the app-exit funnel, and a request replacing the group's waiting load, leave after every input already queued; nothing without a group. */
        fun leave() { slot.value?.send(GroupEvent.UserLeave) }

        /** docs/18 §2.1: an account epoch move ends the running membership. */
        fun onEpochMoved() { slot.value?.send(GroupEvent.EpochMoved) }

        /** The core registers only the first listener of a membership, so entries run one at a time. */
        private val entry = Mutex()

        /** docs/28 §3.3: the running executor of this account, else [build]'s once the core starts a group named by [gateway]. */
        suspend fun create(accountEpoch: ULong, gateway: CoreGateway, build: (ULong) -> SyncPlayExecutor): GroupEntry {
            // docs/28 §3.3: the core waits only for Stopped reports it has, so one still on its way is awaited first,
            // unbounded since stop_playback never waits on the network; outside the entry, so a cancelled wait leaves no group.
            playbackActivityTracker.awaitFinalStops()
            // docs/28 §1: the name's list is its own call outside the entry too, so a create cancelled during it posts nothing.
            val name = try {
                gateway.syncPlayRoomName(accountEpoch)
            } catch (e: CoreException.AccountChanged) {
                return GroupEntry.Refused
            }
            return enter(accountEpoch, build) { gateway.syncPlayCreate(accountEpoch, name, listener) }
        }

        /** docs/28 §8: the app's [create]/[join] builder; nothing is built until an entry starts a fresh membership. */
        fun forApp(): (ULong) -> SyncPlayExecutor =
            { epoch -> SyncPlayExecutor(AppGraph.gateway, epoch, { playbackActivityTracker.arrive(Req.Kind.Play(it.itemId), Req.Origin.Group) }) }

        /** docs/28 §1, §3.1: as [create], joining [target]. */
        suspend fun join(accountEpoch: ULong, target: JoinTarget, build: (ULong) -> SyncPlayExecutor): GroupEntry =
            enter(accountEpoch, build, known = target.name) { gateway.syncPlayJoinRoom(accountEpoch, target.groupId, listener) }

        /** [known] is the room name a join already knows, latched when the core's Starting carries none. */
        private suspend fun enter(
            accountEpoch: ULong,
            build: (ULong) -> SyncPlayExecutor,
            known: String? = null,
            call: suspend SyncPlayExecutor.() -> SyncPlaySnapshot,
        ): GroupEntry =
            entry.withLock {
                slot.value?.let { running ->
                    if (running.accountEpoch == accountEpoch) return@withLock GroupEntry.Started(running)
                    // docs/18 §2.1: another account's membership ends before this account's begins.
                    running.send(GroupEvent.EpochMoved)
                }
                val executor = build(accountEpoch)
                val answer = try {
                    executor.call()
                } catch (e: CancellationException) {
                    // docs/28 §3.4: the core may already hold a group for this listener, and no group is left unowned.
                    executor.abandon()
                    throw e
                } catch (e: CoreException) {
                    executor.end()
                    if (e is CoreException.AccountChanged) return@withLock GroupEntry.Refused
                    throw e
                }
                when (val group = answer.group) {
                    is SyncPlayGroup.Starting -> executor.install(SyncPlayGroup.Starting(group.name ?: known))
                    // docs/28 §3.4: a membership the core holds without our listener has no owner, so it leaves.
                    is SyncPlayGroup.Member -> GroupEntry.Refused.also { executor.abandon() }
                    SyncPlayGroup.Idle, is SyncPlayGroup.Leaving -> GroupEntry.Refused.also { executor.end() }
                }
            }

        /** Installed before its consumer starts, so a Launch already queued finds the slot set. */
        private fun SyncPlayExecutor.install(group: SyncPlayGroup): GroupEntry {
            slot.value = this
            send(GroupEvent.SnapshotRead(group))
            start()
            return GroupEntry.Started(this)
        }

        private fun SyncPlayExecutor.abandon() {
            end()
            leaveBestEffort()
        }
    }
}
