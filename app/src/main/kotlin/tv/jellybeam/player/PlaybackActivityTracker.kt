package tv.jellybeam.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import tv.jellybeam.player.PlayerFlow.Main
import tv.jellybeam.player.PlayerFlow.Player
import tv.jellybeam.player.PlayerFlow.Request
import tv.jellybeam.player.PlayerFlow.Root

/**
 * docs/17 §3: the player slot, the generations still tearing down, and the one request waiting for
 * them. Each PlaybackActivity instance is a generation `g`, minted at create, so an older instance's
 * late callbacks are told apart from the current one's.
 */
internal data class PlayerFlow(
    val player: Player = Player.None,
    val draining: Set<Long> = emptySet(),
    val request: Request = Request.None,
    val main: Main = Main.Hidden,
    val root: Root = Root.Pending,
    /** The request whose Launch or Deliver was emitted last, which a start run later must still match; `null` once withdrawn. */
    val issued: Long? = null,
) {
    /** A player Activity instance is being launched or alive, or an older one hasn't been destroyed yet. */
    val isActive: Boolean get() = player !is Player.None || draining.isNotEmpty()

    sealed interface Player {
        /** In PiP, or headed into or out of it. */
        val inPip: Boolean get() = when (this) {
            is Shrinking, is Pip, is Expanding -> true
            None, is Launching, is Fullscreen, is Closing -> false
        }

        /** An instance on screen that isn't closing: fullscreen or [inPip]. */
        val open: Boolean get() = this is Fullscreen || inPip

        /** No player Activity, and none being launched. */
        data object None : Player

        /** Request [reqId]'s launch is in flight and no Activity exists for it yet. */
        data class Launching(val reqId: Long) : Player

        /** An Activity instance holds the slot. */
        sealed interface Live : Player {
            val g: Long
        }

        data class Fullscreen(override val g: Long) : Live

        /** PiP entry accepted, the platform's mode change not yet seen. */
        data class Shrinking(override val g: Long) : Live

        /** The platform reports this instance in PiP. */
        data class Pip(override val g: Long) : Live

        /** Leaving PiP for fullscreen on delivered [req]: requeued if the platform interrupts the expand, dropped if its start fails. */
        data class Expanding(override val g: Long, val req: Req<Req.Kind.Play>) : Live

        /** Finishing; only its destroy changes the slot. */
        data class Closing(override val g: Long) : Live
    }

    sealed interface Request {
        data object None : Request

        /** [policy] Fresh: arrived over a player covering a hidden MainActivity, so it never joins that player. */
        data class Waiting(val req: Req<*>, val policy: Policy) : Request
    }

    enum class Policy { Fresh, Any }

    /** docs/17 §3: set by MainActivity's onResume/onStop, not its lifecycle state, which a restart before onNewIntent hides. */
    enum class Main { Shown, Hidden }

    /** The account tree's stack: still resolving, on sign-in, or on a Home-rooted stack. */
    enum class Root { Pending, SignedOut, Home }
}

/** A play or detail request; [id] is minted by [PlaybackActivityTracker.arrive]; [K] lets a player start take only a Play. */
internal data class Req<out K : Req.Kind>(val id: Long, val kind: K, val origin: Origin) {
    sealed interface Kind {
        data class Play(val item: String, val start: Start = Start.Resume) : Kind
        data class OpenDetail(val item: String) : Kind
    }

    /** docs/05 "external": the saved position, the beginning, or an absolute start in milliseconds. */
    sealed interface Start {
        data object Resume : Start
        data object Beginning : Start
        data class At(val ms: Long) : Start
    }

    enum class Origin { External, InApp, Group }
}

/** Every input to [reduce]. */
internal sealed interface FlowEvent

/** A player Activity's lifecycle callback, tagged with its generation. */
internal sealed interface PlayerEvent : FlowEvent {
    val g: Long

    data class Created(override val g: Long) : PlayerEvent

    /** A callback from an instance after its create. */
    sealed interface Callback : PlayerEvent

