package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.PlayerFlow.Main
import tv.jellybeam.player.PlayerFlow.Player
import tv.jellybeam.player.PlayerFlow.Player.Closing
import tv.jellybeam.player.PlayerFlow.Player.Expanding
import tv.jellybeam.player.PlayerFlow.Player.Fullscreen
import tv.jellybeam.player.PlayerFlow.Player.Launching
import tv.jellybeam.player.PlayerFlow.Player.None
import tv.jellybeam.player.PlayerFlow.Player.Pip
import tv.jellybeam.player.PlayerFlow.Player.Shrinking
import tv.jellybeam.player.PlayerFlow.Policy
import tv.jellybeam.player.PlayerFlow.Request
import tv.jellybeam.player.PlayerFlow.Root

/** docs/17 §3: the player slot's transitions, how a waiting request settles, and the races between them; [g] is the slot's generation. */
class PlayerFlowReduceTest {
    private val g = 7L
    private val expandReq = play(90, ITEM_A)

    /** The seven columns of the table, each holding generation [g]. */
    private val columns: List<Player> = listOf(None, Launching(LAUNCH), Fullscreen(g), Shrinking(g), Pip(g), Expanding(g, expandReq), Closing(g))

    /** Expected slot per column for [event], `null` = the event is ignored there. */
    private fun assertRow(event: PlayerEvent, vararg expected: Player?) {
        columns.zip(expected.toList()).forEach { (from, to) ->
            val step = reduce(PlayerFlow(from), event, null)
            assertEquals("$event from $from", to ?: from, step.state.player)
            assertEquals("$event from $from draining", emptySet<Long>(), step.state.draining)
            assertTrue("$event from $from effects", step.effects.isEmpty())
        }
    }

    @Test
    fun `Created takes the slot and drains whatever instance held it`() {
        val n = g + 1
        columns.forEach { from ->
            val step = reduce(PlayerFlow(from), PlayerEvent.Created(n), null)
            assertEquals(Fullscreen(n), step.state.player)
            val drained = if (from == None || from is Launching) emptySet() else setOf(g)
            assertEquals("Created from $from", drained, step.state.draining)
            assertTrue(step.effects.isEmpty())
        }
    }

    @Test
    fun `PipRequested shrinks only a fullscreen player`() =
        assertRow(PlayerEvent.PipRequested(g), null, null, Shrinking(g), null, null, null, null)

    @Test
    fun `PipEntered moves fullscreen, shrinking and expanding players to PiP`() =
        assertRow(PlayerEvent.PipEntered(g), null, null, Pip(g), Pip(g), null, Pip(g), null)

    @Test
    fun `an expanding PiP exit returns a PiP player to fullscreen`() =
        assertRow(PlayerEvent.PipExited(g, started = true), null, null, null, null, Fullscreen(g), null, null)

    @Test
    fun `a dismissed PiP player closes, once, and only from PiP`() {
        columns.forEach { from ->
            val step = reduce(PlayerFlow(from), PlayerEvent.PipExited(g, started = false), null)
            if (from == Pip(g)) {
                assertEquals(Closing(g), step.state.player)
                assertEquals(listOf(PlayerEffect.Close(g)), step.effects)
            } else {
                assertEquals("dismissal from $from", from, step.state.player)
                assertTrue(step.effects.isEmpty())
            }
        }
    }

    @Test
    fun `Resumed makes any live player but a closing one fullscreen`() =
        assertRow(PlayerEvent.Resumed(g), null, null, null, Fullscreen(g), Fullscreen(g), Fullscreen(g), null)

    @Test
    fun `Leaving closes any live player`() =
        assertRow(PlayerEvent.Leaving(g), null, null, Closing(g), Closing(g), Closing(g), Closing(g), null)

    @Test
    fun `Destroyed empties the slot from every live state`() =
        assertRow(PlayerEvent.Destroyed(g), null, null, None, None, None, None, None)

    /** Expanding(r) rows: Created, PipEntered, Leaving and Destroyed requeue r; Resumed retires it. */
    @Test
    fun `an unfinished expand requeues its request and a landed one retires it`() {
        val from = PlayerFlow(Expanding(g, expandReq))
        val requeued = Request.Waiting(expandReq, Policy.Any)
        listOf(PlayerEvent.Created(g + 1), PlayerEvent.PipEntered(g), PlayerEvent.Leaving(g), PlayerEvent.Destroyed(g)).forEach { event ->
            assertEquals("$event", requeued, reduce(from, event, null).state.request)
        }
        assertEquals(Request.None, reduce(from, PlayerEvent.Resumed(g), null).state.request)
        val newer = Request.Waiting(play(91, ITEM_B), Policy.Any)
        assertEquals("newest wins", newer, reduce(from.copy(request = newer), PlayerEvent.Leaving(g), null).state.request)
    }

    /** docs/17 §3: a stale instance's callbacks are told apart by generation. */
    @Test
    fun `an event from a generation that is neither current nor draining changes nothing`() {
        val stale = g - 3
        val state = PlayerFlow(Pip(g), draining = setOf(g - 1))
        playerEvents(stale).forEach { event ->
            assertEquals("$event", PlayerStep(state), reduce(state, event, null))
        }
    }

