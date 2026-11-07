package tv.jellybeam.player.music

import tv.jellybeam.player.music.MusicFlow.Net
import tv.jellybeam.player.music.MusicFlow.Pending
import tv.jellybeam.player.music.MusicFlow.Phase
import tv.jellybeam.player.music.MusicFlow.Refusal
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.SongSource

/** What a queue plays from; the reducer only compares it, the owner resolves it to a fetch. */
internal sealed interface MusicSource {
    data class Album(val id: String) : MusicSource
    data class Artist(val id: String) : MusicSource
    data class Songs(val source: SongSource) : MusicSource
    data class Playlist(val id: String) : MusicSource
    data class Mix(val seed: MixSeedFfi) : MusicSource

    /** One song the caller already holds, so it plays with its metadata and no fetch. */
    data class Single(val song: Card) : MusicSource
}

/** The [n]th track start reported in session [sid]: each one is ended exactly once. */
internal data class TrackRef(val sid: Long, val n: Int)

/** One queue, fetched under account [epoch]; [items] is the queue the player is built from, played from index [start], in shuffle when [shuffled]. */
internal data class MusicSession(
    val sid: Long,
    val epoch: ULong,
    val source: MusicSource,
    val items: List<QueueEntry>,
    val start: Int = 0,
    val shuffled: Boolean = false,
)

/**
 * The music owner's whole state: the session's phase, the play request waiting on its fetch and the notice the bar
 * shows once. [lastSid] and [lastTicket] are the last ones minted, so an older session's or service start's late result is told apart.
 */
internal data class MusicFlow(
    val phase: Phase = Phase.Idle,
    val pending: Pending = Pending.None,
    val lastSid: Long = 0,
    val lastTicket: Long = 0,
    val notice: MusicNotice? = null,
) {
    /** docs/26 §1: a starting or playing session holds an update install back, as a video player does. */
    val holdsPlayback: Boolean get() = phase != Phase.Idle

    sealed interface Phase {
        data object Idle : Phase

        /** Service start [ticket] was asked for and no instance has attached for it yet. */
        data class Starting(val session: MusicSession, val ticket: Long) : Phase

        /** Service instance [g] plays [session]; [n] is the playing track's report index. */
        data class Active(val session: MusicSession, val g: Long, val n: Int, val net: Net) : Phase {
            val ref: TrackRef get() = TrackRef(session.sid, n)
        }
    }

    enum class Net { Online, Lost }

    sealed interface Pending {
        data object None : Pending

        /** Request [r]'s fetch is in flight, minted under account [epoch]; its session starts shuffled when [shuffled]. */
        data class Fetch(val r: Long, val epoch: ULong, val source: MusicSource, val shuffled: Boolean = false) : Pending

        /** Request [r]'s fetch of [source] found nothing to play or failed; the session is untouched. */
        data class Refused(val r: Long, val reason: Refusal, val source: MusicSource) : Pending
    }

    enum class Refusal { Empty, Failed }
}

/** A failure the bar shows once; it names its cause, so a repeat is a new notice. */
internal sealed interface MusicNotice {
    /** Enqueue request [r]'s fetch failed; the playing queue is unchanged. */
    data class EnqueueFailed(val r: Long) : MusicNotice

    /** Service start [ticket] was refused or never attached, so nothing plays. */
    data class StartFailed(val ticket: Long) : MusicNotice

    /** docs/29 §8.4: request [r] queued [title] as [mode]. */
    data class Queued(val r: Long, val mode: EnqueueMode, val title: String) : MusicNotice

    /** docs/29 §8.4: write [id] added to [playlist] verbatim; [again] when it already held the song. */
    data class AddedToPlaylist(val id: Long, val playlist: String, val again: Boolean) : MusicNotice

    /** Write [id] to [playlist] failed; nothing was added. */
    data class PlaylistFailed(val id: Long, val playlist: String) : MusicNotice
}

internal enum class RepeatMode { Off, All, One }

