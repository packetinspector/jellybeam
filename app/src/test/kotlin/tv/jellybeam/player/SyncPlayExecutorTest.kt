package tv.jellybeam.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.data.FakeCoreGateway
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SyncPlayDirective
import uniffi.jellybeam_core.SyncPlayDirectiveAt
import uniffi.jellybeam_core.SyncPlayEndCause
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlayLeaveReason
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlaySeverity
import uniffi.jellybeam_core.SyncPlayStage
import uniffi.jellybeam_core.SyncPlayUpdate

private const val NOW = 1_000_000L
private const val TICKS_PER_MS = 10_000L

/** Records what the executor asked of the player. */
private class RecPort : GroupPort {
    val calls = mutableListOf<PortCall>()
    override fun execute(call: PortCall) {
        calls += call
    }
}

private fun load(seq: ULong) = GroupLoad(seq, itemId = "item-$seq", playlistItemId = "entry-$seq", startTicks = 0L)

private val member = SyncPlayGroup.Member(
    role = SyncPlayRole.HOST,
    stage = SyncPlayStage.LOBBY,
    host = SyncPlayHost.Me,
    participants = listOf("member-a"),
    phase = SyncPlayPhase.WAITING,
    connected = true,
    promptShown = false,
)

/** A guest's lobby listing [participants], whose host the core names. */
private fun guest(vararg participants: String) =
    member.copy(role = SyncPlayRole.GUEST, host = SyncPlayHost.Known(participants.first()), participants = participants.toList())

private val leave = GroupEffect.Input(SyncPlayInput.Leave)

private fun loadDir(itemId: String = "item-x") = SyncPlayDirective.Load(itemId, "entry-x", 0L)

private fun attached(
    port: GroupPort,
    motion: Motion = Motion.Steady(NOW - 10_000),
    timed: Timed = Timed.None,
    lastSeq: ULong = 2uL,
    latencyMs: Long = 0L,
) = GroupExec(lastSeq = lastSeq, seekLatencyMs = latencyMs, group = member, stage = Stage.Attached(1uL, port, motion, timed))

private fun loading(port: GroupPort) = GroupExec(lastSeq = 1uL, group = member, stage = Stage.Loading(load(1uL), port))

private fun input(input: SyncPlayInput) = GroupEffect.Input(input)

private fun GroupExec.on(event: GroupEvent, now: Long = NOW) = reduce(this, event, now)

class SyncPlayExecutorTest {

    // -- Pure helpers (docs/28 §5) ----------------------------------------------

    @Test
    fun `a late unpause target adds the missed time and an early one adds nothing`() {
        assertEquals(1_000L + 250 * TICKS_PER_MS, unpauseTarget(1_000L, dueWallMs = NOW, nowMs = NOW + 250))
        assertEquals(1_000L, unpauseTarget(1_000L, dueWallMs = NOW + 250, nowMs = NOW))
    }

    @Test
    fun `a group seek is needed from 400 ms of drift either way`() {
        assertFalse(groupSeekNeeded(0L, 399 * TICKS_PER_MS))
        assertTrue(groupSeekNeeded(0L, 400 * TICKS_PER_MS))
        assertTrue(groupSeekNeeded(400 * TICKS_PER_MS, 0L))
    }

    // -- Table rows (S4 design §4 with §11 amendments) --------------------------

    @Test
    fun `a directive at or below the last seq changes nothing`() {
        val port = RecPort()
        val state = attached(port, lastSeq = 5uL)
        assertEquals(Step(state), state.on(GroupEvent.Dir(5uL, SyncPlayDirective.Seek(9L))))
        assertEquals(Step(state), state.on(GroupEvent.Dir(4uL, SyncPlayDirective.Detach)))
    }

    @Test
    fun `a load in the lobby or while still launching launches the player for the newest item`() {
        val first = GroupExec(group = member).on(GroupEvent.Dir(1uL, loadDir("item-a")))
        val loadA = GroupLoad(1uL, "item-a", "entry-x", 0L)
        assertEquals(Stage.Launching(loadA), first.state.stage)
        assertEquals(listOf(GroupEffect.Launch(loadA)), first.effects)
        val second = first.state.on(GroupEvent.Dir(2uL, loadDir("item-b")))
        val loadB = GroupLoad(2uL, "item-b", "entry-x", 0L)
        assertEquals(Stage.Launching(loadB), second.state.stage)
        assertEquals("a launch that never attached is sent again", listOf(GroupEffect.Launch(loadB)), second.effects)
    }

    @Test
    fun `a launched player attaching loads the current item`() {
        val port = RecPort()
        val launching = GroupExec(lastSeq = 2uL, stage = Stage.Launching(load(2uL)))
        val step = launching.on(GroupEvent.PortAttached(port))
        assertEquals(Stage.Loading(load(2uL), port), step.state.stage)
        assertEquals(PortCall.Load(load(2uL)), (step.effects.first() as GroupEffect.Port).call)
    }