    /** docs/17 §3: a draining instance's callbacks never touch the slot; its destroy only leaves the draining set. */
    @Test
    fun `a draining generation's events never change the slot`() {
        val old = g - 1
        val state = PlayerFlow(Pip(g), draining = setOf(old))
        playerEvents(old).forEach { event ->
            val step = reduce(state, event, null)
            assertEquals("$event", Pip(g), step.state.player)
            assertTrue(step.effects.isEmpty())
            val draining = if (event is PlayerEvent.Destroyed) emptySet() else setOf(old)
            assertEquals("$event", draining, step.state.draining)
        }
    }

    @Test
    fun `the slot's generation is never draining`() {
        val step = reduce(PlayerFlow(Closing(g), draining = setOf(g - 1)), PlayerEvent.Created(g + 1), null)
        assertEquals(setOf(g - 1, g), step.state.draining)
        assertFalse((step.state.player as Player.Live).g in step.state.draining)
    }

    /** docs/17 §3: a second PLAY while the first one's replacement closes the old player launches once, for the newest. */
    @Test
    fun `a replace race launches exactly the newest request once`() {
        val first = play(1, ITEM_B)
        val second = play(2, ITEM_C)
        val run = run(
            home(Fullscreen(1)),
            HostEvent.Arrive(first),
            PlayerEvent.PipRequested(1),
            PlayerEvent.PipEntered(1),
            HostEvent.MainShown,
            HostEvent.Arrive(second),
            PlayerEvent.Leaving(1),
            PlayerEvent.PipExited(1, started = false),
            PlayerEvent.Destroyed(1),
        )
        assertEquals(listOf(PlayerEffect.Close(1), PlayerEffect.Launch(second)), run.effects)
        assertEquals(Launching(second.id), run.state.player)
        assertEquals(emptySet<Long>(), run.state.draining)
    }

    /** docs/17 §3: a PLAY for the playing item over a fullscreen player that shrinks stays in PiP, whichever lands first. */
    @Test
    fun `the expand race keeps a same-item player in PiP in both orders`() {
        val same = play(1, ITEM_A)
        listOf(
            listOf(HostEvent.Arrive(same), PlayerEvent.PipRequested(1)),
            listOf(PlayerEvent.PipRequested(1), HostEvent.Arrive(same)),
        ).forEach { prefix ->
            val run = run(home(Fullscreen(1)), *(prefix + PlayerEvent.PipEntered(1) + HostEvent.MainShown).toTypedArray())
            assertEquals("$prefix", emptyList<PlayerEffect>(), run.effects)
            assertEquals("$prefix", Pip(1), run.state.player)
            assertEquals("$prefix", Request.None, run.state.request)
        }
    }

    /** docs/17 §3: the platform kills an expand before it lands; the request is launched once after the destroy. */
    @Test
    fun `an expand killed by the platform relaunches its request once`() {
        val req = play(1, ITEM_B)
        val run = run(
            home(Pip(1), Main.Shown),
            HostEvent.Arrive(req),
            PlayerEvent.Leaving(1),
            PlayerEvent.Destroyed(1),
            PlayerEvent.Created(2),
            PlayerEvent.Resumed(2),
        )
        assertEquals(listOf(PlayerEffect.Deliver(1, req), PlayerEffect.Launch(req)), run.effects)
        assertEquals(Fullscreen(2), run.state.player)
        assertEquals(Request.None, run.state.request)
    }

    /** docs/17 §3: after the stay, the same PLAY again with Home shown expands the mini player. */
    @Test
    fun `sending the same item again over a shown mini player expands it`() {
        val stayed = run(home(Fullscreen(1)), HostEvent.Arrive(play(1, ITEM_A)), PlayerEvent.PipRequested(1), PlayerEvent.PipEntered(1), HostEvent.MainShown)
        val again = play(2, ITEM_A)
        val step = reduce(stayed.state, HostEvent.Arrive(again), ITEM_A)
        assertEquals(listOf(PlayerEffect.Deliver(1, again)), step.effects)
        assertEquals(Expanding(1, again), step.state.player)
    }

    /** docs/17 §3: with the mini player off the fullscreen player just closes, and the request launches once it is gone. */
    @Test
    fun `with the mini player off the request launches after the player is destroyed`() {
        val req = play(1, ITEM_A)
        val run = run(home(Fullscreen(1)), HostEvent.Arrive(req), HostEvent.MainShown, PlayerEvent.Leaving(1))
        assertEquals("nothing while it closes", emptyList<PlayerEffect>(), run.effects)
        val step = reduce(run.state, PlayerEvent.Destroyed(1), ITEM_A)
        assertEquals(listOf(PlayerEffect.Launch(req)), step.effects)
    }

    /** docs/17 §3: a player created while an older one closes takes the waiting request; the older one drains. */
    @Test
    fun `a request waiting on a closing player settles against its replacement`() {
        val req = play(1, ITEM_B)
        val step = reduce(home(Closing(1), Main.Shown).copy(request = Request.Waiting(req, Policy.Any)), PlayerEvent.Created(2), null)
        assertEquals(setOf(1L), step.state.draining)
        assertEquals(Fullscreen(2), step.state.player)
        assertEquals(listOf(PlayerEffect.Deliver(2, req)), step.effects)
    }

    /** docs/18 §2.1: an account change drops the request and its stack, never the player. */
    @Test
    fun `an account reset drops the request and never closes the mini player`() {
        val state = home(Pip(1)).copy(request = Request.Waiting(play(1, ITEM_B), Policy.Any))
        val step = reduce(state, HostEvent.AccountReset, ITEM_A)
        assertEquals(PlayerStep(state.copy(request = Request.None, root = Root.Pending)), step)
    }

