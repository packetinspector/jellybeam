package tv.jellybeam.player.music

import kotlin.random.Random

/** docs/29 §9 H: where a queue entry came from, kept in its MediaItem extras as [tag] so the Queue screen's groups can be rebuilt. */
internal enum class QueueOrigin(val tag: String) {
    UserNext("user-next"),
    UserAdded("user-added"),
    Source("source"),
    ;

    val isUser: Boolean get() = this != Source
}

/** Play next or Add to queue, and the origin each tags its entries with. */
internal enum class EnqueueMode(val origin: QueueOrigin) {
    PlayNext(QueueOrigin.UserNext),
    AddToQueue(QueueOrigin.UserAdded),
}

internal data class QueueEntry(val item: String, val origin: QueueOrigin)

/**
 * docs/29 §9 H: the executor retags an entry [QueueOrigin.Source] once it becomes current, so a played
 * user entry never rejoins the user group; `null` when it already is.
 */
internal fun originOnceCurrent(origin: QueueOrigin): QueueOrigin? = QueueOrigin.Source.takeIf { origin.isUser }

/** docs/29 §9 H: the user group is the run of user entries right after [current] in the timeline. */
internal fun userCount(current: Int, origins: List<QueueOrigin>): Int {
    var end = current + 1
    while (end < origins.size && origins[end].isUser) end++
    return end - current - 1
}

private fun userGroup(current: Int, origins: List<QueueOrigin>): List<Int> = (current + 1..current + userCount(current, origins)).toList()

/** The row a fetched queue of [size] starts from: [requested] in range, or any row for a shuffled start. */
internal fun fetchStartRow(requested: Int, size: Int, shuffled: Boolean, random: Random): Int =
    if (shuffled) random.nextInt(size) else requested.coerceIn(0, size - 1)

/** docs/29 §9 H: Play next lands at the top of the user group, Add to queue at its end. */
internal fun insertIndex(mode: EnqueueMode, current: Int, origins: List<QueueOrigin>): Int = when (mode) {
    EnqueueMode.PlayNext -> current + 1
    EnqueueMode.AddToQueue -> current + 1 + userCount(current, origins)
}

/** [items] tagged for [mode] and inserted at [insertIndex]. */
internal fun List<QueueEntry>.inserted(mode: EnqueueMode, current: Int, items: List<String>): List<QueueEntry> {
    val at = insertIndex(mode, current, map { it.origin })
    return take(at) + items.map { QueueEntry(it, mode.origin) } + drop(at)
}

/** The Queue screen's three groups as timeline indices, each in the order it will play. */
internal data class QueueGroups(val nowPlaying: Int, val nextInQueue: List<Int>, val nextFromSource: List<Int>)

/** docs/29 §9 H: the user group, then whatever else follows [current] in [playOrder], which [shuffledOrder] keeps behind that group. */
internal fun queueGroups(playOrder: List<Int>, current: Int, origins: List<QueueOrigin>): QueueGroups {
    val user = userGroup(current, origins)
    return QueueGroups(current, user, playOrder.drop(playOrder.indexOf(current) + 1).filter { it !in user })
}

/** docs/29 §9 H: Clear queue removes every entry after [current] in [playOrder]; highest index first, so each removal leaves the rest in place. */
internal fun clearAfterCurrent(playOrder: List<Int>, current: Int): List<Int> =
    playOrder.drop(playOrder.indexOf(current) + 1).sortedDescending()

/** A timeline move, `moveMediaItem(from, to)`, and the origin the moved entry takes in the group it lands in. */
internal data class QueueMove(val from: Int, val to: Int, val origin: QueueOrigin)

/**
 * docs/29 §9 H: a move within or across the upcoming groups; `null` for a no-op or a move that touches
 * [current] or what played before it, so the playing entry never changes. Landing inside the user group
 * joins it, landing further down joins the source, and the boundary between them keeps the entry's group.
 */
