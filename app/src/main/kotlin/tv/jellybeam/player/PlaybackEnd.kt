package tv.jellybeam.player

import androidx.media3.common.Player
import tv.jellybeam.player.PlaybackEnd.Live
import tv.jellybeam.player.PlaybackEnd.NoLoad
import tv.jellybeam.player.PlaybackEnd.Over
import kotlin.math.abs

/** docs/18 §7: a gated segment as the end reads it; [ms] is a real segment's end, a stub's start. */
internal data class Seg(val index: Int, val ms: Long)

/** docs/18 §7: what one load's server has sent; Direct Play stays [Flowing] with nothing read. */
internal sealed interface Media {
    /** The highest-indexed segment with media read on this load. */
    val real: Seg?

    data class Flowing(override val real: Seg?) : Media

    /** [stub]: the start of the lowest run of segments without video. */
    data class Tripped(override val real: Seg?, val stub: Seg) : Media
}

/** docs/18 §7: the end is known only when the stubs directly follow real media read on this load; null otherwise. */
internal val Media.Tripped.endMs: Long? get() = stub.ms.takeIf { real != null && real.index == stub.index - 1 }

/** docs/18 §7's safety net per load: off until a frame shows since the load (re)prepared. */
internal sealed interface Watch {
    data object Opening : Watch
    data object Moving : Watch

    /** Stalled at [atMs] since monotonic [sinceMs], with no other observation between. */
    data class Frozen(val sinceMs: Long, val atMs: Long) : Watch
}

internal enum class Ending { PLAYED_THROUGH, NATURAL, PAST_END, NO_MEDIA, STUCK }

/** One load ([seq] = the holder's load generation); [recoveredAtMs]: where its one recovery re-seek was spent. */
internal data class Load(val seq: Long, val media: Media = Media.Flowing(null), val recoveredAtMs: Long? = null) {
    val recovered: Boolean get() = recoveredAtMs != null
}

/** docs/18 §7: how the current load ends, written only by [reducePlaybackEnd]. */
internal sealed interface PlaybackEnd {
    data object NoLoad : PlaybackEnd
    data class Live(val load: Load, val watch: Watch) : PlaybackEnd
    data class Over(val load: Load, val ending: Ending) : PlaybackEnd
}

/** Who a stall's end belongs to: [HELD] while no session is live or a renegotiation is out. */
enum class Audience { SOLO, GROUP, HELD }

/** One live read of the player; [phase] is a `Player.STATE_*`, [seekInFlight] is [SeekSerializer]'s. */
data class EndFacts(
    val phase: Int,
    val playWhenReady: Boolean,
    val loading: Boolean,
    val seekInFlight: Boolean,
    val positionMs: Long,
    val bufferedMs: Long,
    val bufferedToListedEnd: Boolean,
    val audience: Audience,
)

/** Inputs to [reducePlaybackEnd]; holder and gate events carry the load generation that produced them. Public only
 * because [PlaybackPlayer.onEndInput] carries them. */
sealed interface EndEvent {
    data class LoadOpened(val seq: Long) : EndEvent
    data class Reprepared(val seq: Long) : EndEvent
    data class FirstFrame(val seq: Long) : EndEvent
    data class MediaArrived(val seq: Long, val index: Int, val endMs: Long) : EndEvent
    data class Tripped(val seq: Long, val index: Int, val startMs: Long) : EndEvent

    /** [nowMs] is monotonic: a wall-clock jump must not stretch or cut the safety net's window. */
    data class Observed(val facts: EndFacts, val nowMs: Long) : EndEvent

    /** The player failed; [facts] read as it reported the error. */
    data class Failed(val facts: EndFacts) : EndEvent
    data object Released : EndEvent
}

internal sealed interface EndEffect {
    /** A normal end: next-up or exit solo, the group's own end in a group. */
    data object PlayedThrough : EndEffect
    data object PastEnd : EndEffect
    data object NoMedia : EndEffect