    /** docs/18 §2.1: an account reset retires an expand's request, so the platform finishing the expand never relaunches it. */
    @Test
    fun `an account reset retires an expand without requeueing it`() {
        val req = play(1, ITEM_B)
        val reset = reduce(home(Expanding(1, req), Main.Shown), HostEvent.AccountReset, ITEM_A)
        assertEquals(PlayerStep(PlayerFlow(Fullscreen(1), main = Main.Shown)), reset)
        val run = run(reset.state, PlayerEvent.Leaving(1), PlayerEvent.Destroyed(1), HostEvent.RootChanged(Root.Pending), HostEvent.RootChanged(Root.Home))
        assertEquals(emptyList<PlayerEffect>(), run.effects)
        assertEquals(Request.None, run.state.request)
    }

    /** The account tree goes with MainActivity, so a later request waits for the next one's stack. */
    @Test
    fun `leaving the app returns the root to pending`() {
        listOf(Pip(1), Fullscreen(1), None).forEach { player ->
            assertEquals("$player", Root.Pending, reduce(home(player, Main.Shown), HostEvent.AppExit, ITEM_A).state.root)
        }
    }

    /** Fresh is about the instance the intent shrank; a newer generation is not it. */
    @Test
    fun `a fresh request waiting on a closing player is delivered to its replacement`() {
        val req = play(1, ITEM_B)
        val step = reduce(home(Closing(1)).copy(request = Request.Waiting(req, Policy.Fresh)), PlayerEvent.Created(2), null)
        assertEquals(listOf(PlayerEffect.Deliver(2, req)), step.effects)
        assertEquals(Request.None, step.state.request)
    }

    /** docs/18 §2.1: a request waiting when the account changes never launches on the next account. */
    @Test
    fun `a request waiting through an account change never launches`() {
        listOf(play(1, ITEM_A), detail(1, ITEM_A)).forEach { req ->
            val run = run(
                PlayerFlow(),
                HostEvent.Arrive(req),
                HostEvent.AccountReset,
                HostEvent.RootChanged(Root.Home),
                HostEvent.MainShown,
            )
            assertEquals("$req", emptyList<PlayerEffect>(), run.effects)
            assertEquals("$req", Request.None, run.state.request)
        }
    }

    /** docs/05 "external": a signed-out PLAY is dropped with sign-in feedback; a signed-out detail request is dropped quietly. */
    @Test
    fun `a signed-out external request is dropped, a PLAY with sign-in feedback`() {
        listOf(PlayerFlow(), PlayerFlow(root = Root.SignedOut, main = Main.Shown)).forEach { from ->
            val pending = run(from, HostEvent.Arrive(play(1, ITEM_A)), HostEvent.RootChanged(Root.SignedOut))
            assertEquals("$from", listOf(PlayerEffect.SignInRequired), pending.effects)
            assertEquals("$from", Request.None, pending.state.request)
        }
        val quiet = run(PlayerFlow(root = Root.SignedOut), HostEvent.Arrive(detail(2, ITEM_A)))
        assertEquals(emptyList<PlayerEffect>(), quiet.effects)
        assertEquals(Request.None, quiet.state.request)
    }

    /** A cold external launch waits for the stack, then launches with its start fields. */
    @Test
    fun `a request arriving before the stack resolves launches on Home with its start`() {
        val req = play(1, ITEM_A, Req.Start.At(90_000L))
        val run = run(PlayerFlow(), HostEvent.Arrive(req), HostEvent.MainShown)
        assertEquals("waits while the root is pending", emptyList<PlayerEffect>(), run.effects)
        assertEquals(listOf(PlayerEffect.Launch(req)), reduce(run.state, HostEvent.RootChanged(Root.Home), null).effects)
    }

    @Test
    fun `a launch superseded before its player exists delivers the newer request to it`() {
        val first = play(1, ITEM_A)
        val second = play(2, ITEM_B)
        val run = run(home(None, Main.Shown), HostEvent.Arrive(first), HostEvent.Arrive(second), PlayerEvent.Created(1))
        assertEquals(listOf(PlayerEffect.Launch(first), PlayerEffect.Deliver(1, second)), run.effects)
    }

    @Test
    fun `a failed launch empties the slot`() {
        val run = run(home(None, Main.Shown), HostEvent.Arrive(play(1, ITEM_A)), HostEvent.LaunchFailed(1))
        assertEquals(None, run.state.player)
        assertEquals(Request.None, run.state.request)
    }

    /** A failed start resets only its own launch: an older one's late failure, or a Deliver's into a live player, changes nothing. */
    @Test
    fun `a failure for another request's start changes nothing`() {
        val newer = home(Launching(2), Main.Shown)
        assertEquals(PlayerStep(newer), reduce(newer, HostEvent.LaunchFailed(1), null))
        val live = home(Fullscreen(1), Main.Shown)
        assertEquals(PlayerStep(live), reduce(live, HostEvent.LaunchFailed(3), ITEM_A))
        assertEquals(None, reduce(newer, HostEvent.LaunchFailed(2), null).state.player)
    }

    /** docs/17 §3: AppExit closes a player in or headed for PiP, once, and drops the request. */
    @Test
    fun `leaving the app closes a player in or headed for PiP and nothing else`() {
        columns.forEach { from ->
            val step = reduce(PlayerFlow(from, request = Request.Waiting(play(1, ITEM_B), Policy.Any)), HostEvent.AppExit, null)
            val closes = from is Shrinking || from is Pip || from is Expanding
            assertEquals("$from", if (closes) listOf(PlayerEffect.Close(g)) else emptyList(), step.effects)
            assertEquals("$from", if (closes) Closing(g) else from, step.state.player)
            assertEquals("$from", Request.None, step.state.request)
        }
    }