internal fun queueMove(origins: List<QueueOrigin>, current: Int, from: Int, to: Int): QueueMove? {
    if (from <= current || to <= current || from == to || from >= origins.size || to >= origins.size) return null
    val moved = origins[from]
    val users = userCount(current, origins.filterIndexed { i, _ -> i != from })
    val origin = when {
        to <= current + users -> if (moved.isUser) moved else QueueOrigin.UserAdded
        to == current + users + 1 -> moved
        else -> QueueOrigin.Source
    }
    return QueueMove(from, to, origin)
}

/**
 * docs/29 §9 H: [base], a shuffled permutation of the timeline, with the user group lifted to right
 * after [current] in queue order; everything else keeps its base order, so shuffling never moves [current].
 */
internal fun shuffledOrder(base: List<Int>, current: Int, origins: List<QueueOrigin>): List<Int> {
    val group = userGroup(current, origins)
    val rest = base.filter { it !in group }
    val at = rest.indexOf(current) + 1
    return rest.take(at) + group + rest.drop(at)
}

/** A Queue-screen move as the player applies it: `moveMediaItem(from, to)`, the entry's new origin, and the play order after it in shuffle. */
internal data class QueueReorder(val from: Int, val to: Int, val origin: QueueOrigin, val order: List<Int>?)

/**
 * docs/29 §9 H: the row at play position [fromPos] dropped at [toPos], both after [current]'s. The group rule is
 * [queueMove]'s, read in play order; in shuffle the timeline keeps the user group right after [current], so
 * [insertIndex] stays right, and the new play order is set explicitly, since a moved entry's shuffle slot is random.
 */
internal fun queueReorder(origins: List<QueueOrigin>, playOrder: List<Int>, current: Int, shuffled: Boolean, fromPos: Int, toPos: Int): QueueReorder? {
    val pc = playOrder.indexOf(current)
    if (pc < 0 || fromPos !in playOrder.indices || toPos !in playOrder.indices) return null
    val move = queueMove(playOrder.map { origins[it] }, pc, fromPos, toPos) ?: return null
    if (!shuffled) return QueueReorder(move.from, move.to, move.origin, order = null)
    val from = playOrder[fromPos]
    val currentLeft = if (from < current) current - 1 else current
    val usersLeft = userCount(currentLeft, origins.filterIndexed { i, _ -> i != from })
    val inUserGroup = from in current + 1..current + userCount(current, origins)
    val to = when {
        move.origin.isUser -> currentLeft + 1 + (toPos - pc - 1)
        inUserGroup -> currentLeft + 1 + usersLeft
        else -> from
    }
    val played = playOrder.toMutableList().apply { add(toPos, removeAt(fromPos)) }
    return QueueReorder(from, to, move.origin, played.map { movedIndex(it, from, to) })
}

/**
 * docs/29 §9 H: Play next on the queued timeline entry [index] moves it right after [current] in the timeline and, in
 * shuffle, in play order, tagged user-next, so it heads the user group; `null` for [current] or what played before it.
 */
internal fun queuePlayNext(playOrder: List<Int>, current: Int, shuffled: Boolean, index: Int): QueueReorder? {
    val pc = playOrder.indexOf(current)
    val at = playOrder.indexOf(index)
    if (pc < 0 || at <= pc) return null
    val to = if (index < current) current else current + 1
    val order = if (shuffled) playOrder.toMutableList().apply { add(pc + 1, removeAt(at)) }.map { movedIndex(it, index, to) } else null
    return QueueReorder(index, to, QueueOrigin.UserNext, order)
}

/** Where timeline entry [i] sits after `moveMediaItem(from, to)`. */
internal fun movedIndex(i: Int, from: Int, to: Int): Int = when {
    i == from -> to
    from < to && i in from + 1..to -> i - 1
    from > to && i in to until from -> i + 1
    else -> i
}