    /** The viewer's manual fix, once per load, before the safety net stops playback. */
    data class RecoverSeek(val positionMs: Long) : EndEffect
    data class StopStuck(val toListedEnd: Boolean, val positionMs: Long, val aheadMs: Long) : EndEffect
}

/** At most one effect per step, by construction. */
internal data class PlaybackEndStep(val state: PlaybackEnd, val effect: EndEffect? = null)

/** docs/18 §7: the one transition function for how a load ends; events from any load but the current one change nothing. */
internal fun reducePlaybackEnd(state: PlaybackEnd, event: EndEvent): PlaybackEndStep {
    val load = state.load
    fun current(seq: Long) = load?.seq == seq
    val next = when (event) {
        is EndEvent.LoadOpened -> Live(Load(event.seq), Watch.Opening)
        EndEvent.Released -> NoLoad
        is EndEvent.Reprepared -> if (state is Live && current(event.seq)) state.copy(watch = Watch.Opening) else state
        is EndEvent.FirstFrame ->
            if (state is Live && current(event.seq) && state.watch == Watch.Opening) state.copy(watch = Watch.Moving) else state
        is EndEvent.MediaArrived ->
            if (load != null && current(event.seq)) state.with(load.copy(media = load.media.arrived(Seg(event.index, event.endMs)))) else state
        is EndEvent.Tripped ->
            if (load != null && current(event.seq)) state.with(load.copy(media = load.media.stubbed(Seg(event.index, event.startMs)))) else state
        is EndEvent.Observed -> return when (state) {
            is Live -> observe(state, event.facts, event.nowMs)
            is Over -> PlaybackEndStep(revoked(state, event.facts))
            NoLoad -> PlaybackEndStep(state)
        }
        is EndEvent.Failed -> return failed(state, event.facts)
    }
    return PlaybackEndStep(next)
}

/** docs/18 §7: the stop report never lands in the stubs; a played-through end counts as watched. */
internal fun endStopTicks(end: PlaybackEnd, reachedTicks: Long, runtimeTicks: Long?): Long {
    val media = end.load?.media ?: return reachedTicks
    val real = media.real
    return when {
        end is Over && end.ending == Ending.PLAYED_THROUGH && runtimeTicks != null -> runtimeTicks
        end is Over && end.ending == Ending.PAST_END && real != null ->
            PlaybackTicks.msToTicks(real.ms - END_REACH_MS).coerceAtLeast(0L)
        media is Media.Tripped && reachedTicks >= PlaybackTicks.msToTicks(media.stub.ms) ->
            real?.let { PlaybackTicks.msToTicks(it.ms) } ?: reachedTicks
        else -> reachedTicks
    }
}

private val PlaybackEnd.load: Load? get() = when (this) {
    is Live -> load
    is Over -> load
    NoLoad -> null
}

private fun PlaybackEnd.with(load: Load): PlaybackEnd = when (this) {
    is Live -> copy(load = load)
    is Over -> copy(load = load)
    NoLoad -> this
}

private fun Media.arrived(seg: Seg): Media {
    val real = real?.takeIf { it.index >= seg.index } ?: seg
    return when (this) {
        is Media.Flowing -> copy(real = real)
        is Media.Tripped -> copy(real = real)
    }
}

/** A lower run re-trips lower; a higher one never raises the end. */
private fun Media.stubbed(seg: Seg): Media = when (this) {
    is Media.Flowing -> Media.Tripped(real, seg)
    is Media.Tripped -> if (seg.index < stub.index) copy(stub = seg) else this
}

private fun observe(live: Live, f: EndFacts, nowMs: Long): PlaybackEndStep {
    val group = f.audience == Audience.GROUP
    // The one end report, solo or group (docs/28 §5: the group hears it as PlayerEnded).
    if (f.phase == Player.STATE_ENDED) return PlaybackEndStep(Over(live.load, Ending.NATURAL), EndEffect.PlayedThrough)
    // Never while paused, seeking (the serializer's until it lands or times out) or held.
    val stall = f.playWhenReady && f.phase == Player.STATE_BUFFERING && !f.seekInFlight && f.audience != Audience.HELD
    if (stall) {
        tripEnding(live.load.media, f)?.let { return PlaybackEndStep(Over(live.load, it), tripEffect(it, group)) }
    }
    return net(live, f, nowMs, candidate = stall && !f.loading)
}