    data class PipRequested(override val g: Long) : Callback
    data class PipEntered(override val g: Long) : Callback

    /** [started]: the exit is an expand back to fullscreen, not a dismissal. */
    data class PipExited(override val g: Long, val started: Boolean) : Callback
    data class Resumed(override val g: Long) : Callback

    /** `finish()` or `finishAndRemoveTask()` was called. */
    data class Leaving(override val g: Long) : Callback
    data class Destroyed(override val g: Long) : Callback
}

/** A request, or MainActivity and account-tree signals. */
internal sealed interface HostEvent : FlowEvent {
    /** Newest wins: replaces any waiting request. */
    data class Arrive(val req: Req<*>) : HostEvent
    data object MainShown : HostEvent
    data object MainHidden : HostEvent
    data class RootChanged(val root: Root) : HostEvent
    data object AccountReset : HostEvent

    /** MainActivity finishing. */
    data object AppExit : HostEvent

    /** docs/28 §1: a group create stops whatever player the core still counts. */
    data object GroupStop : HostEvent
    data class DetailDone(val id: Long) : HostEvent

    /** Request [reqId]'s start threw or was withdrawn: its own Launching empties the slot, its own Expanding stays in PiP. */
    data class LaunchFailed(val reqId: Long) : HostEvent
}

internal sealed interface PlayerEffect {
    /** `finishAndRemoveTask()` on generation [g]'s Activity, the only close that never leaves a ghost PiP task (docs/17 §3). */
    data class Close(val g: Long) : PlayerEffect

    /** Starts a player for [req]; no player Activity exists. */
    data class Launch(val req: Req<Req.Kind.Play>) : PlayerEffect

    /** Hands [req] to generation [g]'s Activity: the same platform call as [Launch], which singleInstance routes to its onNewIntent. */
    data class Deliver(val g: Long, val req: Req<Req.Kind.Play>) : PlayerEffect

    /** docs/05 "external": a signed-out PLAY is dropped with a toast rather than a player bound to fail. */
    data object SignInRequired : PlayerEffect

    /** docs/28 §4: a newer request replaced the group's waiting load, so this device can't follow the group and leaves it. */
    data object LeaveGroup : PlayerEffect
}

internal data class PlayerStep(val state: PlayerFlow, val effects: List<PlayerEffect> = emptyList())

/**
 * docs/17 §3: every transition, then the waiting request settled against the result. [liveItem]
 * reads the slot player's live session item, invoked only where a Fresh request meets its PiP.
 */
internal fun reduce(state: PlayerFlow, event: FlowEvent, liveItem: () -> String?): PlayerStep {
    val step = when (event) {
        is PlayerEvent.Created -> created(state, event.g)
        is PlayerEvent.Callback -> playerStep(state, event)
        is HostEvent -> hostStep(state, event)
    }
    return settle(step, liveItem)
}

/** Generation [g] takes the slot and any live instance drains; Fresh is about the instance the intent shrank, which [g] is not. */
private fun created(state: PlayerFlow, g: Long): PlayerStep {
    val player = state.player
    val draining = if (player is Player.Live) state.draining + player.g else state.draining
    val request = (state.request as? Request.Waiting)?.copy(policy = PlayerFlow.Policy.Any) ?: state.request
    return PlayerStep(requeue(state.copy(player = Player.Fullscreen(g), draining = draining, request = request), left = player))
}

/**
 * An event from a generation that is neither the slot's nor draining is dropped; a draining
 * generation's only effect is leaving the draining set at its destroy.
 */