    /** docs/28 §1: a group create closes any live player but a closing one, once, keeping the request and dropping an expand's. */
    @Test
    fun `a group stop closes every live player but a closing one`() {
        columns.forEach { from ->
            listOf(Request.None, Request.Waiting(group(1, ITEM_B), Policy.Any)).forEach { request ->
                val state = PlayerFlow(from, request = request, root = Root.Home)
                val step = reduce(state, HostEvent.GroupStop, null)
                val closes = from is Fullscreen || from is Shrinking || from is Pip || from is Expanding
                assertEquals("$from $request", if (closes) listOf(PlayerEffect.Close(g)) else emptyList(), step.effects)
                assertEquals("$from $request", state.copy(player = if (closes) Closing(g) else from), step.state)
            }
        }
    }

    /** docs/17 §3: the older instance's destroy after its replacement's create only drains it; a repeat is a no-op. */
    @Test
    fun `a stale destroy after a newer create changes nothing`() {
        val created = reduce(home(Fullscreen(1), Main.Shown), PlayerEvent.Created(2), null).state
        val drained = reduce(created, PlayerEvent.Destroyed(1), null)
        assertEquals(PlayerStep(created.copy(draining = emptySet())), drained)
        assertEquals(PlayerStep(drained.state), reduce(drained.state, PlayerEvent.Destroyed(1), null))
    }

    @Test
    fun `a detail request waits on Home until its own DetailDone`() {
        val req = detail(1, ITEM_A)
        val waiting = run(home(None, Main.Shown), HostEvent.Arrive(req))
        assertEquals(emptyList<PlayerEffect>(), waiting.effects)
        assertEquals(Request.Waiting(req, Policy.Any), waiting.state.request)
        assertEquals(waiting.state, reduce(waiting.state, HostEvent.DetailDone(2), null).state)
        assertNull((reduce(waiting.state, HostEvent.DetailDone(1), null).state.request as? Request.Waiting))
    }

    /** docs/17 §3: an in-app Play while the old player closes waits for its destroy, then launches once. */
    @Test
    fun `an in-app Play pressed while the previous player closes launches once, after its destroy`() {
        val req = inApp(1, ITEM_B)
        val waiting = run(home(Closing(1), Main.Shown), HostEvent.Arrive(req))
        assertEquals("nothing while it closes", emptyList<PlayerEffect>(), waiting.effects)
        assertEquals(Request.Waiting(req, Policy.Any), waiting.state.request)
        val rest = run(waiting.state, PlayerEvent.Destroyed(1), PlayerEvent.Created(2), PlayerEvent.Resumed(2))
        assertEquals(listOf(PlayerEffect.Launch(req)), rest.effects)
        assertEquals(Fullscreen(2), rest.state.player)
    }

    /** docs/17 §3: an in-app Play delivered into a shown mini player that the platform then stops is launched again once, whenever MainActivity hides. */
    @Test
    fun `a deliver interrupted by a stop relaunches its request once`() {
        val req = inApp(1, ITEM_B)
        listOf(
            listOf(PlayerEvent.Leaving(1), PlayerEvent.Destroyed(1)),
            listOf(HostEvent.MainHidden, PlayerEvent.Leaving(1), PlayerEvent.Destroyed(1), HostEvent.MainShown),
        ).forEach { tail ->
            val run = run(home(Pip(1), Main.Shown), HostEvent.Arrive(req), *tail.toTypedArray())
            assertEquals("$tail", listOf(PlayerEffect.Deliver(1, req), PlayerEffect.Launch(req)), run.effects)
            assertEquals("$tail", Launching(req.id), run.state.player)
        }
    }

    /** docs/28 §4: a group load skips the account-tree gate, so it neither waits on a resolving root nor drops on sign-in. */
    @Test
    fun `a group load bypasses a pending or signed-out root`() {
        listOf(Root.Pending, Root.SignedOut).forEach { root ->
            val req = group(1, ITEM_A)
            val step = reduce(PlayerFlow(main = Main.Shown, root = root), HostEvent.Arrive(req), null)
            assertEquals("$root", listOf(PlayerEffect.Launch(req)), step.effects)
            assertEquals("$root", Launching(req.id), step.state.player)
        }
    }

    /** docs/28 §4: an interrupted group expand is dropped, never relaunched; the executor launches again on its next Load. */
    @Test
    fun `an interrupted group expand is dropped without a relaunch`() {
        val req = group(1, ITEM_B)
        val from = home(Expanding(1, req), Main.Shown)
        listOf(
            listOf(PlayerEvent.Leaving(1), PlayerEvent.Destroyed(1)),
            listOf(PlayerEvent.PipEntered(1)),
            listOf(PlayerEvent.Created(2)),
        ).forEach { events ->
            val run = run(from, *events.toTypedArray())
            assertEquals("$events", emptyList<PlayerEffect>(), run.effects)
            assertEquals("$events", Request.None, run.state.request)
        }
    }