/** A transport or queue command, applied by the service's player as given. */
internal sealed interface TransportOp {
    data object Pause : TransportOp
    data object Next : TransportOp
    data object Previous : TransportOp
    data class SeekTo(val ms: Long) : TransportOp
    data class PlayAt(val index: Int) : TransportOp
    data class Shuffle(val on: Boolean) : TransportOp
    data class Repeat(val mode: RepeatMode) : TransportOp

    /** docs/29 §9 H: rows [from] and [to] are positions in play order, as the Queue screen lists them. */
    data class Move(val from: Int, val to: Int) : TransportOp

    /** docs/29 §9 H: the queued timeline entry [index] plays next, keeping its place in the queue as it moves. */
    data class PlayNext(val index: Int) : TransportOp

    /** Removes the timeline entry [index]; the playing one stays. */
    data class Remove(val index: Int) : TransportOp
    data object Clear : TransportOp
}

/** Every input to [reduce]; an async result carries the request [r], session `sid`, service `ticket` or instance `g` that started it. */
internal sealed interface MusicEvent {
    /** Request [r] for [source] from its row [startIndex], minted under account [epoch]; [shuffled] starts it in shuffle. */
    data class Play(val r: Long, val source: MusicSource, val epoch: ULong, val startIndex: Int = 0, val shuffled: Boolean = false) : MusicEvent

    /** Play next or Add to queue, minted under account [epoch]; with nothing playing it plays [source] as request [r]. [title] is what the bar confirms. */
    data class Enqueue(val r: Long, val mode: EnqueueMode, val source: MusicSource, val epoch: ULong, val title: String) : MusicEvent
    data class CancelFetch(val r: Long) : MusicEvent
    data object Stop : MusicEvent
    data object UserPlay : MusicEvent
    data class Transport(val op: TransportOp) : MusicEvent

    /** A non-empty result to play from [startIndex]; the owner sends an empty one as [FetchEmpty]. */
    data class Fetched(val r: Long, val items: List<String>, val startIndex: Int = 0) : MusicEvent {
        init {
            require(items.isNotEmpty()) { "an empty fetch is FetchEmpty" }
            require(startIndex in items.indices) { "the start row is in the result" }
        }
    }
    data class FetchEmpty(val r: Long) : MusicEvent
    data class FetchFailed(val r: Long) : MusicEvent
    data class EnqueueFetched(val sid: Long, val mode: EnqueueMode, val items: List<String>) : MusicEvent

    /** Enqueue request [r]'s fetch in session [sid] could not be read. */
    data class EnqueueFailed(val sid: Long, val r: Long) : MusicEvent

    /** The bar showed [notice]. */
    data class NoticeShown(val notice: MusicNotice) : MusicEvent

    /** docs/29 §8.4: a confirmation for the bar, which shows only while a session holds playback. */
    data class Confirm(val notice: MusicNotice) : MusicEvent
    data class ServiceReady(val g: Long, val ticket: Long) : MusicEvent

    /** Service start [ticket] could not start, so no instance will attach for it. */
    data class ServiceStartFailed(val ticket: Long) : MusicEvent
    data class ServiceDestroyed(val g: Long, val ticks: Long) : MusicEvent

    /** The player moved to [item]; [prevTicks] is where the previous track stopped. */
    data class TrackChanged(val sid: Long, val item: String, val prevTicks: Long) : MusicEvent
    data class QueueEnded(val sid: Long, val ticks: Long) : MusicEvent
    data class NetworkLost(val sid: Long) : MusicEvent

    /** A non-network track failure; [isLast]: no track follows it. */
    data class PlayerFailed(val sid: Long, val isLast: Boolean) : MusicEvent
    data object VideoStarting : MusicEvent

    /** The account epochs: the current one and the one a kept playback runs on, if any. */
    data class EpochsChanged(val current: ULong, val parked: ULong?) : MusicEvent
}

/** Where an ended track stopped: known from the event, or the player's position when the effect runs. */
internal sealed interface EndAt {
    data class Ticks(val ticks: Long) : EndAt
    data object PlayerPosition : EndAt
}