private fun playerStep(state: PlayerFlow, event: PlayerEvent.Callback): PlayerStep {
    val player = state.player
    if (player !is Player.Live || player.g != event.g) {
        if (event is PlayerEvent.Destroyed && event.g in state.draining) return PlayerStep(state.copy(draining = state.draining - event.g))
        return PlayerStep(state)
    }
    val g = player.g
    val next: Player = when (event) {
        is PlayerEvent.PipRequested -> if (player is Player.Fullscreen) Player.Shrinking(g) else player
        is PlayerEvent.PipEntered -> when (player) {
            is Player.Fullscreen, is Player.Shrinking, is Player.Expanding -> Player.Pip(g)
            is Player.Pip, is Player.Closing -> player
        }
        is PlayerEvent.PipExited -> when {
            player !is Player.Pip -> player
            event.started -> Player.Fullscreen(g)
            else -> return PlayerStep(state.copy(player = Player.Closing(g)), listOf(PlayerEffect.Close(g)))
        }
        is PlayerEvent.Resumed -> if (player is Player.Closing) player else Player.Fullscreen(g)
        is PlayerEvent.Leaving -> Player.Closing(g)
        is PlayerEvent.Destroyed -> Player.None
    }
    // Resumed retires an expand's request: it landed.
    return PlayerStep(if (event is PlayerEvent.Resumed) state.copy(player = next) else requeue(state.copy(player = next), left = player))
}

/**
 * docs/17 §3: any other way out of Expanding relaunches its request once, unless a newer one waits;
 * docs/28 §4: a group load is dropped instead, since the executor launches again on its next Load.
 */
private fun requeue(next: PlayerFlow, left: Player): PlayerFlow =
    if (left is Player.Expanding && left.req.origin != Req.Origin.Group && next.player != left && next.request == Request.None) {
        next.copy(request = Request.Waiting(left.req, PlayerFlow.Policy.Any))
    } else {
        next
    }

private fun hostStep(state: PlayerFlow, event: HostEvent): PlayerStep = PlayerStep(
    when (event) {
        is HostEvent.Arrive -> {
            val next = state.copy(request = Request.Waiting(event.req, arrivalPolicy(state, event.req)))
            // docs/28 §4: a newer group load is the same group, anything else replacing one leaves it.
            val replacedGroup = (state.request as? Request.Waiting)?.req?.origin == Req.Origin.Group
            if (replacedGroup && event.req.origin != Req.Origin.Group) return PlayerStep(next, listOf(PlayerEffect.LeaveGroup))
            next
        }
        HostEvent.MainShown -> state.copy(main = Main.Shown)
        // docs/17 §3: nothing starts from the background or plays unasked on a later reopen; docs/28 §4: a group load waits.
        HostEvent.MainHidden -> {
            val launching = state.player is Player.Launching
            val group = (state.request as? Request.Waiting)?.req?.origin == Req.Origin.Group
            state.copy(
                main = Main.Hidden,
                player = if (launching) Player.None else state.player,
                issued = if (launching) null else state.issued,
                request = if (group) state.request else Request.None,
            )
        }
        is HostEvent.RootChanged -> state.copy(root = event.root)
        // docs/18 §2.1: an account change drops every request and start, an expand's too, and its stack; it never closes a player.
        HostEvent.AccountReset -> state.copy(
            player = (state.player as? Player.Expanding)?.let { Player.Fullscreen(it.g) } ?: state.player,
            request = Request.None,
            root = Root.Pending,
            issued = null,
        )
        // docs/17 §3: leaving the app closes a player in, or headed for, PiP, so no headless window outlives it; the account tree goes too.
        HostEvent.AppExit -> {
            val left = state.copy(request = Request.None, root = Root.Pending, issued = null)
            val p = state.player
            if (p is Player.Live && p.inPip) return PlayerStep(left.copy(player = Player.Closing(p.g)), listOf(PlayerEffect.Close(p.g)))
            left
        }
        // docs/28 §1: a group create closes any live player once; an expand's request goes with it, unrequeued.
        HostEvent.GroupStop -> {
            val p = state.player
            if (p is Player.Live && p.open) return PlayerStep(state.copy(player = Player.Closing(p.g)), listOf(PlayerEffect.Close(p.g)))
            state
        }
        is HostEvent.DetailDone -> if ((state.request as? Request.Waiting)?.req?.id == event.id) state.copy(request = Request.None) else state
        is HostEvent.LaunchFailed -> {
            val p = state.player
            when {
                p == Player.Launching(event.reqId) -> state.copy(player = Player.None)
                p is Player.Expanding && p.req.id == event.reqId -> state.copy(player = Player.Pip(p.g))
                else -> state
            }
        }
    },
)

