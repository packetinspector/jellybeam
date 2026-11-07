package tv.jellybeam.ui.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.player.music.MusicPlaybackOwner

/** docs/29 §6: input-free time on any screen, while ambient may count, before it draws. */
internal const val AMBIENT_IDLE_MS = 2 * 60_000L

/** docs/29 §6 M2: paused time while drawing before the screen goes back to the system screensaver. */
internal const val AMBIENT_PAUSED_RELEASE_MS = 10 * 60_000L

/**
 * docs/29 §6 (M1/M2): the ambient screen over any screen while music has a session, UI only; times are [AmbientController]'s clock in ms.
 * Only [Drawing] keeps the screen on.
 */
internal sealed interface Ambient {
    /** Not drawing; [idleSince] starts the idle count, `null` while something blocks ambient. */
    data class Off(val idleSince: Long?, val paused: Boolean) : Ambient

    /** Drawing since [startedAt]; [pausedSince] counts toward release; [startPoint] picks the drift start, one per track. */
    data class Drawing(val startedAt: Long, val pausedSince: Long?, val startPoint: Int = 0) : Ambient

    /** Paused too long: nothing draws and the screen is the system's again. */
    data object Released : Ambient
}

internal sealed interface AmbientEvent {
    /** Ambient may count ([ambientMayCount]). */
    data class Shown(val now: Long) : AmbientEvent

    /** Something blocks it: the app isn't in front, a dialog or menu is open, or the keyboard shows. */
    data object Hidden : AmbientEvent
    data class Key(val now: Long) : AmbientEvent
    data class Tick(val now: Long) : AmbientEvent
    data object TrackChanged : AmbientEvent
    data class Paused(val now: Long) : AmbientEvent
    data class Resumed(val now: Long) : AmbientEvent

    /** Nothing plays any more. */
    data object Ended : AmbientEvent
}

internal val AMBIENT_START: Ambient = Ambient.Off(idleSince = null, paused = false)

/** docs/29 §6: every ambient transition. */
internal fun reduceAmbient(s: Ambient, e: AmbientEvent): Ambient = when (e) {
    is AmbientEvent.Shown -> if (s is Ambient.Off && s.idleSince == null) s.copy(idleSince = e.now) else s
    AmbientEvent.Hidden -> Ambient.Off(idleSince = null, paused = s.isPaused)
    AmbientEvent.Ended -> AMBIENT_START
    is AmbientEvent.Key -> when (s) {
        is Ambient.Off -> if (s.idleSince == null) s else s.copy(idleSince = e.now)
        is Ambient.Drawing, Ambient.Released -> Ambient.Off(idleSince = e.now, paused = s.isPaused)
    }
    is AmbientEvent.Tick -> when (s) {
        is Ambient.Off -> if (s.idleSince != null && e.now - s.idleSince >= AMBIENT_IDLE_MS) {
            Ambient.Drawing(startedAt = e.now, pausedSince = e.now.takeIf { s.paused })
        } else {
            s
        }
        is Ambient.Drawing -> if (s.pausedSince != null && e.now - s.pausedSince >= AMBIENT_PAUSED_RELEASE_MS) Ambient.Released else s
        Ambient.Released -> s
    }
    AmbientEvent.TrackChanged -> if (s is Ambient.Drawing) s.copy(startPoint = s.startPoint + 1) else s
    is AmbientEvent.Paused -> when (s) {
        is Ambient.Off -> s.copy(paused = true)
        is Ambient.Drawing -> if (s.pausedSince == null) s.copy(pausedSince = e.now) else s
        Ambient.Released -> s
    }
    is AmbientEvent.Resumed -> when (s) {
        is Ambient.Off -> s.copy(paused = false)
        is Ambient.Drawing -> s.copy(pausedSince = null)
        Ambient.Released -> Ambient.Off(idleSince = e.now, paused = false)
    }
}

private val Ambient.isPaused: Boolean
    get() = when (this) {
        is Ambient.Off -> paused
        is Ambient.Drawing -> pausedSince != null
        Ambient.Released -> true
    }

