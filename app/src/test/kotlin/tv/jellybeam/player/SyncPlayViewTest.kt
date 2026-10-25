package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.SyncPlayEndCause
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayLeaveReason
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayStage

private val host = SyncPlayGroup.Member(
    role = SyncPlayRole.HOST,
    stage = SyncPlayStage.LOBBY,
    host = SyncPlayHost.Me,
    participants = listOf("member-a"),
    phase = SyncPlayPhase.WAITING,
    connected = true,
    promptShown = false,
)

private val ended = SyncPlayNotice.GroupEnded(SyncPlayEndCause.AUTH)

private val terminal = listOf(
    SyncPlayNotice.StartFailed, SyncPlayNotice.NoSuchGroup, SyncPlayNotice.NoAccess, ended,
    SyncPlayNotice.HostLeft, SyncPlayNotice.CreateRefused, SyncPlayNotice.JoinRefused,
)

private val live = listOf(
    SyncPlayNotice.Joined, SyncPlayNotice.UserJoined("member-b"), SyncPlayNotice.UserLeft("member-b"), SyncPlayNotice.Reconnecting,
)

class SyncPlayViewTest {

    @Test
    fun `the lobby shows notices until a player loads and a gone group shows none`() {
        val port = GroupPort { }
        val load = GroupLoad(1uL, "item-a", "entry-a", 0L)
        assertEquals(NoticeSurface.Lobby, noticeSurface(Stage.Lobby))
        assertEquals(NoticeSurface.Lobby, noticeSurface(Stage.Launching(load)))
        assertEquals(NoticeSurface.Player, noticeSurface(Stage.Loading(load, port)))
        assertEquals(NoticeSurface.Player, noticeSurface(Stage.Attached(1uL, port, Motion.Stalled, Timed.None)))
        assertNull(noticeSurface(Stage.Gone))
    }

    @Test
    fun `only a connected host in the lobby may start, before a title or after the queue ended`() {
        assertTrue(startAllowed(host))
        assertTrue(startAllowed(host.copy(stage = SyncPlayStage.FINISHED)))
        assertFalse(startAllowed(host.copy(role = SyncPlayRole.GUEST)))
        assertFalse(startAllowed(host.copy(stage = SyncPlayStage.PLAYING)))
        assertFalse(startAllowed(host.copy(connected = false)))
        listOf(SyncPlayGroup.Idle, SyncPlayGroup.Starting("member-a's group"), SyncPlayGroup.Leaving(SyncPlayLeaveReason.USER))
            .forEach { assertFalse(startAllowed(it)) }
    }

    @Test
    fun `entry failures and group endings are terminal and presence notices are live`() {
        terminal.forEach { assertTrue("$it", isTerminal(it)) }
        live.forEach { assertFalse("$it", isTerminal(it)) }
    }

    @Test
    fun `the live notice is the oldest live one and terminal notices are never it`() {
        assertNull(liveNotice(emptyList()))
        assertNull(liveNotice(listOf(ended)))
        assertEquals(SyncPlayNotice.Reconnecting, liveNotice(listOf(ended, SyncPlayNotice.Reconnecting, SyncPlayNotice.Joined)))
    }

    @Test
    fun `an entry failure an ending or nothing decide the outcome by the first terminal notice`() {
        listOf(SyncPlayNotice.StartFailed, SyncPlayNotice.NoSuchGroup, SyncPlayNotice.NoAccess)
            .forEach { assertEquals(GroupOutcome.EntryFailed(it), outcome(listOf(SyncPlayNotice.Joined, it))) }
        assertEquals(GroupOutcome.Ended(ended), outcome(listOf(ended)))
        assertEquals(GroupOutcome.HostLeft, outcome(listOf(SyncPlayNotice.Reconnecting, SyncPlayNotice.HostLeft, ended)))
        assertEquals(GroupOutcome.Silent, outcome(live))
        assertEquals(GroupOutcome.Silent, outcome(emptyList()))
    }

