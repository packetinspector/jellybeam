package tv.jellybeam.ui.watchtogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.R
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.player.GroupExec
import tv.jellybeam.player.JoinTarget
import uniffi.jellybeam_core.SyncPlayEndCause
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlayStage

/** The room list a guest joined from; the lobby never reads it. */
private val joinedFromList = TogetherState.Rooms(1uL, RoomList.Loading, RoomsPoll.Off, null)

class LobbyFormatTest {

    private val strings = ResourceUiStrings.default
    private val clock: (Long) -> String = { if (it == 77L) "21:32" else "?" }

    private fun group(
        role: SyncPlayRole,
        vararg names: String,
        host: SyncPlayHost = SyncPlayHost.Me,
        stage: SyncPlayStage = SyncPlayStage.LOBBY,
        connected: Boolean = true,
    ) = SyncPlayGroup.Member(
        role = role,
        stage = stage,
        host = host,
        participants = names.toList(),
        // An idle lobby: a playing room's phase would make a guest a late joiner.
        phase = SyncPlayPhase.IDLE,
        connected = connected,
        promptShown = false,
    )

    private fun hostView(vararg names: String, held: HeldItem = HeldItem("item-a", 0L), finished: Boolean = false, memory: LobbyMemory = LobbyMemory()) = lobbyView(
        GroupExec(group = group(SyncPlayRole.HOST, *names, stage = if (finished) SyncPlayStage.FINISHED else SyncPlayStage.LOBBY), name = "sp-alice's group"),
        Origin.Created(held),
        memory,
        ownName = names.first(),
    )

    private fun guestView(
        vararg names: String,
        host: SyncPlayHost = SyncPlayHost.Known("sp-alice"),
        memory: LobbyMemory = LobbyMemory(),
        connected: Boolean = true,
        ownName: String? = null,
    ) = lobbyView(GroupExec(group = group(SyncPlayRole.GUEST, *names, host = host, connected = connected)), Origin.Joined(JoinTarget("room-1", "sp-alice's group"), joinedFromList), memory, ownName)

    private fun labels(view: LobbyView) = lobbyButtons(view).map {
        when (it) {
            is LobbyButton.Start -> it.label.text(strings)
            LobbyButton.Choose -> strings.get(R.string.wt_choose_title)
            is LobbyButton.Exit -> it.label.text(strings)
        }
    }

    // -- Members ----------------------------------------------------------------------------------

    @Test
    fun `the host sees itself as host and you and everyone after it as joined`() {
        assertEquals(listOf(MemberRow("sp-alice", MemberTag.HostYou)), memberRows(listOf("sp-alice"), "sp-alice", "sp-alice"))
        assertEquals(
            listOf(MemberRow("sp-alice", MemberTag.HostYou), MemberRow("sp-Bob", MemberTag.Joined)),
            memberRows(listOf("sp-alice", "sp-Bob"), "sp-alice", "sp-alice"),
        )
    }

    @Test
    fun `a guest sees the host first, itself as you, earlier members in the group and later ones joined`() {
        val names = listOf("sp-carol", "sp-alice", "sp-bob", "sp-dave")
        assertEquals(
            listOf(
                MemberRow("sp-alice", MemberTag.Host),
                MemberRow("sp-carol", MemberTag.InGroup),
                MemberRow("sp-bob", MemberTag.You),
                MemberRow("sp-dave", MemberTag.Joined),
            ),
            memberRows(names, "sp-alice", "sp-bob"),
        )
        assertEquals(MemberTag.InGroup, memberRows(names, "sp-alice", null).last().tag)
    }

    @Test
    fun `our own row is the account's name, else the one name left beside the core's host, never the list order`() {
        val known = SyncPlayHost.Known("sp-alice")
        assertEquals("sp-bob", selfName(listOf("sp-alice", "sp-bob", "sp-carol"), known, "sp-bob"))
        assertEquals("sp-bob", selfName(listOf("sp-alice", "sp-bob"), known, null))
        assertEquals("sp-bob", selfName(listOf("sp-bob", "sp-alice"), known, "someone-else"))
        assertNull(selfName(listOf("sp-alice", "sp-bob", "sp-carol"), known, null))
        assertNull("several possible hosts name no one", selfName(listOf("sp-alice", "sp-bob"), SyncPlayHost.Among, null))
        assertNull(selfName(listOf("sp-alice", "sp-bob"), SyncPlayHost.Unknown, null))
        assertEquals("sp-alice", selfName(listOf("sp-alice"), SyncPlayHost.Me, null))
        assertNull("a host among others needs its account name", selfName(listOf("sp-alice", "sp-bob"), SyncPlayHost.Me, null))
        assertEquals("sp-bob", selfName(listOf("sp-alice", "sp-bob"), SyncPlayHost.Me, "sp-bob"))
    }