    /** docs/28 §4: a group load over a mini player while MainActivity is hidden waits until it is shown, then expands the mini player. */
    @Test
    fun `a group load waits while MainActivity is hidden over a PiP player`() {
        val req = group(1, ITEM_B)
        val waiting = run(home(Pip(1)), HostEvent.Arrive(req))
        assertEquals(emptyList<PlayerEffect>(), waiting.effects)
        assertEquals(Request.Waiting(req, Policy.Any), waiting.state.request)
        assertEquals(Pip(1), waiting.state.player)
        val shown = reduce(waiting.state, HostEvent.MainShown, ITEM_A)
        assertEquals(listOf(PlayerEffect.Deliver(1, req)), shown.effects)
        assertEquals(Expanding(1, req), shown.state.player)
    }

    /** docs/28 §4: any other request replacing a group's waiting load leaves the group, once; a newer group load, or nothing waiting, leaves nothing. */
    @Test
    fun `a request replacing a waiting group load leaves the group`() {
        val waiting = home(Closing(1), Main.Shown).copy(request = Request.Waiting(group(1, ITEM_A), Policy.Any))
        listOf(inApp(2, ITEM_B), play(2, ITEM_B), detail(2, ITEM_B)).forEach { newer ->
            val step = reduce(waiting, HostEvent.Arrive(newer), ITEM_A)
            assertEquals("$newer", listOf(PlayerEffect.LeaveGroup), step.effects)
            assertEquals("$newer", newer, (step.state.request as Request.Waiting).req)
        }
        assertEquals(emptyList<PlayerEffect>(), reduce(waiting, HostEvent.Arrive(group(2, ITEM_B)), ITEM_A).effects)
        val other = waiting.copy(request = Request.Waiting(inApp(1, ITEM_A), Policy.Any))
        assertEquals(emptyList<PlayerEffect>(), reduce(other, HostEvent.Arrive(inApp(2, ITEM_B)), ITEM_A).effects)
        assertEquals(emptyList<PlayerEffect>(), reduce(waiting.copy(request = Request.None), HostEvent.Arrive(inApp(2, ITEM_B)), ITEM_A).effects)
    }

    /** docs/17 §3: a start may run later than its step, so the owner names the one start it still stands behind. */
    @Test
    fun `each launch and deliver is the start the owner stands behind until a newer one`() {
        val first = inApp(1, ITEM_A)
        val launched = run(home(None, Main.Shown), HostEvent.Arrive(first))
        assertEquals(first.id, launched.state.issued)

        val older = inApp(2, ITEM_B)
        val newer = inApp(3, ITEM_C)
        val delivered = run(home(Fullscreen(1)), HostEvent.Arrive(older), HostEvent.Arrive(newer))
        assertEquals(listOf(PlayerEffect.Deliver(1, older), PlayerEffect.Deliver(1, newer)), delivered.effects)
        assertEquals(newer.id, delivered.state.issued)
    }

    /** docs/18 §2.1, docs/17 §3: an account change or leaving the app withdraws a start not yet run. */
    @Test
    fun `an account change or leaving the app withdraws a pending start`() {
        listOf(HostEvent.AccountReset, HostEvent.AppExit).forEach { event ->
            val launched = run(home(None, Main.Shown), HostEvent.Arrive(play(1, ITEM_A)))
            assertNull("$event", reduce(launched.state, event, null).state.issued)
        }
    }

    /** docs/17 §3: a player never starts from the background; a launch not yet run when MainActivity hides is dropped with its request. */
    @Test
    fun `MainActivity hiding drops a launch not yet run and its request`() {
        val launched = run(home(None, Main.Shown), HostEvent.Arrive(play(1, ITEM_A)))
        val hidden = reduce(launched.state, HostEvent.MainHidden, null)
        assertEquals(None, hidden.state.player)
        assertNull(hidden.state.issued)
        assertEquals(Request.None, hidden.state.request)
        assertEquals("a later reopen launches nothing", emptyList<PlayerEffect>(), run(hidden.state, HostEvent.MainShown).effects)
    }

    /** docs/17 §3: a deliver whose platform call fails leaves the mini player in PiP, and its request is not relaunched. */
    @Test
    fun `a failed deliver into a mini player leaves it in PiP and drops its request`() {
        val req = inApp(1, ITEM_B)
        val expanding = run(home(Pip(1), Main.Shown), HostEvent.Arrive(req))
        assertEquals(Expanding(1, req), expanding.state.player)
        val failed = reduce(expanding.state, HostEvent.LaunchFailed(req.id), ITEM_A)
        assertEquals(PlayerStep(expanding.state.copy(player = Pip(1))), failed)
        assertEquals("not relaunched at its destroy", emptyList<PlayerEffect>(), run(failed.state, PlayerEvent.Leaving(1), PlayerEvent.Destroyed(1)).effects)
        assertEquals("another request's failure changes nothing", PlayerStep(expanding.state), reduce(expanding.state, HostEvent.LaunchFailed(req.id + 1), ITEM_A))
    }

    /** docs/18 §2.1: the account tree goes with the account, so nothing settles against the old stack's root. */
    @Test
    fun `an account change returns the root to pending`() {
        listOf(Root.Home, Root.SignedOut).forEach { root ->
            assertEquals("$root", Root.Pending, reduce(PlayerFlow(root = root), HostEvent.AccountReset, null).state.root)
        }
    }