internal data class TrackEnd(val ref: TrackRef, val at: EndAt)

/** Run in order after the new state is published; `g` names the service instance an effect addresses. */
internal sealed interface MusicEffect {
    /** A [shuffled] fetch starts from a random row, picked by the owner. */
    data class LaunchFetch(val r: Long, val source: MusicSource, val startIndex: Int = 0, val shuffled: Boolean = false) : MusicEffect
    data class CancelFetch(val r: Long) : MusicEffect

    /** Enqueue request [r]'s fetch in session [sid]'s scope. */
    data class LaunchEnqueue(val sid: Long, val r: Long, val mode: EnqueueMode, val source: MusicSource) : MusicEffect

    /** Cancels session [sid]'s scope and every enqueue fetch in it. */
    data class CancelSession(val sid: Long) : MusicEffect

    /** Closes a playing video before music starts; returns once it is gone. */
    data object CloseVideo : MusicEffect
    data class StartService(val ticket: Long) : MusicEffect
    data object StopService : MusicEffect
    data class Release(val g: Long) : MusicEffect
    data class BuildPlayer(val g: Long, val session: MusicSession) : MusicEffect
    data class ReplaceQueue(val g: Long, val session: MusicSession) : MusicEffect

    /** Inserted at [insertIndex] against the player's queue when it runs. */
    data class Insert(val g: Long, val mode: EnqueueMode, val items: List<String>) : MusicEffect

    /** Reports [ref] started, ending [ends] in the same core call. */
    data class StartTrack(val ref: TrackRef, val item: String, val ends: TrackEnd?) : MusicEffect
    data class End(val end: TrackEnd) : MusicEffect
    data class Pause(val g: Long) : MusicEffect
    data class Play(val g: Long) : MusicEffect
    data class RetryPrepare(val g: Long) : MusicEffect
    data class SkipNext(val g: Long) : MusicEffect
    data class Apply(val g: Long, val op: TransportOp) : MusicEffect
}

internal data class MusicStep(val state: MusicFlow, val effects: List<MusicEffect> = emptyList())