    @Test
    fun `a host's rows mark its own name as host wherever the server lists it`() {
        val view = hostView("sp-bob", "sp-alice")
        assertEquals("sp-bob", view.hostName)
        assertEquals(listOf(MemberRow("sp-bob", MemberTag.HostYou), MemberRow("sp-alice", MemberTag.Joined)), memberRows(view.members, view.hostName, view.self))
    }

    @Test
    fun `several possible hosts show no host name and no host tag, and our row stays ours`() {
        val view = guestView("sp-alice", "sp-bob", "sp-carol", host = SyncPlayHost.Among, ownName = "sp-carol")
        assertEquals("Waiting for the host to start", lobbyHeading(view).text(strings))
        val rows = memberRows(view.members, view.hostName, view.self)
        assertEquals(listOf(MemberTag.InGroup, MemberTag.InGroup, MemberTag.You), rows.map { it.tag })
    }

    @Test
    fun `a late joiner reads joining and lists no one until the title loads`() {
        val transit = lobbyView(
            GroupExec(group = group(SyncPlayRole.GUEST, "sp-alice", "sp-bob", host = SyncPlayHost.Known("sp-alice")).copy(phase = SyncPlayPhase.PLAYING)),
            Origin.Joined(JoinTarget("room-1", "sp-alice's group"), joinedFromList),
        )
        assertEquals(true, transit.transit)
        assertEquals("Joining…", lobbyHeading(transit).text(strings))
        val loaded = lobbyView(
            GroupExec(lastSeq = 3uL, group = group(SyncPlayRole.GUEST, "sp-alice", "sp-bob", host = SyncPlayHost.Known("sp-alice")).copy(phase = SyncPlayPhase.PLAYING)),
            Origin.Joined(JoinTarget("room-1", "sp-alice's group"), joinedFromList),
        )
        assertEquals(false, loaded.transit)
        assertEquals("Waiting for sp-alice to start", lobbyHeading(loaded).text(strings))
    }

    // -- Log line ---------------------------------------------------------------------------------

    @Test
    fun `a host alone waits for others without a time`() {
        assertEquals(LogLine("WAITING FOR OTHERS TO JOIN…", LogTone.Waiting), logLine(strings, hostView("sp-alice"), clock))
    }

    @Test
    fun `an event shows its arrival time and the name verbatim with only the verb in capitals`() {
        val joined = LobbyMemory(log = Stamped(SyncPlayNotice.UserJoined("sp-Bob"), 77L))
        assertEquals(LogLine("21:32  sp-Bob JOINED", LogTone.Event), logLine(strings, hostView("sp-alice", "sp-Bob", memory = joined), clock))
        val left = LobbyMemory(log = Stamped(SyncPlayNotice.UserLeft("sp-Bob"), 77L))
        assertEquals(LogLine("21:32  sp-Bob LEFT", LogTone.Event), logLine(strings, hostView("sp-alice", memory = left), clock))
        val you = LobbyMemory(log = Stamped(SyncPlayNotice.Joined, 77L))
        assertEquals(LogLine("21:32  YOU JOINED", LogTone.Event), logLine(strings, guestView("sp-alice", "sp-bob", memory = you), clock))
    }

    @Test
    fun `the host's own joined notice keeps the waiting line`() {
        val own = LobbyMemory(log = Stamped(SyncPlayNotice.Joined, 77L))
        assertEquals(LogLine("WAITING FOR OTHERS TO JOIN…", LogTone.Waiting), logLine(strings, hostView("sp-alice", memory = own), clock))
    }

    @Test
    fun `a guest with no event shows no log line`() {
        assertNull(logLine(strings, guestView("sp-alice", "sp-bob"), clock))
    }

    @Test
    fun `a lost connection says reconnecting in amber and a landed reconnect is not news`() {
        assertEquals(LogLine("RECONNECTING…", LogTone.Alert), logLine(strings, guestView("sp-alice", "sp-bob", connected = false), clock))
        val old = LobbyMemory(log = Stamped(SyncPlayNotice.Reconnecting, 77L))
        assertEquals(LogLine("WAITING FOR OTHERS TO JOIN…", LogTone.Waiting), logLine(strings, hostView("sp-alice", memory = old), clock))
    }