    /** docs/17 §3: a request waiting when MainActivity hides is dropped, so a later reopen never plays it unasked; a group load keeps waiting (docs/28 §4). */
    @Test
    fun `MainActivity hiding drops a waiting request but a group load`() {
        listOf(play(1, ITEM_B), inApp(1, ITEM_B), detail(1, ITEM_B), group(1, ITEM_B)).forEach { req ->
            val waiting = home(Closing(1), Main.Shown).copy(request = Request.Waiting(req, Policy.Any))
            val hidden = reduce(waiting, HostEvent.MainHidden, null).state
            val keeps = req.origin == Req.Origin.Group
            assertEquals("$req", if (keeps) waiting.request else Request.None, hidden.request)
            val reopened = run(hidden, PlayerEvent.Destroyed(1), HostEvent.MainShown)
            val launches = if (keeps) listOf(PlayerEffect.Launch(group(1, ITEM_B))) else emptyList()
            assertEquals("$req", launches, reopened.effects)
        }
    }

    /** docs/17 §3: a launch the owner emitted counts as a player, so a destroy that triggered it doesn't report playback over. */
    @Test
    fun `a launching player counts as active`() {
        assertTrue(PlayerFlow(Launching(1)).isActive)
        assertFalse(PlayerFlow().isActive)
    }

    /**
     * Every sequence of up to four events from five seeds, over Created, each player event aimed at the slot's,
     * a draining and a stale generation, and every host event, an in-app and a group request and a group stop among them;
     * the rules in [checkInvariants] hold after every step.
     */
    @Test
    fun `every event sequence up to length four keeps the invariants`() {
        val seeds = listOf(
            PlayerFlow(),
            home(None, Main.Shown),
            home(Fullscreen(1)).copy(request = Request.Waiting(play(1, ITEM_B), Policy.Fresh)),
            home(Pip(2), Main.Shown).copy(draining = setOf(1)),
            home(Expanding(2, play(1, ITEM_A)), Main.Shown).copy(draining = setOf(1), issued = 1),
        )
        seeds.forEach { seed ->
            ensure(PlayerStep(seed) == reduce(seed, PlayerEvent.Resumed(STALE), liveItem(seed))) { "seed $seed is settled" }
            var deepest = 0
            fun walk(state: PlayerFlow, path: Path, depth: Int) {
                deepest = maxOf(deepest, depth)
                if (depth == 4) return
                for (event in alphabet(state, path)) {
                    var read = false
                    val step = tv.jellybeam.player.reduce(state, event) { read = true; liveItem(state) }
                    val label = { "$event from $state" }
                    checkInvariants(state, event, step, path, label)
                    // The live item is read only where a Fresh request meets a PiP player: it stays, or the player closes.
                    val stayedOrClosed = (step.state.player is Pip && step.state.request == Request.None) ||
                        (step.state.player is Closing && step.effects.any { it is PlayerEffect.Close })
                    ensure(!read || stayedOrClosed) { "live item read off its branch: ${label()}" }
                    walk(step.state, path.after(state, event, step), depth + 1)
                }
            }
            walk(seed, Path(lastG = 2, nextId = 2, closed = emptySet(), launches = 0), depth = 0)
            assertEquals("seed $seed reaches depth four", 4, deepest)
        }
    }

    /** What the walk carries along one path: the next ids to mint, the generations already closed, launches since the last reset. */
    private data class Path(val lastG: Long, val nextId: Long, val closed: Set<Long>, val launches: Int) {
        fun after(before: PlayerFlow, event: FlowEvent, step: PlayerStep): Path = Path(
            lastG = if (event is PlayerEvent.Created) event.g else lastG,
            nextId = if (event is HostEvent.Arrive) nextId + 1 else nextId,
            closed = closed + step.effects.filterIsInstance<PlayerEffect.Close>().map { it.g },
            launches = (if (resetsLaunches(before, event, step.state)) 0 else launches) + step.effects.count { it is PlayerEffect.Launch },
        )
    }

    private fun alphabet(state: PlayerFlow, path: Path): List<FlowEvent> {
        val current = (state.player as? Player.Live)?.g ?: STALE
        val drainingG = state.draining.minOrNull() ?: STALE
        val waitingDetail = ((state.request as? Request.Waiting)?.req?.takeIf { it.kind is Req.Kind.OpenDetail })?.id
        return buildList {
            add(PlayerEvent.Created(path.lastG + 1))
            listOf(current, drainingG, STALE).distinct().forEach { addAll(playerEvents(it)) }
            add(HostEvent.Arrive(play(path.nextId, ITEM_A)))
            add(HostEvent.Arrive(play(path.nextId, ITEM_B)))
            add(HostEvent.Arrive(detail(path.nextId, ITEM_A)))
            add(HostEvent.Arrive(inApp(path.nextId, ITEM_A)))
            add(HostEvent.Arrive(group(path.nextId, ITEM_A)))
            add(if (state.main == Main.Shown) HostEvent.MainHidden else HostEvent.MainShown)
            Root.entries.filter { it != state.root }.forEach { add(HostEvent.RootChanged(it)) }
            add(HostEvent.AccountReset)
            add(HostEvent.AppExit)
            add(HostEvent.GroupStop)
            (state.player as? Launching)?.let { add(HostEvent.LaunchFailed(it.reqId)) }
            (state.player as? Expanding)?.let { add(HostEvent.LaunchFailed(it.req.id)) }
            add(HostEvent.LaunchFailed(STALE))
            waitingDetail?.let { add(HostEvent.DetailDone(it)) }
        }
    }