/** Every music transition; an event whose tag isn't current changes nothing, and an orphan service is released. */
internal fun reduce(state: MusicFlow, event: MusicEvent): MusicStep {
    val phase = state.phase
    val active = phase as? Phase.Active
    val sid = phase.session?.sid
    val same = MusicStep(state)
    return when (event) {
        is MusicEvent.Play -> request(state, event.r, event.source, event.epoch, event.startIndex, event.shuffled)
        // Nothing playing on the enqueue's account: Play next or Add to queue plays the item as a new queue there.
        is MusicEvent.Enqueue -> if (sid == null || phase.session?.epoch != event.epoch) {
            request(state, event.r, event.source, event.epoch, startIndex = 0, shuffled = false)
        } else {
            MusicStep(state.copy(notice = MusicNotice.Queued(event.r, event.mode, event.title)), listOf(MusicEffect.LaunchEnqueue(sid, event.r, event.mode, event.source)))
        }
        is MusicEvent.CancelFetch -> when (val p = state.pending) {
            is Pending.Fetch -> if (p.r == event.r) dropFetch(state) else same
            is Pending.Refused -> if (p.r == event.r) MusicStep(state.copy(pending = Pending.None)) else same
            Pending.None -> same
        }
        is MusicEvent.Fetched -> fetched(state, event)
        is MusicEvent.FetchEmpty -> refuse(state, event.r, Refusal.Empty)
        is MusicEvent.FetchFailed -> refuse(state, event.r, Refusal.Failed)
        is MusicEvent.EnqueueFetched -> when {
            sid != event.sid -> same
            phase is Phase.Starting -> {
                // The start track is still to play, so the user group starts after it.
                val session = phase.session.copy(items = phase.session.items.inserted(event.mode, phase.session.start, event.items))
                MusicStep(state.copy(phase = phase.copy(session = session)))
            }
            active != null -> MusicStep(state, listOf(MusicEffect.Insert(active.g, event.mode, event.items)))
            else -> same
        }
        // The queue stays as it was; the bar says so once.
        is MusicEvent.EnqueueFailed -> if (sid == event.sid) MusicStep(state.copy(notice = MusicNotice.EnqueueFailed(event.r))) else same
        is MusicEvent.NoticeShown -> if (state.notice == event.notice) MusicStep(state.copy(notice = null)) else same
        is MusicEvent.Confirm -> if (phase == Phase.Idle) same else MusicStep(state.copy(notice = event.notice))
        is MusicEvent.ServiceReady -> when {
            phase is Phase.Starting && event.ticket == phase.ticket -> {
                val s = phase.session
                val started = Phase.Active(s, event.g, n = 0, Net.Online)
                MusicStep(
                    state.copy(phase = started),
                    listOf(MusicEffect.BuildPlayer(event.g, s), MusicEffect.StartTrack(started.ref, s.items[s.start].item, ends = null)),
                )
            }
            active?.g == event.g -> same
            else -> MusicStep(state, listOf(MusicEffect.Release(event.g)))
        }
        // Nothing attached, so there is nothing to stop or release.
        is MusicEvent.ServiceStartFailed ->
            if (phase is Phase.Starting && event.ticket == phase.ticket) {
                MusicStep(state.copy(phase = Phase.Idle, notice = MusicNotice.StartFailed(event.ticket)), listOf(MusicEffect.CancelSession(phase.session.sid)))
            } else {
                same
            }
        // The service is gone, so there is nothing to release.
        is MusicEvent.ServiceDestroyed -> if (active != null && active.g == event.g) ended(state, active, EndAt.Ticks(event.ticks), release = false) else same
        is MusicEvent.TrackChanged -> if (active == null || sid != event.sid) {
            same
        } else {
            val next = active.copy(n = active.n + 1)
            MusicStep(state.copy(phase = next), listOf(MusicEffect.StartTrack(next.ref, event.item, TrackEnd(active.ref, EndAt.Ticks(event.prevTicks)))))
        }
        // Repeat off: the session ends with its queue.
        is MusicEvent.QueueEnded -> if (active != null && sid == event.sid) ended(state, active, EndAt.Ticks(event.ticks), release = true) else same
        is MusicEvent.NetworkLost ->
            if (active != null && active.net == Net.Online && sid == event.sid) MusicStep(state.copy(phase = active.copy(net = Net.Lost)), listOf(MusicEffect.Pause(active.g))) else same
        is MusicEvent.UserPlay -> when {
            active == null -> same
            active.net == Net.Lost -> MusicStep(state.copy(phase = active.copy(net = Net.Online)), listOf(MusicEffect.RetryPrepare(active.g)))
            else -> MusicStep(state, listOf(MusicEffect.Play(active.g)))
        }
        is MusicEvent.Transport -> if (active == null) {
            same
        } else {
            // A skip while the network is lost reconnects and plays the new track.
            val retry = active.net == Net.Lost && (event.op == TransportOp.Next || event.op == TransportOp.Previous)
            val apply = MusicEffect.Apply(active.g, event.op)
            if (retry) MusicStep(state.copy(phase = active.copy(net = Net.Online)), listOf(apply, MusicEffect.RetryPrepare(active.g))) else MusicStep(state, listOf(apply))
        }
        // A failed track is skipped; the last one ends the session as a queue end does.
        is MusicEvent.PlayerFailed -> when {
            active == null || sid != event.sid -> same
            event.isLast -> ended(state, active, EndAt.PlayerPosition, release = true)
            else -> MusicStep(state, listOf(MusicEffect.SkipNext(active.g)))
        }
        MusicEvent.Stop, MusicEvent.VideoStarting -> stop(state)
        // An account change drops a fetch and stops a session that belong to neither the current nor the kept account.
        is MusicEvent.EpochsChanged -> {
            val live = setOfNotNull(event.current, event.parked)
            val kept = (state.pending as? Pending.Fetch)?.takeIf { it.epoch in live }
            val epoch = phase.session?.epoch
            val step = if (epoch != null && epoch !in live) stop(state) else dropFetch(state)
            if (kept == null) step else MusicStep(step.state.copy(pending = kept), step.effects - MusicEffect.CancelFetch(kept.r))
        }
    }
}