    @Test
    fun `a late joiner is in transit only as a guest in a running room's lobby before any directive`() {
        val guest = host.copy(role = SyncPlayRole.GUEST, host = SyncPlayHost.Known("member-a"), phase = SyncPlayPhase.PLAYING)
        assertTrue(inTransit(GroupExec(group = guest)))
        assertFalse("the host never waits for its own title", inTransit(GroupExec(group = guest.copy(role = SyncPlayRole.HOST))))
        assertFalse("an idle room is a plain lobby", inTransit(GroupExec(group = guest.copy(phase = SyncPlayPhase.IDLE))))
        assertFalse("the Load arrived", inTransit(GroupExec(lastSeq = 1uL, group = guest)))
        assertFalse("already playing", inTransit(GroupExec(group = guest.copy(stage = SyncPlayStage.PLAYING))))
        assertFalse("after the queue ended", inTransit(GroupExec(group = guest.copy(stage = SyncPlayStage.FINISHED))))
        assertFalse(inTransit(GroupExec(group = SyncPlayGroup.Starting(name = null))))
    }

    @Test
    fun `the host's name is ours when we host, the core's when known, else none`() {
        assertEquals("member-a", hostName(SyncPlayHost.Me, "member-a"))
        assertNull(hostName(SyncPlayHost.Me, null))
        assertEquals("member-b", hostName(SyncPlayHost.Known("member-b"), "member-a"))
        assertNull(hostName(SyncPlayHost.Among, "member-a"))
        assertNull(hostName(SyncPlayHost.Unknown, "member-a"))
    }