    private fun checkInvariants(before: PlayerFlow, event: FlowEvent, step: PlayerStep, path: Path, label: () -> String) {
        val after = step.state
        val effects = step.effects
        val beforeLive = before.player as? Player.Live
        val afterLive = after.player as? Player.Live
        fun same(expected: Any?, actual: Any?, rule: String) = ensure(expected == actual) { "$rule: expected $expected, was $actual; ${label()}" }
        fun holds(ok: Boolean, rule: String) = ensure(ok) { "$rule; ${label()}" }

        // Settled: the next step's stale event finds nothing to do.
        same(PlayerStep(after), reduce(after, PlayerEvent.Resumed(STALE), liveItem(after)), "settled")

        // A launch: one per step, into an empty slot, nothing draining, MainActivity shown, once per player instance.
        val launches = effects.filterIsInstance<PlayerEffect.Launch>()
        if (launches.isNotEmpty()) {
            same(1, launches.size, "one launch per step")
            same(Launching(launches.single().req.id), after.player, "a launch holds the slot")
            holds(after.draining.isEmpty(), "a launch waits for every older instance")
            same(Main.Shown, after.main, "a launch only while MainActivity is shown")
            same(0, if (resetsLaunches(before, event, after)) 0 else path.launches, "one launch per player instance")
        }
        // Launch and Deliver run on Home, a group load's anywhere; signed out holds only a group load.
        val started = effects.mapNotNull { (it as? PlayerEffect.Launch)?.req ?: (it as? PlayerEffect.Deliver)?.req }
        started.forEach { if (it.origin != Req.Origin.Group) same(Root.Home, after.root, "a start needs Home") }
        val held = (after.request as? Request.Waiting)?.req
        if (after.root == Root.SignedOut) holds(held == null || held.origin == Req.Origin.Group, "signed out holds nothing")

        // docs/17 §3: the owner stands behind the last start it emitted until an account change, an app exit or a hidden MainActivity withdraws it.
        val withdrawn = event == HostEvent.AccountReset || event == HostEvent.AppExit || (event == HostEvent.MainHidden && before.player is Launching)
        same(started.lastOrNull()?.id ?: if (withdrawn) null else before.issued, after.issued, "the start the owner stands behind")
        // docs/17 §3: a hidden MainActivity holds no launch and nothing waiting but a group load.
        if (event == HostEvent.MainHidden) {
            holds(after.player !is Launching, "nothing launches from the background")
            holds(held == null || held.origin == Req.Origin.Group, "a hidden MainActivity drops a waiting request")
        }

        // docs/28 §4: a group's waiting load replaced by another origin's request leaves the group, once; nothing else does.
        val replacedGroup = event is HostEvent.Arrive && event.req.origin != Req.Origin.Group &&
            (before.request as? Request.Waiting)?.req?.origin == Req.Origin.Group
        same(if (replacedGroup) 1 else 0, effects.count { it == PlayerEffect.LeaveGroup }, "leave group")

        // An event from a stale generation changes nothing; a draining one's never touches the slot.
        if (event is PlayerEvent.Callback) {
            when (event.g) {
                beforeLive?.g -> Unit
                in before.draining -> {
                    val launchedOnLastDrain = event is PlayerEvent.Destroyed && before.player == None && after.player is Launching
                    holds(after.player == before.player || launchedOnLastDrain, "a draining instance never changes the slot")
                    holds(effects.isEmpty() || launchedOnLastDrain, "a draining instance has no effect")
                }
                else -> same(PlayerStep(before), step, "a stale generation changes nothing")
            }
        }

        // A Fresh request never joins the player it shrank; a Deliver joins a fullscreen player or expands a shown mini player.
        val freshArrival = event is HostEvent.Arrive && expectedPolicy(before, event.req) == Policy.Fresh
        // A new generation is not the instance a Fresh request shrank, so Created lifts it.
        val freshWaiting = (before.request as? Request.Waiting)?.takeIf { it.policy == Policy.Fresh && event !is PlayerEvent.Created }?.req
        effects.filterIsInstance<PlayerEffect.Deliver>().forEach { deliver ->
            holds(!(freshArrival && deliver.req == (event as HostEvent.Arrive).req), "a fresh arrival never joins")
            holds(deliver.req != freshWaiting, "a fresh request never joins")
            when (val p = after.player) {
                is Expanding -> {
                    same(Expanding(deliver.g, deliver.req), p, "a deliver into PiP expands it")
                    same(Main.Shown, after.main, "a deliver into PiP only while MainActivity is shown")
                }
                else -> same(Fullscreen(deliver.g), p, "a deliver joins a fullscreen player")
            }
        }

        // A live player leaves the slot only at its own destroy or a newer create; a launch ends at a create, its own failure or a hidden MainActivity.
        if (beforeLive != null && afterLive?.g != beforeLive.g) {
            holds(event == PlayerEvent.Destroyed(beforeLive.g) || event is PlayerEvent.Created, "a live player leaves only at its destroy")
        }
        (before.player as? Launching)?.let { launching ->
            if (after.player != launching) {
                holds(
                    event is PlayerEvent.Created || event == HostEvent.LaunchFailed(launching.reqId) || event == HostEvent.MainHidden,
                    "a launch ends only at a create, its failure or a hidden MainActivity",
                )
            }
        }
        // A failed start empties its own launch, returns its own expand to PiP, and changes nothing else; a waiting request then settles.
        if (event is HostEvent.LaunchFailed) {
            val p = before.player
            when {
                p == Launching(event.reqId) -> holds(after.player == None || launches.isNotEmpty(), "a failed launch empties the slot")
                p is Expanding && p.req.id == event.reqId ->
                    holds(after.player == Pip(p.g) || started.isNotEmpty() || after.player == Closing(p.g), "a failed deliver stays in PiP")
                else -> same(PlayerStep(before), step, "another request's failure changes nothing")
            }
        }

        // An expand that ends without landing: a group load, or one an app exit, account change, group stop or failed
        // start took, is gone; otherwise a newer waiting request wins, else it is launched, delivered, held, or dropped by the sign-in gate.
        val expanding = before.player as? Expanding
        if (expanding != null && after.player != expanding) {
            val r = expanding.req
            val carried = held == r || r in started
            val retired = event == PlayerEvent.Resumed(expanding.g) || event == HostEvent.AppExit || event == HostEvent.AccountReset ||
                event == HostEvent.GroupStop || event == HostEvent.LaunchFailed(r.id) || r.origin == Req.Origin.Group
            when {
                retired -> holds(!carried, "a retired expand is never relaunched")
                before.request is Request.Waiting -> holds(!carried, "a newer waiting request replaces an interrupted expand")
                else -> holds(carried || after.root == Root.SignedOut, "an interrupted expand is relaunched once")
            }
        }

        // A close: once per instance, the slot's own, never a closing one, at most one per step.
        effects.filterIsInstance<PlayerEffect.Close>().forEach { close ->
            holds(close.g !in path.closed, "a player is closed once")
            same(beforeLive?.g, close.g, "a close is the slot's player")
            holds(before.player !is Closing, "a closing player is never closed again")
        }
        holds(effects.count { it is PlayerEffect.Close } <= 1, "one close per step")

        // docs/18 §2.1: an account change never closes a player, and drops the request, the root and any expand.
        if (event == HostEvent.AccountReset) {
            holds(effects.none { it is PlayerEffect.Close }, "an account change closes nothing")
            same(Request.None, after.request, "an account change drops the request")
            same(Root.Pending, after.root, "an account change drops the root")
            holds(after.player !is Expanding, "no expand outlives an account change")
        }
        if (event == HostEvent.AppExit) {
            same(Request.None, after.request, "leaving the app drops the request")
            same(Root.Pending, after.root, "leaving the app drops the root")
        }

        // The slot's generation is never draining.
        afterLive?.let { holds(it.g !in after.draining, "the slot's generation is not draining") }

        // Leaving and Destroyed empty the slot from any live state.
        afterLive?.let { live ->
            val left = reduce(after, PlayerEvent.Leaving(live.g), liveItem(after)).state
            val gone = reduce(left, PlayerEvent.Destroyed(live.g), liveItem(left)).state
            holds((gone.player as? Player.Live)?.g != live.g, "leaving and destroyed empty the slot")
        }

        holds(event is PlayerEvent.Created || after.draining.all { it in before.draining }, "draining only grows at a create")
    }

