package tv.jellybeam.ui.music

import uniffi.jellybeam_core.PlaylistEntry

/**
 * docs/29 §9 E4: a playlist page's reorder mode, screen-local. [Off] is the normal page; the other modes show the
 * REORDERING banner in place of the action row.
 */
internal sealed interface ReorderMode {
    data object Off : ReorderMode

    /** Reordering, nothing picked up. */
    data object Browsing : ReorderMode

    /** Entry [entry] picked up at [from], shown at [at]. */
    data class Holding(val entry: String, val from: Int, val at: Int) : ReorderMode

    /** The drop of [entry] from [from] to [to] is at the server; only the result tagged [tag] settles it. */
    data class Dropping(val entry: String, val from: Int, val to: Int, val tag: Long) : ReorderMode
}

/** [entries] is the order the server holds as far as this page knows; [failed] names the song whose move failed, until shown. */
internal data class PlaylistReorder(
    val entries: List<PlaylistEntry> = emptyList(),
    val mode: ReorderMode = ReorderMode.Off,
    val failed: String? = null,
    val lastTag: Long = 0,
)

internal sealed interface ReorderEvent {
    data object Enter : ReorderEvent
    data class PickUp(val index: Int) : ReorderEvent
    data class Step(val delta: Int) : ReorderEvent
    data object Drop : ReorderEvent
    data object Back : ReorderEvent
    data class Moved(val tag: Long) : ReorderEvent
    data class MoveFailed(val tag: Long) : ReorderEvent

    /** A fresh first page: a load, a retry or a re-read after a remove. */
    data class Loaded(val entries: List<PlaylistEntry>) : ReorderEvent

    /** The next page, read at the current end. */
    data class Appended(val more: List<PlaylistEntry>) : ReorderEvent
    data object NoticeShown : ReorderEvent
}

/** The one server write a drop needs; its result comes back as [ReorderEvent.Moved] or [ReorderEvent.MoveFailed] with [tag]. */
internal data class ReorderMove(val entryId: String, val newIndex: Int, val tag: Long)

internal data class ReorderStep(val state: PlaylistReorder, val move: ReorderMove? = null)

/**
 * docs/29 §9 E4: every reorder transition. A drop's result carries its tag, so a stale or out-of-order one
 * changes nothing; a failure leaves the order from before the pick-up and names the song for the notice.
 */
internal fun reduceReorder(s: PlaylistReorder, e: ReorderEvent): ReorderStep {
    val mode = s.mode
    val next = when (e) {
        ReorderEvent.Enter -> if (mode == ReorderMode.Off && s.entries.size > 1) s.copy(mode = ReorderMode.Browsing) else s
        is ReorderEvent.PickUp -> {
            val entry = s.entries.getOrNull(e.index)
            if (mode == ReorderMode.Browsing && entry != null) s.copy(mode = ReorderMode.Holding(entry.entryId, e.index, e.index)) else s
        }
        is ReorderEvent.Step -> if (mode is ReorderMode.Holding) s.copy(mode = mode.copy(at = (mode.at + e.delta).coerceIn(0, s.entries.lastIndex))) else s
        ReorderEvent.Drop -> when {
            mode !is ReorderMode.Holding -> s
            mode.at == mode.from -> s.copy(mode = ReorderMode.Browsing)
            else -> {
                val tag = s.lastTag + 1
                val dropping = s.copy(mode = ReorderMode.Dropping(mode.entry, mode.from, mode.at, tag), lastTag = tag)
                return ReorderStep(dropping, ReorderMove(mode.entry, mode.at, tag))
            }
        }
        ReorderEvent.Back -> when (mode) {
            ReorderMode.Off, is ReorderMode.Dropping -> s
            ReorderMode.Browsing -> s.copy(mode = ReorderMode.Off)
            is ReorderMode.Holding -> s.copy(mode = ReorderMode.Browsing)
        }
        is ReorderEvent.Moved -> if (mode is ReorderMode.Dropping && mode.tag == e.tag) {
            s.copy(entries = moved(s.entries, mode.from, mode.to), mode = ReorderMode.Browsing)
        } else {
            s
        }
        is ReorderEvent.MoveFailed -> if (mode is ReorderMode.Dropping && mode.tag == e.tag) {
            s.copy(mode = ReorderMode.Browsing, failed = s.entries[mode.from].card.name)
        } else {
            s
        }
        is ReorderEvent.Loaded -> s.copy(
            entries = e.entries,
            mode = when {
                e.entries.size < 2 -> ReorderMode.Off
                mode is ReorderMode.Holding || mode is ReorderMode.Dropping -> ReorderMode.Browsing
                else -> mode
            },
        )
        is ReorderEvent.Appended -> s.copy(entries = s.entries + e.more)
        ReorderEvent.NoticeShown -> s.copy(failed = null)
    }
    return ReorderStep(next)
}

/** The rows as the page shows them: a held or dropping entry at its new place, everything else in [PlaylistReorder.entries] order. */
internal fun PlaylistReorder.shown(): List<PlaylistEntry> = when (val m = mode) {
    is ReorderMode.Holding -> moved(entries, m.from, m.at)
    is ReorderMode.Dropping -> moved(entries, m.from, m.to)
    ReorderMode.Off, ReorderMode.Browsing -> entries
}

/** The entry the page draws picked up (accent, indent, ↕); `null` when none is. */
internal fun PlaylistReorder.heldEntry(): String? = when (val m = mode) {
    is ReorderMode.Holding -> m.entry
    is ReorderMode.Dropping -> m.entry
    ReorderMode.Off, ReorderMode.Browsing -> null
}

private fun moved(entries: List<PlaylistEntry>, from: Int, to: Int): List<PlaylistEntry> =
    if (from == to) entries else entries.toMutableList().apply { add(to, removeAt(from)) }