/** docs/18 §7: a stall once tripped, whatever is loading (a refused load is held, never failed); null decides nothing. */
private fun tripEnding(media: Media, f: EndFacts): Ending? {
    if (media !is Media.Tripped) return null
    if (media.real == null) return Ending.NO_MEDIA
    val end = media.endMs ?: return Ending.PAST_END.takeIf { f.positionMs >= media.stub.ms }
    return when {
        f.positionMs > end + END_SLACK_MS -> Ending.PAST_END
        // The last real segment can hold a single frame, so samples may stop anywhere in the last two.
        f.positionMs >= end - END_REACH_MS && f.bufferedMs >= end - END_SLACK_MS -> Ending.PLAYED_THROUGH
        else -> null
    }
}

/** docs/28: a group member leaves an unplayable end through the error path, and plays through like a natural end. */
private fun tripEffect(ending: Ending, group: Boolean): EndEffect = when (ending) {
    Ending.PLAYED_THROUGH -> EndEffect.PlayedThrough
    Ending.PAST_END -> if (group) EndEffect.NoMedia else EndEffect.PastEnd
    else -> EndEffect.NoMedia
}

/** docs/18 §7's safety net: [STALLED_FOR_GOOD_MS] of continuous candidate stall at one position re-seeks once per load, then stops. */
private fun net(live: Live, f: EndFacts, nowMs: Long, candidate: Boolean): PlaybackEndStep {
    val watch = live.watch
    return when {
        watch == Watch.Opening -> PlaybackEndStep(live)
        !candidate -> PlaybackEndStep(if (watch == Watch.Moving) live else live.copy(watch = Watch.Moving))
        watch !is Watch.Frozen || watch.atMs != f.positionMs -> PlaybackEndStep(live.copy(watch = Watch.Frozen(nowMs, f.positionMs)))
        nowMs - watch.sinceMs < STALLED_FOR_GOOD_MS -> PlaybackEndStep(live)
        !live.load.recovered ->
            PlaybackEndStep(Live(live.load.copy(recoveredAtMs = f.positionMs), Watch.Moving), EndEffect.RecoverSeek(f.positionMs))
        else -> stuck(live.load, f)
    }
}

/** docs/18 §7: a failure where the spent recovery left playback stuck is that stall's end, not a new error. */
private fun failed(state: PlaybackEnd, f: EndFacts): PlaybackEndStep {
    val at = (state as? Live)?.load?.recoveredAtMs
    return if (at != null && abs(f.positionMs - at) <= END_SLACK_MS) stuck(state.load, f) else PlaybackEndStep(state)
}

private fun stuck(load: Load, f: EndFacts) =
    PlaybackEndStep(Over(load, Ending.STUCK), EndEffect.StopStuck(f.bufferedToListedEnd, f.positionMs, f.bufferedMs - f.positionMs))

/** A deferred normal end (still-watching up, or its decision pending) is revoked once playback moves on. */
private fun revoked(over: Over, f: EndFacts): PlaybackEnd {
    val normal = over.ending == Ending.PLAYED_THROUGH || over.ending == Ending.NATURAL
    return when {
        !normal -> over
        f.seekInFlight -> Live(over.load, Watch.Opening) // a seek resets the renderers: its first frame re-arms the net
        f.phase == Player.STATE_READY -> Live(over.load, Watch.Moving)
        else -> over
    }
}

internal const val END_SLACK_MS = 1_000L
internal const val END_REACH_MS = 2 * OutOfMediaGate.SEGMENT_MS
internal const val STALLED_FOR_GOOD_MS = 15_000L
