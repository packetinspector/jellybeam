package tv.jellybeam.ui.watchtogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.GroupExec
import tv.jellybeam.player.GroupLoad
import tv.jellybeam.player.JoinTarget
import tv.jellybeam.player.Stage
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayStage

/** The room list a guest joined from; the lobby never reads it. */
private val joinedFromList = TogetherState.Rooms(1uL, RoomList.Loading, RoomsPoll.Off, null)

class LobbyViewTest {

    private val host = SyncPlayGroup.Member(
        role = SyncPlayRole.HOST,
        stage = SyncPlayStage.LOBBY,
        host = SyncPlayHost.Me,
        participants = listOf("member-a", "member-B"),
        phase = SyncPlayPhase.WAITING,
        connected = true,
        promptShown = false,
    )
    private val resumable = HeldItem("item-a", 30_000_000L)

    private fun exec(group: SyncPlayGroup, notices: List<SyncPlayNotice> = emptyList(), stage: Stage = Stage.Lobby) =
        GroupExec(group = group, name = "member-a's group", notices = notices, stage = stage)

    @Test
    fun `a host holding a resumable title offers both starts, the room name and the members verbatim`() {
        val view = lobbyView(exec(host), Origin.Created(resumable))
        assertEquals(LobbyPhase.Members, view.phase)
        assertEquals("member-a's group", view.name)
        assertEquals(listOf("member-a", "member-B"), view.members)
        assertEquals(listOf(0L, 30_000_000L), view.startTicks)
        assertTrue(view.startActive)
        assertFalse(view.chooseTitle)
    }

    @Test
    fun `a title without a resume position has one Start`() {
        assertEquals(listOf(0L), lobbyView(exec(host), Origin.Created(HeldItem("item-a", 0L))).startTicks)
    }

    @Test
    fun `after the queue ended the host may choose another title and a guest keeps waiting`() {
        val ended = host.copy(stage = SyncPlayStage.FINISHED)
        assertTrue(lobbyView(exec(ended), Origin.Created(resumable)).chooseTitle)
        val guest = lobbyView(exec(ended.copy(role = SyncPlayRole.GUEST, host = SyncPlayHost.Known("member-a"))), Origin.Joined(JoinTarget("room-1", "member-a's group"), joinedFromList))
        assertFalse(guest.chooseTitle)
        assertFalse(guest.host)
        assertEquals("a guest sees the room name too", "member-a's group", guest.name)
        assertEquals(emptyList<Long>(), guest.startTicks)
    }

    @Test
    fun `a disconnected host's Start is inert and the reconnecting notice is not shown twice`() {
        val view = lobbyView(exec(host.copy(connected = false), listOf(SyncPlayNotice.Reconnecting)), Origin.Created(resumable))
        assertFalse(view.startActive)
        assertFalse(view.connected)
        assertNull(view.noticeLine)
        assertEquals(1, view.pendingNotices)
    }

    @Test
    fun `the lobby shows live notices only until a player loads`() {
        val notices = listOf(SyncPlayNotice.UserJoined("member-B"), SyncPlayNotice.HostLeft)
        val lobby = lobbyView(exec(host, notices), Origin.Created(resumable))
        assertEquals(SyncPlayNotice.UserJoined("member-B"), lobby.noticeLine)
        assertEquals(1, lobby.pendingNotices)
        val loading = lobbyView(exec(host, notices, Stage.Loading(GroupLoad(1uL, "item-a", "entry-a", 0L)) { }), Origin.Created(resumable))
        assertNull(loading.noticeLine)
        assertEquals(0, loading.pendingNotices)
    }

    @Test
    fun `a group still starting or leaving says so`() {
        assertEquals(LobbyPhase.Starting, lobbyView(exec(SyncPlayGroup.Starting("member-a's group")), Origin.Created(resumable)).phase)
        assertEquals(LobbyPhase.Starting, lobbyView(exec(SyncPlayGroup.Idle), Origin.Joined(JoinTarget("room-1", "member-a's group"), joinedFromList)).phase)
        assertEquals(
            LobbyPhase.Leaving,
            lobbyView(exec(SyncPlayGroup.Leaving(uniffi.jellybeam_core.SyncPlayLeaveReason.HOST_LEFT)), Origin.Created(resumable)).phase,
        )
    }

    @Test
    fun `a lobby whose group is leaving offers only its exit, even to a finished host`() {
        val leaving = exec(SyncPlayGroup.Leaving(uniffi.jellybeam_core.SyncPlayLeaveReason.USER))
        for (origin in listOf(Origin.Created(resumable), Origin.Adopted(resumable), Origin.Joined(JoinTarget("room-1", "member-a's group"), joinedFromList))) {
            val buttons = lobbyButtons(lobbyView(leaving, origin, LobbyMemory(lastPlayed = "item-a")))
            assertEquals("$origin", listOf(LobbyButton.Exit::class), buttons.map { it::class })
        }
        val backed = lobbyView(exec(host.copy(stage = SyncPlayStage.FINISHED)), Origin.Created(resumable), LobbyMemory(lastPlayed = "item-a"), leaving = true)
        assertEquals("a Back already sent leaves only the exit before the core says so", listOf(LobbyButton.Exit::class), lobbyButtons(backed).map { it::class })
        assertEquals(LobbyPhase.Leaving, backed.phase)
    }
}
