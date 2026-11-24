package tv.jellybeam.player

import uniffi.jellybeam_core.Card

/** docs/31 §4.1.1: the live video session's remote list; [Playing] holds at least two ids and plays [Playing.at]. */
internal sealed interface RemoteList {
    data object None : RemoteList

    data class Playing(val ids: List<String>, val at: Int) : RemoteList
}

/** Every input to [reduceRemoteList]. */
internal sealed interface RemoteListEvent {
    /** A remote Play's list, starting at [at]. */
    data class Open(val ids: List<String>, val at: Int) : RemoteListEvent

    /** A Play without a list, a group load, or entering a group. */
    data object Clear : RemoteListEvent

    /** PlayNext / PlayLast of [ids] over [live], the item on screen; `null` when nothing plays. */
    data class Enqueue(val ids: List<String>, val mode: EnqueueMode, val live: String?) : RemoteListEvent

    /** The session started [item]; [target] is the list index its transition named, `null` for none. */
    data class Adopted(val item: String, val target: Int?) : RemoteListEvent
}

internal enum class EnqueueMode { Next, Last }

/** Which neighbour a transition walks to. */
internal enum class ListStep { Next, Previous }

internal sealed interface RemoteListEffect {
    /** Report the session now, its list as it stands. */
    data object Report : RemoteListEffect

    /** The command can't change this list; the remote path drops it as not applicable. */
    data object Refused : RemoteListEffect
}

/** docs/31 §4.1.1: what a step plays: the list's neighbour by id, else the episode neighbour, else nothing. */
internal sealed interface Successor {
    data class ListItem(val id: String, val at: Int) : Successor

    data class Episode(val card: Card) : Successor

    data object None : Successor
}

/** docs/31 §4.1.1: the list wins over the episode neighbour; a step off either end of the list falls back to it. */
internal fun successor(list: RemoteList, neighbours: NeighborLookup, step: ListStep): Successor {
    if (list is RemoteList.Playing) {
        val at = if (step == ListStep.Next) list.at + 1 else list.at - 1
        list.ids.getOrNull(at)?.let { return Successor.ListItem(it, at) }
    }
    val known = neighbours as? NeighborLookup.Known
    val card = if (step == ListStep.Next) known?.next else known?.previous
    return card?.let(Successor::Episode) ?: Successor.None
}

/**
 * docs/31 §4.1.1: every list transition; a change reports once. In a group ([grouped]) the group's
 * queue decides what follows, so an Open or Enqueue is refused, as is an Enqueue with nothing live.
 */
internal fun reduceRemoteList(list: RemoteList, e: RemoteListEvent, grouped: Boolean): Pair<RemoteList, List<RemoteListEffect>> {
    val playing = list as? RemoteList.Playing
    val next = when (e) {
        is RemoteListEvent.Open -> if (grouped) return list to listOf(RemoteListEffect.Refused) else opened(e.ids, e.at)
        RemoteListEvent.Clear -> RemoteList.None
        // docs/31 §4.1 (approved Q4): over another item, or no list, the live item starts a new one.
        is RemoteListEvent.Enqueue -> when {
            grouped || e.live == null -> return list to listOf(RemoteListEffect.Refused)
            playing == null || e.live != playing.ids[playing.at] -> opened(listOf(e.live) + e.ids, 0)
            e.mode == EnqueueMode.Next -> playing.copy(ids = playing.ids.take(playing.at + 1) + e.ids + playing.ids.drop(playing.at + 1))
            else -> playing.copy(ids = playing.ids + e.ids)
        }
        is RemoteListEvent.Adopted -> when {
            playing == null -> list
            e.target != null && playing.ids.getOrNull(e.target) == e.item -> playing.copy(at = e.target)
            e.target == null && e.item == playing.ids[playing.at] -> playing
            else -> RemoteList.None
        }
    }
    return if (next == list) list to emptyList() else next to listOf(RemoteListEffect.Report)
}

/** docs/31 §4.1.1: a new request opens its remote Play's list; any other ends the one playing. */
internal fun listOpening(queue: RemoteQueue?): RemoteListEvent = queue?.let { RemoteListEvent.Open(it.ids, it.startIndex) } ?: RemoteListEvent.Clear

/** A list needs a second item and a start inside it; anything less plays as a single item. */
private fun opened(ids: List<String>, at: Int): RemoteList = if (ids.size >= 2 && at in ids.indices) RemoteList.Playing(ids, at) else RemoteList.None