/** An external PLAY over a player covering a hidden MainActivity is Fresh; everything else may join a live player. */
private fun arrivalPolicy(state: PlayerFlow, req: Req<*>): PlayerFlow.Policy {
    val covered = state.main == Main.Hidden && state.player.open
    return if (req.origin == Req.Origin.External && req.kind is Req.Kind.Play && covered) PlayerFlow.Policy.Fresh else PlayerFlow.Policy.Any
}

/** docs/17 §3: the waiting request against the step's result; a no-op on a settled state. */
private fun settle(step: PlayerStep, liveItem: () -> String?): PlayerStep {
    val state = step.state
    val waiting = state.request as? Request.Waiting ?: return step
    val req = waiting.req
    // docs/28 §4: a group load skips the account-tree gate; the group already runs on this account.
    if (req.origin != Req.Origin.Group) {
        when (state.root) {
            Root.Pending -> return step
            Root.SignedOut -> {
                val toast = req.origin == Req.Origin.External && req.kind is Req.Kind.Play
                return step.then(state.copy(request = Request.None), PlayerEffect.SignInRequired.takeIf { toast })
            }
            Root.Home -> Unit
        }
    }
    // OpenDetail waits on Home for JellybeamRoot, which answers with DetailDone.
    val play = Req(req.id, req.kind as? Req.Kind.Play ?: return step, req.origin)
    val consumed = state.copy(request = Request.None)
    val issuing = consumed.copy(issued = req.id)
    return when (val player = state.player) {
        Player.None ->
            if (state.draining.isEmpty() && state.main == Main.Shown) step.then(issuing.copy(player = Player.Launching(req.id)), PlayerEffect.Launch(play)) else step
        is Player.Launching, is Player.Shrinking, is Player.Expanding, is Player.Closing -> step
        is Player.Fullscreen -> if (waiting.policy == PlayerFlow.Policy.Any) step.then(issuing, PlayerEffect.Deliver(player.g, play)) else step
        is Player.Pip -> when (waiting.policy) {
            PlayerFlow.Policy.Any ->
                if (state.main == Main.Shown) step.then(issuing.copy(player = Player.Expanding(player.g, play)), PlayerEffect.Deliver(player.g, play)) else step
            // A request that shrank a fullscreen player keeps it in PiP for its own item, else replaces it (expanding out of a PiP entry can stop it).
            PlayerFlow.Policy.Fresh -> {
                val start = play.kind.start
                val action = newIntentAction(liveItem(), play.kind.item, start == Req.Start.Beginning, (start as? Req.Start.At)?.ms, group = false)
                if (action == NewIntentAction.Expand) step.then(consumed, null) else step.then(state.copy(player = Player.Closing(player.g)), PlayerEffect.Close(player.g))
            }
        }
    }
}

private fun PlayerStep.then(next: PlayerFlow, effect: PlayerEffect?): PlayerStep = PlayerStep(next, effects + listOfNotNull(effect))

/** What the tracker can do to a live player Activity; attached at its create, detached at its destroy. [liveItemId] is read only once the player reached PiP, never during its create. */
internal class PlayerHandle(val finishAndRemoveTask: () -> Unit, val liveItemId: () -> String?)

/**
 * What the tracker asks of the app for a request. [start] may run its platform call later: it then runs only if
 * [PlaybackActivityTracker.confirmStart] agrees, and reports a throw as [HostEvent.LaunchFailed]; [leaveGroup] leaves the running group.
 */
internal class RequestHost(val start: (Req<Req.Kind.Play>) -> Unit, val signInRequired: () -> Unit, val leaveGroup: () -> Unit)