    /** docs/28 §1: the player reads the group and notices from the executor's state, so attaching only loads. */
    @Test
    fun `an attaching player only loads and the stored group and notices stay in the state`() {
        val port = RecPort()
        val notices = listOf(SyncPlayNotice.Joined)
        val launching = GroupExec(lastSeq = 2uL, group = member, notices = notices, stage = Stage.Launching(load(2uL)))
        val step = launching.on(GroupEvent.PortAttached(port))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Load(load(2uL)))), step.effects)
        assertEquals(member, step.state.group)
        assertEquals(notices, step.state.notices)
    }

    @Test
    fun `a load while loading or attached reloads the same port and drops the timer`() {
        val port = RecPort()
        val next = GroupLoad(3uL, "item-x", "entry-x", 0L)
        listOf(loading(port), attached(port, timed = Timed.Hold(2uL))).forEach { state ->
            val step = state.on(GroupEvent.Dir(3uL, loadDir()))
            assertEquals(Stage.Loading(next, port), step.state.stage)
            assertEquals(listOf(GroupEffect.Port(port, PortCall.Load(next))), step.effects)
            assertEquals(Step(step.state), step.state.on(GroupEvent.Due(2uL)))
        }
    }

    @Test
    fun `a pause already due pauses now and a later one arms its timer`() {
        val port = RecPort()
        val now = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Pause(freezeTicks = 7L, dueWallMs = NOW)))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Pause(7L))), now.effects)
        val later = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Pause(freezeTicks = null, dueWallMs = NOW + 300)))
        assertEquals(listOf(GroupEffect.Arm(3uL, 300L)), later.effects)
        val fired = later.state.on(GroupEvent.Due(3uL), NOW + 300)
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Pause(null))), fired.effects)
        assertEquals(Timed.None, (fired.state.stage as Stage.Attached).timed)
    }

    @Test
    fun `a late unpause plays ahead by the time it missed`() {
        val port = RecPort()
        val step = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Unpause(positionTicks = 1_000L, dueWallMs = NOW - 200)))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Play(1_000L + 200 * TICKS_PER_MS))), step.effects)
    }

    @Test
    fun `a future unpause arms and plays from its own position when its timer fires late`() {
        val port = RecPort()
        val armed = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Unpause(positionTicks = 1_000L, dueWallMs = NOW + 500)))
        assertEquals(listOf(GroupEffect.Arm(3uL, 500L)), armed.effects)
        val fired = armed.state.on(GroupEvent.Due(3uL), NOW + 520)
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Play(1_000L + 20 * TICKS_PER_MS))), fired.effects)
    }

    @Test
    fun `a seek lands exactly and paused and owes one SeekLanded`() {
        val port = RecPort()
        val seeking = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Seek(positionTicks = 50L)))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.SeekExact(50L, playing = false))), seeking.effects)
        val landed = seeking.state.on(GroupEvent.SeekLanded(1uL, landedTicks = 51L, latencyMs = 80L))
        assertEquals(listOf(input(SyncPlayInput.SeekLanded(3uL, 51L))), landed.effects)
        assertEquals(80L, landed.state.seekLatencyMs)
        assertEquals(Step(landed.state), landed.state.on(GroupEvent.SeekLanded(1uL, landedTicks = 51L, latencyMs = 80L)))
    }

    @Test
    fun `a seek voids a timer armed before it so the seeking player stays paused and owes its landing`() {
        val port = RecPort()
        listOf(Timed.Unpause(2uL, NOW + 400, 5L), Timed.Pause(2uL, 3L), Timed.Hold(2uL)).forEach { armed ->
            val seeking = attached(port, timed = armed).on(GroupEvent.Dir(3uL, SyncPlayDirective.Seek(positionTicks = 900L)))
            assertEquals(Stage.Attached(1uL, port, Motion.Seeking(3uL), Timed.None), seeking.state.stage)
            assertEquals("$armed fires nothing mid-seek", Step(seeking.state), seeking.state.on(GroupEvent.Due(2uL), NOW + 400))
        }
    }

    @Test
    fun `a correction seeks ahead by the measured seek latency and keeps playing`() {
        val port = RecPort()
        val step = attached(port, latencyMs = 120L).on(GroupEvent.Dir(3uL, SyncPlayDirective.Correct(positionTicks = 5_000L)))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.SeekExact(5_000L + 120 * TICKS_PER_MS, playing = true))), step.effects)
        assertEquals(Motion.Seeking(3uL), (step.state.stage as Stage.Attached).motion)
    }

    @Test
    fun `a hold pauses without a group input and resumes at its timer`() {
        val port = RecPort()
        val held = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.HoldFor(ms = 250uL)))
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Pause(null)), GroupEffect.Arm(3uL, 250L)), held.effects)
        val resumed = held.state.on(GroupEvent.Due(3uL), NOW + 250)
        assertEquals(listOf(GroupEffect.Port(port, PortCall.Play(null))), resumed.effects)
    }

    @Test
    fun `detach returns a loaded player to solo exactly once and ends`() {
        val port = RecPort()
        listOf(loading(port), attached(port)).forEach { state ->
            val step = state.on(GroupEvent.Dir(3uL, SyncPlayDirective.Detach))
            assertEquals(Stage.Gone, step.state.stage)
            assertEquals(listOf(GroupEffect.Port(port, PortCall.Solo), GroupEffect.End), step.effects)
        }
        val lobby = GroupExec().on(GroupEvent.Dir(1uL, SyncPlayDirective.Detach))
        assertEquals(listOf(GroupEffect.End), lobby.effects)
    }

    @Test
    fun `return to lobby closes the player and keeps the membership`() {
        val port = RecPort()
        val step = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.ReturnToLobby))
        assertEquals(Stage.Lobby, step.state.stage)
        assertEquals(listOf(GroupEffect.Port(port, PortCall.CloseToLobby)), step.effects)
        val launching = GroupExec(stage = Stage.Launching(load(1uL))).on(GroupEvent.Dir(2uL, SyncPlayDirective.ReturnToLobby))
        assertEquals(Stage.Lobby, launching.state.stage)
        assertEquals(emptyList<GroupEffect>(), launching.effects)
    }

    @Test
    fun `notices and snapshots are stored for an attached player without a port call`() {
        val port = RecPort()
        val notices = listOf(SyncPlayNotice.UserJoined("member-b"))
        val noticed = attached(port).on(GroupEvent.Notices(notices))
        assertEquals(notices, noticed.state.notices)
        assertEquals(emptyList<GroupEffect>(), noticed.effects)
        val paused = member.copy(phase = SyncPlayPhase.PAUSED)
        val read = noticed.state.on(GroupEvent.SnapshotRead(paused))
        assertEquals(paused, read.state.group)
        assertEquals(emptyList<GroupEffect>(), read.effects)
        val lobby = GroupExec().on(GroupEvent.SnapshotRead(paused))
        assertEquals(paused, lobby.state.group)
        assertEquals(emptyList<GroupEffect>(), lobby.effects)
    }

    @Test
    fun `a change reads the snapshot`() {
        assertEquals(listOf(GroupEffect.ReadSnapshot), GroupExec().on(GroupEvent.Changed).effects)
    }

    @Test
    fun `an idle snapshot an account change or an epoch move ends the membership`() {
        val port = RecPort()
        listOf(GroupEvent.SnapshotRead(SyncPlayGroup.Idle), GroupEvent.AccountChanged, GroupEvent.EpochMoved).forEach { event ->
            val step = attached(port).on(event)
            assertEquals(Stage.Gone, step.state.stage)
            assertEquals(listOf(GroupEffect.Port(port, PortCall.Solo), GroupEffect.End), step.effects)
        }
    }

    @Test
    fun `a second port is closed and the same port reattaching is a no-op`() {
        val port = RecPort()
        val other = RecPort()
        listOf(GroupExec(), loading(port), attached(port)).forEach { state ->
            assertEquals(Step(state, listOf(GroupEffect.Port(other, PortCall.Close))), state.on(GroupEvent.PortAttached(other)))
        }
        listOf(loading(port), attached(port)).forEach { state ->
            assertEquals(Step(state), state.on(GroupEvent.PortAttached(port)))
        }
    }

    @Test
    fun `a ready load reports ItemLoaded at its landed position and stalls report buffering`() {
        val port = RecPort()
        val ready = loading(port).on(GroupEvent.PlayerReady(1uL, positionTicks = 42L))
        assertEquals(Stage.Attached(1uL, port, Motion.Steady(NOW), Timed.None), ready.state.stage)
        assertEquals(listOf(input(SyncPlayInput.ItemLoaded("entry-1", 42L))), ready.effects)
        val stalled = ready.state.on(GroupEvent.PlayerBuffering(1uL))
        assertEquals(listOf(input(SyncPlayInput.BufferingStarted)), stalled.effects)
        val resumed = stalled.state.on(GroupEvent.PlayerReady(1uL, positionTicks = 60L))
        assertEquals(listOf(input(SyncPlayInput.BufferingEnded(60L))), resumed.effects)
        assertEquals(Step(resumed.state), resumed.state.on(GroupEvent.PlayerReady(1uL, positionTicks = 61L)))
    }

    @Test
    fun `position samples go out only while steady with no timer and at most every 1500 ms`() {
        val port = RecPort()
        val steady = attached(port, motion = Motion.Steady(NOW))
        assertEquals(Step(steady), steady.on(GroupEvent.Position(1uL, 9L), NOW + POSITION_SAMPLE_MS - 1))
        val sampled = steady.on(GroupEvent.Position(1uL, 9L), NOW + POSITION_SAMPLE_MS)
        assertEquals(listOf(input(SyncPlayInput.PositionSample(9L))), sampled.effects)
        assertEquals(Step(sampled.state), sampled.state.on(GroupEvent.Position(1uL, 9L), NOW + POSITION_SAMPLE_MS + 1))
        val timed = attached(port, motion = Motion.Steady(NOW), timed = Timed.Hold(2uL))
        assertEquals(Step(timed), timed.on(GroupEvent.Position(1uL, 9L), NOW + 10_000))
        val stalled = attached(port, motion = Motion.Stalled)
        assertEquals(Step(stalled), stalled.on(GroupEvent.Position(1uL, 9L), NOW + 10_000))
    }

    @Test
    fun `a fatal error or a close returns to the lobby and reports it while a recoverable one stalls`() {
        val port = RecPort()
        listOf(loading(port), attached(port)).forEach { state ->
            val fatal = state.on(GroupEvent.PlayerError(1uL, SyncPlaySeverity.FATAL))
            assertEquals(Step(state.copy(stage = Stage.Lobby), listOf(input(SyncPlayInput.PlaybackError(SyncPlaySeverity.FATAL)))), fatal)
            val closed = state.on(GroupEvent.PlayerClosed(1uL))
            assertEquals(Step(state.copy(stage = Stage.Lobby), listOf(input(SyncPlayInput.PlayerClosed))), closed)
        }
        val recoverable = attached(port).on(GroupEvent.PlayerError(1uL, SyncPlaySeverity.RECOVERABLE))
        assertEquals(Motion.Stalled, (recoverable.state.stage as Stage.Attached).motion)
        assertEquals(listOf(input(SyncPlayInput.PlaybackError(SyncPlaySeverity.RECOVERABLE))), recoverable.effects)
        assertEquals(listOf(input(SyncPlayInput.PlayerEnded)), attached(port).on(GroupEvent.PlayerEnded(1uL)).effects)
    }

    @Test
    fun `user transport during a load or playback goes to the group`() {
        val port = RecPort()
        listOf(loading(port), attached(port)).forEach { state ->
            assertEquals(
                Step(state, listOf(input(SyncPlayInput.Seek(77L)))),
                state.on(GroupEvent.UserTransport(1uL, SyncPlayInput.Seek(77L))),
            )
        }
    }

    @Test
    fun `a stale tag or a timer from an older directive changes nothing`() {
        val port = RecPort()
        val state = attached(port, timed = Timed.Pause(2uL, null))
        listOf(
            GroupEvent.PlayerReady(0uL, 1L),
            GroupEvent.PlayerClosed(0uL),
            GroupEvent.PlayerError(0uL, SyncPlaySeverity.FATAL),
            GroupEvent.UserTransport(0uL, SyncPlayInput.Pause),
            GroupEvent.Due(1uL),
        ).forEach { assertEquals(Step(state), state.on(it)) }
        val launching = GroupExec(stage = Stage.Launching(load(1uL)))
        assertEquals(Step(launching), launching.on(GroupEvent.PlayerClosed(1uL)))
    }

    @Test
    fun `leave reaches the core from every live stage and gone ignores everything`() {
        val port = RecPort()
        listOf(GroupExec(), GroupExec(stage = Stage.Launching(load(1uL))), loading(port), attached(port)).forEach { state ->
            assertEquals(Step(state, listOf(input(SyncPlayInput.Leave))), state.on(GroupEvent.UserLeave))
        }
        val gone = GroupExec(stage = Stage.Gone)
        listOf(GroupEvent.UserLeave, GroupEvent.Dir(9uL, loadDir()), GroupEvent.PortAttached(port))
            .forEach { assertEquals(Step(gone), gone.on(it)) }
    }

    @Test
    fun `an unpause already due drops an armed pause so its late timer is inert`() {
        val port = RecPort()
        val armed = attached(port).on(GroupEvent.Dir(3uL, SyncPlayDirective.Pause(freezeTicks = null, dueWallMs = NOW + 500)))
        val playing = armed.state.on(GroupEvent.Dir(4uL, SyncPlayDirective.Unpause(positionTicks = 1_000L, dueWallMs = NOW - 10)))
        assertEquals(Timed.None, (playing.state.stage as Stage.Attached).timed)
        assertEquals(Step(playing.state), playing.state.on(GroupEvent.Due(3uL), NOW + 500))
    }

    @Test
    fun `notices from two updates before they are shown are all kept`() {
        val port = RecPort()
        val joined = SyncPlayNotice.UserJoined("member-b")
        val first = attached(port).on(GroupEvent.Notices(listOf(SyncPlayNotice.Joined)))
        val second = first.state.on(GroupEvent.Notices(listOf(joined)))
        assertEquals(listOf(SyncPlayNotice.Joined, joined), second.state.notices)
        assertEquals(emptyList<GroupEffect>(), second.effects)
    }

    // -- S5 executor extensions (S5 UI design, Owners E1-E4) ----------------------

    @Test
    fun `the player showing a notice drops only the oldest live one and nothing else changes`() {
        val port = RecPort()
        val joined = SyncPlayNotice.UserJoined("member-b")
        val noticed = attached(port).copy(notices = listOf(SyncPlayNotice.Joined, joined))
        assertEquals(Step(noticed.copy(notices = listOf(joined), dropped = 1)), noticed.on(GroupEvent.NoticesShown(NoticeSurface.Player)))
    }

    @Test
    fun `a notice shown by the surface not on screen changes nothing`() {
        val port = RecPort()
        val inPlayer = attached(port).copy(notices = listOf(SyncPlayNotice.Joined))
        assertEquals(Step(inPlayer), inPlayer.on(GroupEvent.NoticesShown(NoticeSurface.Lobby)))
        listOf(GroupExec(group = member), GroupExec(stage = Stage.Launching(load(1uL)))).forEach { lobby ->
            val noticed = lobby.copy(notices = listOf(SyncPlayNotice.Joined))
            assertEquals(Step(noticed), noticed.on(GroupEvent.NoticesShown(NoticeSurface.Player)))
            assertEquals(Step(lobby.copy(dropped = 1)), noticed.on(GroupEvent.NoticesShown(NoticeSurface.Lobby)))
        }
    }

    @Test
    fun `a terminal notice is never dropped by a notice shown`() {
        val ended = SyncPlayNotice.GroupEnded(SyncPlayEndCause.REMOVED)
        val onlyTerminal = GroupExec(group = member, notices = listOf(ended))
        assertEquals(Step(onlyTerminal), onlyTerminal.on(GroupEvent.NoticesShown(NoticeSurface.Lobby)))
        val mixed = GroupExec(group = member, notices = listOf(ended, SyncPlayNotice.Reconnecting))
        assertEquals(Step(mixed.copy(notices = listOf(ended), dropped = 1)), mixed.on(GroupEvent.NoticesShown(NoticeSurface.Lobby)))
    }

    @Test
    fun `the host's pick in the lobby sends the item from the chosen position`() {
        val step = GroupExec(group = member).on(GroupEvent.UserPick("item-a", startTicks = 600L))
        assertEquals(listOf(input(SyncPlayInput.PickItem(listOf("item-a"), 0, 600L))), step.effects)
        val finished = GroupExec(group = member.copy(stage = SyncPlayStage.FINISHED))
        assertEquals("a host picks again once the queue ended", step.effects, finished.on(GroupEvent.UserPick("item-a", 600L)).effects)
    }

    @Test
    fun `a pick outside the lobby or by anyone but a connected host in the lobby changes nothing`() {
        val port = RecPort()
        listOf(
            GroupExec(group = member.copy(role = SyncPlayRole.GUEST)),
            GroupExec(group = member.copy(connected = false)),
            GroupExec(group = member.copy(stage = SyncPlayStage.PLAYING)),
            GroupExec(group = SyncPlayGroup.Starting(name = "member-a's group")),
            GroupExec(group = member, stage = Stage.Launching(load(1uL))),
            loading(port),
            attached(port),
        ).forEach { assertEquals(Step(it), it.on(GroupEvent.UserPick("item-a", 0L))) }
    }

    @Test
    fun `the room name is kept from the first starting snapshot and never changes`() {
        val started = GroupExec().on(GroupEvent.SnapshotRead(SyncPlayGroup.Starting(name = "member-a's group"))).state
        assertEquals("member-a's group", started.name)
        val asMember = started.on(GroupEvent.SnapshotRead(member)).state
        assertEquals("member-a's group", asMember.name)
        assertEquals("member-a's group", asMember.on(GroupEvent.SnapshotRead(SyncPlayGroup.Starting(name = "member-b's group"))).state.name)
        val joining = GroupExec().on(GroupEvent.SnapshotRead(SyncPlayGroup.Starting(name = null))).state
        assertNull(joining.name)
    }

    @Test
    fun `a step's notices come before its directives and the change`() {
        val update = SyncPlayUpdate(
            listOf(SyncPlayDirectiveAt(4uL, SyncPlayDirective.Detach)),
            listOf(SyncPlayNotice.HostLeft),
            changed = true,
        )
        assertEquals(
            listOf(GroupEvent.Notices(listOf(SyncPlayNotice.HostLeft)), GroupEvent.Dir(4uL, SyncPlayDirective.Detach), GroupEvent.Changed),
            expand(update),
        )
    }

    // -- Sequence sweep (docs/28 §9) --------------------------------------------

    @Test
    fun `every sequence of four keeps the invariants`() {
        val a = RecPort()
        val b = RecPort()
        val seeds = listOf(
            GroupExec(group = member),
            GroupExec(group = SyncPlayGroup.Starting(name = null), notices = listOf(SyncPlayNotice.GroupEnded(SyncPlayEndCause.LOST), SyncPlayNotice.Joined)),
            GroupExec(lastSeq = 1uL, stage = Stage.Launching(load(1uL))),
            loading(a),
            attached(a),
            attached(a, motion = Motion.Stalled, timed = Timed.Pause(2uL, null)),
            attached(a, motion = Motion.Seeking(2uL)),
            attached(a, timed = Timed.Unpause(2uL, NOW + 900, 5L)),
            GroupExec(group = guest("member-a", "member-b")),
        )
        val letters = sweepLetters(a, b)
        var steps = 0L
        fun walk(state: GroupExec, now: Long, depth: Int, solos: Int, path: List<String>) {
            if (depth == 4) return
            for ((name, letter) in letters) {
                val event = letter(state, now)
                val step = reduce(state, event, now)
                steps++
                val trail = path + name
                val soloCount = solos + step.effects.count { it is GroupEffect.Port && it.call == PortCall.Solo }
                checkInvariants(state, event, step, soloCount, trail)
                walk(step.state, now + 700, depth + 1, soloCount, trail)
            }
        }
        seeds.forEach { walk(it, NOW, 0, 0, emptyList()) }
        assertTrue(steps > seeds.size.toLong() * letters.size * letters.size * letters.size * letters.size)
    }

    private fun tagOf(stage: Stage): ULong? = when (stage) {
        is Stage.Loading -> stage.load.seq
        is Stage.Attached -> stage.loadSeq
        else -> null
    }

    private fun portOf(stage: Stage): GroupPort? = when (stage) {
        is Stage.Loading -> stage.port
        is Stage.Attached -> stage.port
        else -> null
    }

    private fun timedSeq(stage: Stage): ULong? = when (val t = (stage as? Stage.Attached)?.timed) {
        is Timed.Pause -> t.seq
        is Timed.Unpause -> t.seq
        is Timed.Hold -> t.seq
        Timed.None, null -> null
    }

    private fun sweepLetters(a: GroupPort, b: GroupPort): List<Pair<String, (GroupExec, Long) -> GroupEvent>> {
        fun dir(d: SyncPlayDirective): (GroupExec, Long) -> GroupEvent = { s, _ -> GroupEvent.Dir(s.lastSeq + 1uL, d) }
        fun cur(make: (ULong) -> GroupEvent): (GroupExec, Long) -> GroupEvent = { s, _ -> make(tagOf(s.stage) ?: 1uL) }
        return listOf(
            "load" to dir(loadDir()),
            "staleDir" to { s, _ -> GroupEvent.Dir(s.lastSeq, SyncPlayDirective.Detach) },
            "pauseNow" to { s, now -> GroupEvent.Dir(s.lastSeq + 1uL, SyncPlayDirective.Pause(null, now)) },
            "pauseLater" to { s, now -> GroupEvent.Dir(s.lastSeq + 1uL, SyncPlayDirective.Pause(3L, now + 400)) },
            "unpauseNow" to { s, now -> GroupEvent.Dir(s.lastSeq + 1uL, SyncPlayDirective.Unpause(5L, now - 100)) },
            "unpauseLater" to { s, now -> GroupEvent.Dir(s.lastSeq + 1uL, SyncPlayDirective.Unpause(5L, now + 400)) },
            "seek" to dir(SyncPlayDirective.Seek(8L)),
            "correct" to dir(SyncPlayDirective.Correct(8L)),
            "hold" to dir(SyncPlayDirective.HoldFor(300uL)),
            "detach" to dir(SyncPlayDirective.Detach),
            "toLobby" to dir(SyncPlayDirective.ReturnToLobby),
            "notices" to { _, _ -> GroupEvent.Notices(listOf(SyncPlayNotice.Joined)) },
            "noticesTerminal" to { _, _ -> GroupEvent.Notices(listOf(SyncPlayNotice.HostLeft)) },
            "shownLobby" to { _, _ -> GroupEvent.NoticesShown(NoticeSurface.Lobby) },
            "shownPlayer" to { _, _ -> GroupEvent.NoticesShown(NoticeSurface.Player) },
            "userPick" to { _, _ -> GroupEvent.UserPick("item-p", 300L) },
            "snapshot" to { _, _ -> GroupEvent.SnapshotRead(member) },
            "snapshotStarting" to { _, _ -> GroupEvent.SnapshotRead(SyncPlayGroup.Starting(name = "member-a's group")) },
            "snapshotGuest" to { _, _ -> GroupEvent.SnapshotRead(guest("member-a", "member-b")) },
            "snapshotFinished" to { _, _ -> GroupEvent.SnapshotRead(member.copy(stage = SyncPlayStage.FINISHED)) },
            "changed" to { _, _ -> GroupEvent.Changed },
            "idle" to { _, _ -> GroupEvent.SnapshotRead(SyncPlayGroup.Idle) },
            "accountChanged" to { _, _ -> GroupEvent.AccountChanged },
            "epochMoved" to { _, _ -> GroupEvent.EpochMoved },
            "attachA" to { _, _ -> GroupEvent.PortAttached(a) },
            "attachB" to { _, _ -> GroupEvent.PortAttached(b) },
            "ready" to cur { GroupEvent.PlayerReady(it, 4L) },
            "buffering" to cur { GroupEvent.PlayerBuffering(it) },
            "seekLanded" to cur { GroupEvent.SeekLanded(it, 8L, 60L) },
            "position" to cur { GroupEvent.Position(it, 6L) },
            "ended" to cur { GroupEvent.PlayerEnded(it) },
            "errorRecoverable" to cur { GroupEvent.PlayerError(it, SyncPlaySeverity.RECOVERABLE) },
            "errorFatal" to cur { GroupEvent.PlayerError(it, SyncPlaySeverity.FATAL) },
            "closed" to cur { GroupEvent.PlayerClosed(it) },
            "readyOld" to { s, _ -> GroupEvent.PlayerReady((tagOf(s.stage) ?: 1uL) + 100uL, 4L) },
            "transport" to cur { GroupEvent.UserTransport(it, SyncPlayInput.Pause) },
            "due" to { s, _ -> GroupEvent.Due(timedSeq(s.stage) ?: s.lastSeq) },
            "dueOther" to { s, _ -> GroupEvent.Due((timedSeq(s.stage) ?: s.lastSeq) + 100uL) },
            "leave" to { _, _ -> GroupEvent.UserLeave },
        )
    }

    /** Fails with the event path that broke [rule]; the message is built only on failure. */
    private fun ensure(ok: Boolean, rule: String, path: List<String>) {
        if (!ok) throw AssertionError("invariant $rule broken after: ${path.joinToString(" ")}")
    }

    /** S4 design §5 invariants 1-8 and 10 for one step; 9's ordering half is the owner test's. S5 7, 8 and 3 last. */
    private fun checkInvariants(before: GroupExec, event: GroupEvent, step: Step, solos: Int, path: List<String>) {
        val inert = step == Step(before)
        val portBefore = portOf(before.stage)
        ensure(before.name == null || step.state.name == before.name, "S5-7 name never changes once set", path)
        // docs/28 §1: the core decides host-left, so a snapshot never sends an input.
        ensure(event !is GroupEvent.SnapshotRead || step.effects.none { it is GroupEffect.Input }, "a snapshot sends nothing", path)
        if (event is GroupEvent.NoticesShown) {
            val dropped = before.notices.size - step.state.notices.size
            ensure(step.effects.isEmpty() && step.state.copy(notices = before.notices, dropped = before.dropped) == before, "S5-8 only notices change", path)
            ensure(step.state.dropped == before.dropped + dropped, "R1-B2 the drop count rises by the notices dropped", path)
            ensure(step.state.notices.filter(::isTerminal) == before.notices.filter(::isTerminal), "S5-8 terminal kept", path)
            ensure(dropped == 0 || (dropped == 1 && noticeSurface(before.stage) == event.by), "S5-8 one live notice by its surface", path)
            ensure(dropped == 1 || liveNotice(before.notices) == null || noticeSurface(before.stage) != event.by, "S5-8 a live notice is dropped", path)
        } else {
            ensure(step.state.dropped == before.dropped, "R1-B2 only a notice shown counts a drop", path)
            ensure(before.notices.filter(::isTerminal).let { it == step.state.notices.filter(::isTerminal).take(it.size) }, "S5-8 terminal kept", path)
        }
        val picks = step.effects.count { it is GroupEffect.Input && it.input is SyncPlayInput.PickItem }
        ensure(picks == 0 || (event is GroupEvent.UserPick && before.stage == Stage.Lobby && startAllowed(before.group)), "S5-3 pick only by a host in the lobby", path)
        ensure(before.stage != Stage.Gone || inert, "2 gone is terminal", path)
        ensure(event !is GroupEvent.Dir || event.seq > before.lastSeq || inert, "1 old seq inert", path)
        ensure(step.state.lastSeq >= before.lastSeq, "1 lastSeq monotone", path)
        ensure(event !is GroupEvent.Tagged || event.tag == tagOf(before.stage) || inert, "3 stale tag inert", path)
        ensure(event !is GroupEvent.Due || event.seq == timedSeq(before.stage) || inert, "3 stale timer inert", path)
        val timing = event is GroupEvent.Dir && event.seq > before.lastSeq && step.state.stage is Stage.Attached &&
            (event.directive is SyncPlayDirective.Pause || event.directive is SyncPlayDirective.Unpause || event.directive is SyncPlayDirective.HoldFor)
        ensure(!timing || timedSeq(step.state.stage).let { it == null || it == event.seq }, "11 timer belongs to the latest timing directive", path)
        val seek = event is GroupEvent.Dir && event.seq > before.lastSeq && event.directive is SyncPlayDirective.Seek && step.state.stage is Stage.Attached
        ensure(!seek || timedSeq(step.state.stage) == null, "12 a seek voids any armed timer", path)
        step.effects.forEach { effect ->
            if (effect is GroupEffect.Input) {
                when (val i = effect.input) {
                    SyncPlayInput.PlayerClosed ->
                        ensure(event is GroupEvent.PlayerClosed && event.tag == tagOf(before.stage), "4 closed from current", path)
                    is SyncPlayInput.ItemLoaded -> ensure(before.stage is Stage.Loading, "5 ItemLoaded owed", path)
                    is SyncPlayInput.SeekLanded ->
                        ensure((before.stage as? Stage.Attached)?.motion == Motion.Seeking(i.seq), "5 SeekLanded owed", path)
                    else -> Unit
                }
            }
            if (effect is GroupEffect.Port) {
                val foreignClose = event is GroupEvent.PortAttached && event.port === effect.port && effect.port !== portBefore
                val own = effect.port === portBefore || effect.port === portOf(step.state.stage)
                ensure(if (effect.call == PortCall.Close) foreignClose else own, "6 own port", path)
            }
            ensure(effect !is GroupEffect.Arm || step.state.stage is Stage.Attached, "10 only attached arms", path)
            ensure(effect !is GroupEffect.Launch || before.stage == Stage.Lobby || before.stage is Stage.Launching, "launch only from lobby or launching", path)
        }
        ensure(event !is GroupEvent.Tagged || step.effects.all { it is GroupEffect.Input }, "7 player emits inputs", path)
        ensure(event !is GroupEvent.PortAttached || step.effects.all { it is GroupEffect.Port }, "7 attach emits port calls", path)
        val ended = step.effects.count { it == GroupEffect.End }
        if (before.stage != Stage.Gone && step.state.stage == Stage.Gone) {
            ensure(ended == 1 && step.effects.last() == GroupEffect.End, "9 ends once and last", path)
            val solo = step.effects.count { it is GroupEffect.Port && it.call == PortCall.Solo }
            ensure(solo == if (portBefore != null) 1 else 0, "8 one solo per port", path)
        } else {
            ensure(ended == 0, "9 end only into gone", path)
        }
        ensure(solos <= 1, "8 solo at most once", path)
    }

    // -- Owner (docs/28 §4) -------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Rig(private val scope: TestScope) {
        val gateway = FakeCoreGateway().apply { syncPlayGroup = SyncPlayGroup.Starting(name = "member-a's group") }
        val launched = mutableListOf<GroupLoad>()
        val port = RecPort()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val build: (ULong) -> SyncPlayExecutor = { epoch ->
            SyncPlayExecutor(gateway, epoch, { launched += it }, scope.backgroundScope, dispatcher) { NOW + scope.testScheduler.currentTime }
        }
    }

    private fun update(
        vararg directives: Pair<ULong, SyncPlayDirective>,
        notices: List<SyncPlayNotice> = emptyList(),
        changed: Boolean = false,
    ) = SyncPlayUpdate(directives.map { (seq, d) -> SyncPlayDirectiveAt(seq, d) }, notices, changed)

    private fun GroupEntry.executor() = (this as GroupEntry.Started).executor

    /** Ends the executor whatever the body did, so the process slot is empty for the next test. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ownerTest(body: suspend TestScope.(Rig, SyncPlayExecutor) -> Unit) = runTest {
        val rig = Rig(this)
        val executor = SyncPlayExecutor.create(1uL, rig.gateway, rig.build).executor()
        try {
            advanceUntilIdle()
            body(rig, executor)
        } finally {
            executor.send(GroupEvent.EpochMoved)
            SyncPlayExecutor.onEpochMoved()
            advanceUntilIdle()
        }
    }

    /** Loads the group's first item into [Rig.port]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.attach(rig: Rig, executor: SyncPlayExecutor) {
        rig.gateway.syncPlayListener!!.onStep(update(1uL to loadDir()))
        advanceUntilIdle()
        executor.send(GroupEvent.PortAttached(rig.port))
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `inputs reach the core in event order`() = ownerTest { rig, executor ->
        attach(rig, executor)
        val held = CompletableDeferred<Unit>()
        rig.gateway.syncPlayInputGatesByCall[0] = held
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Pause))
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Seek(5L)))
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Next))
        advanceUntilIdle()
        assertEquals(emptyList<SyncPlayInput>(), rig.gateway.syncPlayInputs)
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SyncPlayInput.Pause, SyncPlayInput.Seek(5L), SyncPlayInput.Next), rig.gateway.syncPlayInputs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onStep calls no export on the callback thread`() = ownerTest { rig, _ ->
        val before = rig.gateway.syncPlayCalls
        rig.gateway.syncPlayListener!!.onStep(update(1uL to loadDir(), changed = true))
        assertEquals(before, rig.gateway.syncPlayCalls)
        assertEquals(emptyList<GroupLoad>(), rig.launched)
        advanceUntilIdle()
        assertEquals(before + 1, rig.gateway.syncPlayCalls)
        assertEquals(listOf(GroupLoad(1uL, "item-x", "entry-x", 0L)), rig.launched)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a group that ends in one step keeps its GroupEnded notice after the executor ends`() = ownerTest { rig, executor ->
        val ended = SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE)
        rig.gateway.syncPlayListener!!.onStep(update(1uL to SyncPlayDirective.Detach, notices = listOf(ended)))
        advanceUntilIdle()
        assertEquals(Stage.Gone, executor.state.value.stage)
        assertEquals(listOf<SyncPlayNotice>(ended), executor.state.value.notices)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a created group keeps its room name after the snapshot turns to Member`() = ownerTest { rig, executor ->
        assertEquals("member-a's group", executor.state.value.name)
        rig.gateway.syncPlayGroup = member
        rig.gateway.syncPlayListener!!.onStep(update(changed = true))
        advanceUntilIdle()
        assertEquals(member, executor.state.value.group)
        assertEquals("member-a's group", executor.state.value.name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `End cancels every executor job`() = ownerTest { rig, executor ->
        attach(rig, executor)
        executor.send(GroupEvent.PlayerReady(1uL, 0L))
        advanceUntilIdle()
        rig.gateway.syncPlayInputs.clear()
        rig.gateway.syncPlayInputGatesByCall[1] = CompletableDeferred()
        rig.gateway.syncPlayListener!!.onStep(update(2uL to SyncPlayDirective.Pause(null, NOW + testScheduler.currentTime + 1_000)))
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Unpause))
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Next))
        runCurrent()
        assertEquals("the armed pause has not fired", PortCall.Load(GroupLoad(1uL, "item-x", "entry-x", 0L)), rig.port.calls.first())
        assertEquals("nothing but the load reached the port", 1, rig.port.calls.size)
        rig.gateway.syncPlayListener!!.onStep(update(3uL to SyncPlayDirective.Detach))
        runCurrent()
        assertNull(SyncPlayExecutor.current.value)
        assertEquals(PortCall.Solo, rig.port.calls.last())
        rig.gateway.syncPlayInputGatesByCall.getValue(1).complete(Unit)
        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertEquals(emptyList<SyncPlayInput>(), rig.gateway.syncPlayInputs)
        assertEquals(PortCall.Solo, rig.port.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a launch that never attached is retried by the next load`() = ownerTest { rig, _ ->
        rig.gateway.syncPlayListener!!.onStep(update(1uL to loadDir("item-a")))
        advanceUntilIdle()
        rig.gateway.syncPlayListener!!.onStep(update(2uL to loadDir("item-b")))
        advanceUntilIdle()
        assertEquals(listOf("item-a", "item-b"), rig.launched.map { it.itemId })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a refused create or join names its outcome and installs no executor`() = runTest {
        val rig = Rig(this)
        rig.gateway.syncPlayGroup = SyncPlayGroup.Idle
        assertEquals(GroupEntry.Refused, SyncPlayExecutor.create(1uL, rig.gateway, rig.build))
        rig.gateway.syncPlayError = CoreException.AccountChanged()
        assertEquals(GroupEntry.Refused, SyncPlayExecutor.create(1uL, rig.gateway, rig.build))
        assertNull(SyncPlayExecutor.current.value)
        runCurrent()
        assertEquals(emptyList<SyncPlayInput>(), rig.gateway.syncPlayInputs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a room join asks the core for that room`() = runTest {
        val rig = Rig(this)
        rig.gateway.syncPlayGroup = SyncPlayGroup.Starting(name = null)
        val joined = SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group"), rig.build).executor()
        assertEquals(listOf("room-1"), rig.gateway.syncPlayRoomJoins)
        assertSame(joined, SyncPlayExecutor.current.value)
        runCurrent()
        assertEquals("the listed room name is the guest's", "member-a's group", joined.state.value.name)
        joined.send(GroupEvent.EpochMoved)
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a room name the core already has wins over the listed one`() = runTest {
        val rig = Rig(this)
        rig.gateway.syncPlayGroup = SyncPlayGroup.Starting(name = "member-b's group")
        val joined = SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group"), rig.build).executor()
        runCurrent()
        assertEquals("member-b's group", joined.state.value.name)
        joined.send(GroupEvent.EpochMoved)
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `only a group the core is starting for us is installed and an unowned one is left`() = runTest {
        val rig = Rig(this)
        rig.gateway.syncPlayGroup = SyncPlayGroup.Leaving(SyncPlayLeaveReason.USER)
        assertEquals(GroupEntry.Refused, SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group"), rig.build))
        runCurrent()
        assertEquals(emptyList<SyncPlayInput>(), rig.gateway.syncPlayInputs)
        rig.gateway.syncPlayGroup = member
        assertEquals(GroupEntry.Refused, SyncPlayExecutor.create(1uL, rig.gateway, rig.build))
        assertEquals(GroupEntry.Refused, SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group"), rig.build))
        assertNull(SyncPlayExecutor.current.value)
        runCurrent()
        assertEquals(listOf<SyncPlayInput>(SyncPlayInput.Leave, SyncPlayInput.Leave), rig.gateway.syncPlayInputs)
        rig.gateway.syncPlayGroup = SyncPlayGroup.Starting(name = null)
        val joined = SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group"), rig.build).executor()
        assertSame(joined, SyncPlayExecutor.current.value)
        joined.send(GroupEvent.EpochMoved)
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a caller cancelled after the create went out still leaves the group`() = runTest {
        val rig = Rig(this)
        rig.gateway.syncPlayEntryGate = CompletableDeferred()
        val entering = launch { SyncPlayExecutor.create(1uL, rig.gateway, rig.build) }
        runCurrent()
        assertEquals(1, rig.gateway.syncPlayCreatedNames.size)
        entering.cancel()
        advanceUntilIdle()
        runCurrent()
        assertNull(SyncPlayExecutor.current.value)
        assertEquals(listOf<SyncPlayInput>(SyncPlayInput.Leave), rig.gateway.syncPlayInputs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a create cancelled while its room name is read posts nothing and the next create starts its own group`() = runTest {
        val rig = Rig(this)
        val naming = CompletableDeferred<Unit>()
        rig.gateway.syncPlayRoomNameGate = naming
        try {
            val cancelled = launch { SyncPlayExecutor.create(1uL, rig.gateway, rig.build) }
            runCurrent()
            cancelled.cancel()
            runCurrent()
            val next = async { SyncPlayExecutor.create(1uL, rig.gateway, rig.build) }
            runCurrent()
            val namesWhileHeld = rig.gateway.syncPlayRoomNameCalls
            naming.complete(Unit)
            advanceUntilIdle()
            val started = next.await().executor()
            assertEquals("the next create's name read waits on no other create", 2, namesWhileHeld)
            assertEquals("only the next create posts", listOf("member-a's group"), rig.gateway.syncPlayCreatedNames)
            assertSame(started.listener, rig.gateway.syncPlayListener)
            assertSame(started, SyncPlayExecutor.current.value)
            assertEquals("the cancelled create left no group to leave", emptyList<SyncPlayInput>(), rig.gateway.syncPlayInputs)
        } finally {
            naming.complete(Unit)
            SyncPlayExecutor.onEpochMoved()
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a running executor is returned instead of a second entry`() = ownerTest { rig, executor ->
        val calls = rig.gateway.syncPlayCalls
        assertSame(executor, SyncPlayExecutor.current.value)
        var built = 0
        assertEquals(GroupEntry.Started(executor), SyncPlayExecutor.join(1uL, JoinTarget("room-1", "member-a's group")) { built++; rig.build(it) })
        assertEquals(calls, rig.gateway.syncPlayCalls)
        assertEquals("a running membership builds nothing", 0, built)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an executor of another account is ended before a fresh entry`() = ownerTest { rig, executor ->
        val fresh = SyncPlayExecutor.create(2uL, rig.gateway, rig.build).executor()
        advanceUntilIdle()
        assertEquals(Stage.Gone, executor.state.value.stage)
        assertEquals(2uL, fresh.accountEpoch)
        assertSame(fresh, SyncPlayExecutor.current.value)
    }

    // -- Exit hooks (docs/28 §4) --------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `app exit sends one Leave in a group and none when idle`() = runTest {
        val rig = Rig(this)
        SyncPlayExecutor.onAppExit()
        runCurrent()
        assertEquals("no group, no call", 0, rig.gateway.syncPlayCalls)

        val executor = SyncPlayExecutor.create(1uL, rig.gateway, rig.build).executor()
        try {
            advanceUntilIdle()
            SyncPlayExecutor.onAppExit()
            advanceUntilIdle()
            runCurrent()
            assertEquals(listOf<SyncPlayInput>(SyncPlayInput.Leave), rig.gateway.syncPlayInputs)
        } finally {
            executor.send(GroupEvent.EpochMoved)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `app exit leaves after the inputs already queued`() = ownerTest { rig, executor ->
        attach(rig, executor)
        val held = CompletableDeferred<Unit>()
        rig.gateway.syncPlayInputGatesByCall[0] = held
        executor.send(GroupEvent.UserTransport(1uL, SyncPlayInput.Pause))
        runCurrent()
        SyncPlayExecutor.onAppExit()
        runCurrent()
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SyncPlayInput.Pause, SyncPlayInput.Leave), rig.gateway.syncPlayInputs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an epoch move ends the running executor`() = ownerTest { _, executor ->
        SyncPlayExecutor.onEpochMoved()
        advanceUntilIdle()
        assertEquals(Stage.Gone, executor.state.value.stage)
        assertNull(SyncPlayExecutor.current.value)
    }
}