/** When the next [AmbientEvent.Tick] is due; `null` means no timer exists. */
internal fun ambientDeadline(s: Ambient): Long? = when (s) {
    is Ambient.Off -> s.idleSince?.plus(AMBIENT_IDLE_MS)
    is Ambient.Drawing -> s.pausedSince?.plus(AMBIENT_PAUSED_RELEASE_MS)
    Ambient.Released -> null
}

/** docs/29 §6: a key while ambient shows only closes it, so it is consumed. */
internal fun ambientConsumesKey(s: Ambient): Boolean = s !is Ambient.Off

/**
 * docs/29 §6: whether a press ([down]) or release of [key] is consumed; [endsAmbient] counts each press as input.
 * A key that ended ambient stays in [swallowed], its auto-repeats consumed with it, until its release.
 */
internal fun <K> ambientKeyConsumed(down: Boolean, key: K, swallowed: MutableSet<K>, endsAmbient: () -> Boolean): Boolean {
    if (!down) return swallowed.remove(key)
    if (endsAmbient()) swallowed += key
    return key in swallowed
}

/** docs/29 §6: ambient counts only while the app is in front ([resumed]) with no dialog or menu open and no keyboard, which takes the keys. */
internal fun ambientMayCount(resumed: Boolean, blockers: Int, imeVisible: Boolean): Boolean = resumed && blockers == 0 && !imeVisible

/** What ambient follows from the player: the playing entry and whether the user paused; `null` once nothing plays. */
internal data class AmbientSource(val item: String, val paused: Boolean)

/** docs/29 §6: the ambient inputs one player change makes, at [now]; the first source after [prev] `null` only sets the pause. */
internal fun ambientInputs(prev: AmbientSource?, next: AmbientSource?, now: Long): List<AmbientEvent> = when {
    next == null -> if (prev == null) emptyList() else listOf(AmbientEvent.Ended)
    else -> listOfNotNull(
        AmbientEvent.TrackChanged.takeIf { prev != null && prev.item != next.item },
        (if (next.paused) AmbientEvent.Paused(now) else AmbientEvent.Resumed(now)).takeIf { prev?.paused != next.paused },
    )
}

/**
 * docs/29 §6: the app's one ambient owner, the only writer of [state] and holder of its one idle timer.
 * Its jobs run in [scope], which ends with the music session, so nothing counts without one.
 */
internal class AmbientController(
    private val owner: MusicPlaybackOwner,
    private val scope: CoroutineScope,
    /** Monotonic ms for the ambient timers. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val _state = MutableStateFlow(AMBIENT_START)
    val state: StateFlow<Ambient> = _state.asStateFlow()

    /** The one pending tick; it exists only while [ambientDeadline] names one, so never while blocked. */
    private var timer: Job? = null
    private var following: Job? = null

    /** [mayCount] is [ambientMayCount]; the player is followed only while it holds. */
    fun setMayCount(mayCount: Boolean) {
        following?.cancel()
        send(if (mayCount) AmbientEvent.Shown(clock()) else AmbientEvent.Hidden)
        following = if (mayCount) scope.launch { follow() } else null
    }

    /** docs/29 §6: every key press; `true` when ambient showed, so the key only closes it. */
    fun key(): Boolean {
        val consumed = ambientConsumesKey(_state.value)
        send(AmbientEvent.Key(clock()))
        return consumed
    }

    private fun send(event: AmbientEvent) {
        _state.update { reduceAmbient(it, event) }
        timer?.cancel()
        val due = ambientDeadline(_state.value) ?: return
        timer = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            send(AmbientEvent.Tick(clock()))
        }
    }

    private suspend fun follow() {
        var prev: AmbientSource? = null
        combine(owner.state, owner.now) { flow, now -> musicQueueView(flow, now)?.now?.let { AmbientSource(it.queue[it.current].item, it.paused) } }
            .distinctUntilChanged()
            .collect { next ->
                ambientInputs(prev, next, clock()).forEach(::send)
                prev = next
            }
    }
}