/**
 * docs/17 §3: the one owner of player presence, PiP state and the waiting play request,
 * process-wide because the `singleInstance` PlaybackActivity and MainActivity never hold each other.
 * [send] is Main-only and its only writer; effects run after the new state is published, so a
 * re-entrant [send] from an effect sees it.
 */
internal class PlaybackActivityTracker {
    private val _state = MutableStateFlow(PlayerFlow())
    val state: StateFlow<PlayerFlow> = _state.asStateFlow()

    /** Main-only; keyed by generation, so only an instance's own destroy detaches its handle. */
    private val handles = mutableMapOf<Long, PlayerHandle>()
    private var lastGeneration = 0L
    private var lastRequestId = 0L

    /** Process-wide and built before any Context exists, so MainActivity attaches the app's at its create. */
    private var host: RequestHost? = null

    val isActive: Boolean get() = _state.value.isActive

    /** docs/28 §1: the slot's player is in PiP or headed into or out of it, so a refused group create offers to stop it. */
    val inPip: Boolean get() = _state.value.player.inPip

    /** The slot player's live session item (`null` with no player, or once its session ended). */
    private fun liveItemId(): String? = (_state.value.player as? Player.Live)?.let { handles[it.g] }?.liveItemId?.invoke()

    fun attachHost(host: RequestHost) {
        this.host = host
    }

    /** Mints the new Activity's generation and attaches its [handle]; the caller tags every later event with it. */
    fun created(handle: PlayerHandle): Long {
        val g = ++lastGeneration
        handles[g] = handle
        send(PlayerEvent.Created(g))
        return g
    }

    fun destroyed(g: Long) {
        handles.remove(g)
        send(PlayerEvent.Destroyed(g))
    }

    /** Mints the request's id and sends it. */
    fun arrive(kind: Req.Kind, origin: Req.Origin) = send(HostEvent.Arrive(Req(++lastRequestId, kind, origin)))

    fun send(event: FlowEvent) {
        val step = reduce(_state.value, event, ::liveItemId)
        _state.value = step.state
        step.effects.forEach(::execute)
    }

    /**
     * docs/17 §3: whether request [id]'s start, run after its step, is still the one this owner stands behind;
     * a withdrawn one is sent as [HostEvent.LaunchFailed], so its Launching never holds the slot.
     */
    fun confirmStart(id: Long): Boolean {
        if (_state.value.issued == id) return true
        send(HostEvent.LaunchFailed(id))
        return false
    }

    /** docs/28 §1: stops the slot's player and returns once it is destroyed, by which time its final stop is counted. */
    suspend fun stopForGroup() {
        val g = (_state.value.player as? Player.Live)?.g ?: return
        send(HostEvent.GroupStop)
        _state.first { (it.player as? Player.Live)?.g != g && g !in it.draining }
    }

    private fun execute(effect: PlayerEffect) {
        when (effect) {
            is PlayerEffect.Close -> handles[effect.g]?.finishAndRemoveTask?.invoke()
            is PlayerEffect.Launch -> start(effect.req)
            is PlayerEffect.Deliver -> start(effect.req)
            PlayerEffect.SignInRequired -> host?.signInRequired?.invoke()
            PlayerEffect.LeaveGroup -> host?.leaveGroup?.invoke()
        }
    }

    private fun start(req: Req<Req.Kind.Play>) {
        val host = host
        if (host == null) send(HostEvent.LaunchFailed(req.id)) else host.start(req)
    }

    /** docs/28 §3.3: final stops launched whose core call hasn't returned; from then on the core holds each. */
    private val finalStops = MutableStateFlow(0)

    fun onFinalStopLaunched() = finalStops.update { it + 1 }
    fun onFinalStopHandedOff() = finalStops.update { (it - 1).coerceAtLeast(0) }
    suspend fun awaitFinalStops() = finalStops.first { it == 0 }
}

internal val playbackActivityTracker = PlaybackActivityTracker()