    @Test
    fun `a finished screen's line is its notice, the host-left line or none`() {
        val strings = ResourceUiStrings.default
        assertEquals("The host left", outcomeText(strings, GroupOutcome.HostLeft))
        assertEquals("The group ended", outcomeText(strings, GroupOutcome.Ended(SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE))))
        assertEquals("That room has ended", outcomeText(strings, GroupOutcome.EntryFailed(SyncPlayNotice.NoSuchGroup)))
        assertNull(outcomeText(strings, GroupOutcome.Silent))
    }

    @Test
    fun `every notice shows its own line with names verbatim`() {
        val strings = ResourceUiStrings.default
        val rows = listOf(
            SyncPlayNotice.CreateRefused to "Stop what's playing to watch together",
            SyncPlayNotice.JoinRefused to "That group isn't available",
            SyncPlayNotice.StartFailed to "Couldn't start watch together",
            SyncPlayNotice.NoSuchGroup to "That room has ended",
            SyncPlayNotice.NoAccess to "Your account can't use watch-together",
            SyncPlayNotice.Joined to "You joined the group",
            SyncPlayNotice.UserJoined("member-B") to "member-B joined",
            SyncPlayNotice.UserLeft("member-B") to "member-B left",
            SyncPlayNotice.HostLeft to "The host left",
            SyncPlayNotice.Reconnecting to "Reconnecting…",
            SyncPlayNotice.GroupEnded(SyncPlayEndCause.REMOVED) to "You were removed from the group",
            SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE) to "The group ended",
            SyncPlayNotice.GroupEnded(SyncPlayEndCause.AUTH) to "You were signed out, so you left the group",
            SyncPlayNotice.GroupEnded(SyncPlayEndCause.LOST) to "Lost the connection to the group",
        )
        rows.forEach { (notice, line) -> assertEquals(line, noticeText(strings, notice)) }
        assertEquals("one row per notice and cause", terminal.size + live.size - 1 + SyncPlayEndCause.entries.size, rows.size)
    }

    // -- The player in a group (docs/28 §1, §6) ------------------------------------

    private val playing = SyncPlayGroup.Member(
        role = SyncPlayRole.HOST,
        stage = SyncPlayStage.PLAYING,
        host = SyncPlayHost.Me,
        participants = listOf("ana", "ben"),
        phase = SyncPlayPhase.PLAYING,
        connected = true,
        promptShown = false,
    )
    private val attachedStage = Stage.Attached(1uL, GroupPort { }, Motion.Stalled, Timed.None)

    private fun exec(group: SyncPlayGroup = playing, name: String? = "member-a's group", notices: List<SyncPlayNotice> = emptyList(), stage: Stage = attachedStage) =
        GroupExec(group = group, name = name, notices = notices, stage = stage)

    /** The player's group as the account "ana" sees it. */
    private fun osd(exec: GroupExec, ownName: String? = "ana") = groupOsd(exec, ownName)

    @Test
    fun `the player shows no group while solo, starting, leaving or in the lobby`() {
        val lobbies = listOf(SyncPlayStage.LOBBY, SyncPlayStage.FINISHED).map { playing.copy(stage = it) }
        (listOf(SyncPlayGroup.Idle, SyncPlayGroup.Starting("member-a's group"), SyncPlayGroup.Leaving(SyncPlayLeaveReason.USER)) + lobbies)
            .forEach { assertNull("$it", osd(exec(group = it))) }
        assertTrue(osd(exec()) != null)
    }

    @Test
    fun `every member sees the room name it knows, verbatim`() {
        assertEquals("member-a's group", osd(exec())?.name)
        assertEquals("member-a's group", osd(exec(group = playing.copy(role = SyncPlayRole.GUEST, host = SyncPlayHost.Known("ana"))))?.name)
        assertNull(osd(exec(name = null))?.name)
    }

    @Test
    fun `members keep the server's order and only the host the core names is marked`() {
        val hosting = playing.copy(participants = listOf("ben", "ana"))
        assertEquals("we host: our own name is marked, wherever it is listed", listOf(OsdMember("ben", false), OsdMember("ana", true)), osd(exec(group = hosting))?.members)
        assertEquals("without our name nobody is marked", false, osd(exec(group = hosting), ownName = null)?.members?.any { it.host })
        val guest = playing.copy(role = SyncPlayRole.GUEST, host = SyncPlayHost.Known("ana"), participants = listOf("ben", "ana", "Cy"))
        assertEquals(listOf(OsdMember("ben", false), OsdMember("ana", true), OsdMember("Cy", false)), osd(exec(group = guest), ownName = "ben")?.members)
        listOf(SyncPlayHost.Among, SyncPlayHost.Unknown).forEach { seat ->
            assertEquals("$seat marks nobody", false, osd(exec(group = guest.copy(host = seat)), ownName = "ben")?.members?.any { it.host })
        }
    }

    @Test
    fun `the group waits only in its Waiting phase`() {
        SyncPlayPhase.entries.forEach { phase ->
            assertEquals("$phase", phase == SyncPlayPhase.WAITING, osd(exec(group = playing.copy(phase = phase)))?.waiting)
        }
    }

    @Test
    fun `the stall prompt follows the group's prompt flag`() {
        assertEquals(false, osd(exec())?.prompt)
        assertEquals(true, osd(exec(group = playing.copy(phase = SyncPlayPhase.WAITING, promptShown = true)))?.prompt)
    }

    @Test
    fun `a lost connection is reconnecting`() {
        assertEquals(false, osd(exec())?.reconnecting)
        assertEquals(true, osd(exec(group = playing.copy(connected = false)))?.reconnecting)
    }

    @Test
    fun `the player's live notices are the non-terminal ones while it is the notice screen`() {
        val notices = listOf(SyncPlayNotice.UserJoined("ben"), ended, SyncPlayNotice.UserLeft("ben"))
        val load = GroupLoad(1uL, "item-a", "entry-a", 0L)
        val shown = listOf(SyncPlayNotice.UserJoined("ben"), SyncPlayNotice.UserLeft("ben"))
        assertEquals(shown, osd(exec(notices = notices))?.live)
        assertEquals(shown, osd(exec(notices = notices, stage = Stage.Loading(load, GroupPort { })))?.live)
        assertEquals(emptyList<SyncPlayNotice>(), osd(exec(notices = notices, stage = Stage.Launching(load)))?.live)
        assertEquals(emptyList<SyncPlayNotice>(), osd(exec(notices = notices, stage = Stage.Lobby))?.live)
    }

    @Test
    fun `each live notice is read once, in order, however the list changes meanwhile`() {
        val ana = SyncPlayNotice.UserJoined("ana")
        val left = SyncPlayNotice.UserLeft("ana")
        // (live list seen, executor's dropped count, notice read now)
        val rows = listOf(
            Triple(listOf(ana), 0, ana),
            Triple(listOf(ana, left), 0, null), // the read head is still waiting to be dropped
            Triple(listOf(left), 1, left),
            Triple(emptyList(), 2, null),
            Triple(listOf(left), 2, left), // the same notice again is a new turn after its drop
            Triple(listOf(left, ana, ana), 2, null),
            Triple(listOf(ana, ana), 3, ana),
            Triple(listOf(ana), 4, ana), // an equal notice behind the one read is read too
            Triple(listOf(ana, ana), 4, null), // an equal notice queues behind the head being read
            Triple(listOf(ana), 5, ana), // the head was dropped, so the equal one left is a new turn
            Triple(listOf(ana), 5, null), // nothing dropped, nothing new
            Triple(emptyList(), 5, null),
            Triple(listOf(ana), 5, null), // the head read before the list hid is not read again
        )
        var readAt: Int? = null
        rows.forEachIndexed { i, (live, dropped, fresh) ->
            val turn = noticeTurn(live, dropped, readAt)
            assertEquals("row $i", fresh, turn.fresh)
            readAt = turn.readAt
        }
        assertEquals("the first read of a list starts a turn", ana, noticeTurn(listOf(ana), 7, null).fresh)
    }

    @Test
    fun `the event line is reconnecting while it lasts, else the latest join or leave with names verbatim`() {
        val strings = ResourceUiStrings.default
        val group = osd(exec())!!
        val time: (Long) -> String = { "21:32" }
        assertEquals("21:32  ana JOINED", groupEventLine(strings, group, ReadNotice(SyncPlayNotice.UserJoined("ana"), 0L), time))
        assertEquals("21:32  ben LEFT", groupEventLine(strings, group, ReadNotice(SyncPlayNotice.UserLeft("ben"), 0L), time))
        assertEquals("RECONNECTING…", groupEventLine(strings, group.copy(reconnecting = true), ReadNotice(SyncPlayNotice.UserJoined("ana"), 0L), time))
        assertNull("a reconnect that landed is old news", groupEventLine(strings, group, ReadNotice(SyncPlayNotice.Reconnecting, 0L), time))
        assertNull(groupEventLine(strings, group, null, time))
    }

    @Test
    fun `the readout shows with the OSD and otherwise only a fresh event or a reconnect`() {
        for (osd in listOf(true, false)) for (sheet in listOf(true, false)) for (fresh in listOf(true, false)) for (reconnecting in listOf(true, false)) {
            val expected = when {
                sheet -> ReadoutShows.Nothing
                osd -> ReadoutShows.All
                fresh || reconnecting -> ReadoutShows.EventOnly
                else -> ReadoutShows.Nothing
            }
            assertEquals("$osd $sheet $fresh $reconnecting", expected, readoutShows(osd, sheet, fresh, reconnecting))
        }
    }

    @Test
    fun `the helper line shows on the first OSD reveal only`() {
        var helper = HelperLine.Pending
        assertFalse("not while hidden", helperShown(helper, osdVisible = false))
        assertEquals(HelperLine.Pending, helperAfter(helper, osdVisible = false))
        assertTrue("from the reveal's first frame", helperShown(helper, osdVisible = true))
        helper = helperAfter(helper, osdVisible = true)
        assertTrue(helperShown(helper, osdVisible = true))
        helper = helperAfter(helper, osdVisible = false)
        assertEquals(HelperLine.Done, helper)
        helper = helperAfter(helper, osdVisible = true)
        assertFalse("not on the next reveal", helperShown(helper, osdVisible = true))
    }

    @Test
    fun `only the host's own setting auto-skips in a group, a guest gets the pill, solo is unchanged`() {
        SegmentDecision.entries.forEach {
            assertEquals("solo $it", it, groupSegmentDecision(it, role = null))
            assertEquals("host $it", it, groupSegmentDecision(it, SyncPlayRole.HOST))
        }
        assertEquals(SegmentDecision.PILL, groupSegmentDecision(SegmentDecision.AUTO_SKIP, SyncPlayRole.GUEST))
        assertEquals(SegmentDecision.PILL, groupSegmentDecision(SegmentDecision.PILL, SyncPlayRole.GUEST))
        assertEquals(SegmentDecision.NOTHING, groupSegmentDecision(SegmentDecision.NOTHING, SyncPlayRole.GUEST))
    }
}