private val Phase.session: MusicSession?
    get() = when (this) {
        Phase.Idle -> null
        is Phase.Starting -> session
        is Phase.Active -> session
    }

/** The newest request replaces any waiting one, whose fetch is cancelled. */
private fun request(state: MusicFlow, r: Long, source: MusicSource, epoch: ULong, startIndex: Int, shuffled: Boolean): MusicStep {
    val dropped = dropFetch(state)
    return MusicStep(dropped.state.copy(pending = Pending.Fetch(r, epoch, source, shuffled)), dropped.effects + MusicEffect.LaunchFetch(r, source, startIndex, shuffled))
}

private fun dropFetch(state: MusicFlow): MusicStep {
    val cancel = (state.pending as? Pending.Fetch)?.let { MusicEffect.CancelFetch(it.r) }
    return MusicStep(state.copy(pending = Pending.None), listOfNotNull(cancel))
}

private fun refuse(state: MusicFlow, r: Long, reason: Refusal): MusicStep {
    val p = state.pending as? Pending.Fetch
    return if (p?.r == r) MusicStep(state.copy(pending = Pending.Refused(r, reason, p.source))) else MusicStep(state)
}

/** Only the waiting request's result replaces the queue: a new session, the old one's scope cancelled. */
private fun fetched(state: MusicFlow, event: MusicEvent.Fetched): MusicStep {
    val p = state.pending as? Pending.Fetch
    if (p?.r != event.r) return MusicStep(state)
    val session = MusicSession(state.lastSid + 1, p.epoch, p.source, event.items.map { QueueEntry(it, QueueOrigin.Source) }, event.startIndex, p.shuffled)
    val next = state.copy(pending = Pending.None, lastSid = session.sid)
    return when (val phase = state.phase) {
        // A video in PiP closes before the service starts.
        Phase.Idle -> {
            val ticket = state.lastTicket + 1
            MusicStep(next.copy(phase = Phase.Starting(session, ticket), lastTicket = ticket), listOf(MusicEffect.CloseVideo, MusicEffect.StartService(ticket)))
        }
        is Phase.Starting -> MusicStep(next.copy(phase = phase.copy(session = session)), listOf(MusicEffect.CancelSession(phase.session.sid)))
        is Phase.Active -> {
            val replaced = phase.copy(session = session, n = 0)
            MusicStep(
                next.copy(phase = replaced),
                listOf(
                    MusicEffect.CancelSession(phase.session.sid),
                    MusicEffect.ReplaceQueue(phase.g, session),
                    MusicEffect.StartTrack(replaced.ref, event.items[event.startIndex], TrackEnd(phase.ref, EndAt.PlayerPosition)),
                ),
            )
        }
    }
}

/** Ends the session and its fetches: a start in flight is stopped, a playing track ended before its service is released. */
private fun stop(state: MusicFlow): MusicStep {
    val dropped = dropFetch(state)
    val ending = when (val phase = state.phase) {
        Phase.Idle -> return dropped
        is Phase.Starting -> MusicStep(dropped.state.copy(phase = Phase.Idle), listOf(MusicEffect.CancelSession(phase.session.sid), MusicEffect.StopService))
        is Phase.Active -> ended(dropped.state, phase, EndAt.PlayerPosition, release = true)
    }
    return MusicStep(ending.state, ending.effects + dropped.effects)
}

/** Leaves [active]: its track ended at [at], then its service released unless it is already gone. */
private fun ended(state: MusicFlow, active: Phase.Active, at: EndAt, release: Boolean): MusicStep = MusicStep(
    state.copy(phase = Phase.Idle),
    listOfNotNull(MusicEffect.End(TrackEnd(active.ref, at)), MusicEffect.Release(active.g).takeIf { release }, MusicEffect.CancelSession(active.session.sid)),
)