    /** Throws only when [ok] is false, so the message is built only for a failure. */
    private inline fun ensure(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError(message())
    }
    /** The spec's arrival rule, restated: an external PLAY over a covering player while MainActivity is hidden is Fresh. */
    private fun expectedPolicy(state: PlayerFlow, req: Req<*>): Policy {
        val covering = when (state.player) {
            is Fullscreen, is Shrinking, is Pip, is Expanding -> true
            None, is Launching, is Closing -> false
        }
        val externalPlay = req.origin == Req.Origin.External && req.kind is Req.Kind.Play
        return if (externalPlay && covering && state.main == Main.Hidden) Policy.Fresh else Policy.Any
    }

    private fun playerEvents(of: Long): List<PlayerEvent> = listOf(
        PlayerEvent.PipRequested(of),
        PlayerEvent.PipEntered(of),
        PlayerEvent.PipExited(of, started = true),
        PlayerEvent.PipExited(of, started = false),
        PlayerEvent.Resumed(of),
        PlayerEvent.Leaving(of),
        PlayerEvent.Destroyed(of),
    )

    private data class Run(val state: PlayerFlow, val effects: List<PlayerEffect>)

    /** Folds [events] through [reduce]; every player's live item is [ITEM_A]. */
    private fun run(from: PlayerFlow, vararg events: FlowEvent): Run =
        events.fold(Run(from, emptyList())) { acc, event ->
            val step = reduce(acc.state, event, liveItem(acc.state))
            Run(step.state, acc.effects + step.effects)
        }

    private fun liveItem(state: PlayerFlow): String? = if (state.player is Player.Live) ITEM_A else null

    /** [reduce] with a fixed live item. */
    private fun reduce(state: PlayerFlow, event: FlowEvent, liveItem: String?): PlayerStep = tv.jellybeam.player.reduce(state, event) { liveItem }

    private fun home(player: Player, main: Main = Main.Hidden) = PlayerFlow(player, main = main, root = Root.Home)

    private companion object {
        const val STALE = 999L
        const val LAUNCH = 50L
        const val ITEM_A = "item-a"
        const val ITEM_B = "item-b"
        const val ITEM_C = "item-c"

        fun play(id: Long, item: String, start: Req.Start = Req.Start.Resume, origin: Req.Origin = Req.Origin.External) = Req(id, Req.Kind.Play(item, start), origin)
        fun inApp(id: Long, item: String) = play(id, item, origin = Req.Origin.InApp)
        fun group(id: Long, item: String) = play(id, item, origin = Req.Origin.Group)
        fun detail(id: Long, item: String) = Req(id, Req.Kind.OpenDetail(item), Req.Origin.External)

        /** A launch count restarts when a player instance is created or a launch ends without one. */
        fun resetsLaunches(before: PlayerFlow, event: FlowEvent, after: PlayerFlow): Boolean =
            event is PlayerEvent.Created || (before.player is Launching && after.player != before.player)
    }
}