    @Test
    fun `a group still starting or leaving says so in the log line`() {
        val starting = lobbyView(GroupExec(group = SyncPlayGroup.Starting("sp-alice's group")), Origin.Created(HeldItem("item-a", 0L)))
        assertEquals(LogLine("STARTING…", LogTone.Waiting), logLine(strings, starting, clock))
        val joining = lobbyView(GroupExec(group = SyncPlayGroup.Idle), Origin.Joined(JoinTarget("room-1", "sp-alice's group"), joinedFromList))
        assertEquals(LogLine("JOINING…", LogTone.Waiting), logLine(strings, joining, clock))
    }

    @Test
    fun `a terminal notice reads in capitals`() {
        assertEquals("THE GROUP ENDED", logText(strings, SyncPlayNotice.GroupEnded(SyncPlayEndCause.GONE)))
    }

    // -- Headings, roles, buttons, title card -----------------------------------------------------

    @Test
    fun `a host before playing has its room open with Start then Cancel and shows what it holds`() {
        val view = hostView("sp-alice")
        assertEquals("Your room is open", lobbyHeading(view).text(strings))
        assertEquals(R.string.wt_role_hosting, lobbyRole(view))
        assertEquals(listOf("Start", "Cancel"), labels(view))
        assertEquals(listOf("start-0", "cancel"), lobbyButtons(view).map { it.key })
        assertEquals("item-a", view.cardItem)
        assertEquals(R.string.wt_card_now_showing, cardCaption(view))
    }

    @Test
    fun `a resumable title keeps both starts`() {
        assertEquals(listOf("From the beginning", "Resume from 0:03", "Cancel"), labels(hostView("sp-alice", held = HeldItem("item-a", 30_000_000L))))
    }

    @Test
    fun `a host back from the title asks to watch something else, choosing first`() {
        val view = hostView("sp-alice", "sp-bob", finished = true, memory = LobbyMemory(lastPlayed = "item-a"))
        assertEquals("Finished. Watch something else?", lobbyHeading(view).text(strings))
        assertEquals(listOf("Choose another title", "Play again", "End group"), labels(view))
        assertEquals("choose", lobbyButtons(view).first().key)
        assertEquals(R.string.wt_card_just_watched, cardCaption(view))
        val resumable = hostView("sp-alice", held = HeldItem("item-a", 30_000_000L), finished = true, memory = LobbyMemory(lastPlayed = "item-a"))
        assertEquals(listOf("Choose another title", "Play again", "Resume from 0:03", "End group"), labels(resumable))
    }

    @Test
    fun `a host holding a newly chosen title starts it as up next`() {
        val view = hostView("sp-alice", held = HeldItem("item-b", 0L), finished = true, memory = LobbyMemory(lastPlayed = "item-a"))
        assertEquals("Your room is open", lobbyHeading(view).text(strings))
        assertEquals(listOf("Start", "Choose another title", "Cancel"), labels(view))
        assertEquals("item-b", view.cardItem)
        assertEquals(R.string.wt_card_up_next, cardCaption(view))
    }

    @Test
    fun `a guest waits for the host by name and can only leave`() {
        val view = guestView("sp-alice", "sp-bob")
        assertEquals("Waiting for sp-alice to start", lobbyHeading(view).text(strings))
        assertEquals(R.string.wt_role_joined, lobbyRole(view))
        assertEquals(listOf("Leave"), labels(view))
        assertEquals("Waiting for the host to start", lobbyHeading(guestView("sp-alice", "sp-bob", host = SyncPlayHost.Unknown)).text(strings))
    }

    @Test
    fun `a guest's title card appears only once a title played`() {
        assertNull(guestView("sp-alice", "sp-bob").cardItem)
        val adopted = lobbyView(GroupExec(group = group(SyncPlayRole.GUEST, "sp-alice", "sp-bob", host = SyncPlayHost.Known("sp-alice"))), Origin.Adopted(HeldItem("item-b", 0L)))
        assertNull(adopted.cardItem)
        val after = guestView("sp-alice", "sp-bob", memory = LobbyMemory(lastPlayed = "item-a"))
        assertEquals("item-a", after.cardItem)
        assertEquals(R.string.wt_card_just_watched, cardCaption(after))
    }
}
