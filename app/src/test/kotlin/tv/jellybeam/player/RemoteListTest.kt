package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.player.RemoteList.Playing
import tv.jellybeam.player.RemoteListEffect.Refused
import tv.jellybeam.player.RemoteListEffect.Report
import tv.jellybeam.player.RemoteListEvent.Adopted
import tv.jellybeam.player.RemoteListEvent.Clear
import tv.jellybeam.player.RemoteListEvent.Enqueue
import tv.jellybeam.player.RemoteListEvent.Open
import tv.jellybeam.ui.cards.testCard

/** docs/31 §4.1.1: the video remote list's transitions and the successor a step plays. */
class RemoteListTest {
    private val changed = listOf(Report)
    private val none = emptyList<RemoteListEffect>()
    private val refused = listOf(Refused)

    private fun reduce(list: RemoteList, e: RemoteListEvent, grouped: Boolean = false) = reduceRemoteList(list, e, grouped)

    @Test
    fun `a Play with several ids opens the list and one id opens none`() {
        assertEquals(Playing(listOf(A, B), 1) to changed, reduce(RemoteList.None, Open(listOf(A, B), 1)))
        assertEquals(RemoteList.None to none, reduce(RemoteList.None, Open(listOf(A), 0)))
        assertEquals(RemoteList.None to changed, reduce(Playing(listOf(A, B), 0), Open(listOf(C), 0)))
    }

    /** Table §8 (#4): a group's queue decides what follows, so a list Open or Enqueue is refused and never reported. */
    @Test
    fun `under a group an open or an enqueue is refused without a report`() {
        assertEquals(RemoteList.None to refused, reduce(RemoteList.None, Open(listOf(A, B), 0), grouped = true))
        EnqueueMode.entries.forEach { mode ->
            assertEquals("$mode", RemoteList.None to refused, reduce(RemoteList.None, Enqueue(listOf(B), mode, A), grouped = true))
        }
    }

    /** Table §8 (#8): a same-item Play without a list, or entering a group, ends the list and reports it. */
    @Test
    fun `a clear ends a playing list with one report`() {
        listOf(false, true).forEach { grouped ->
            assertEquals("grouped=$grouped", RemoteList.None to changed, reduce(Playing(listOf(A, B), 0), Clear, grouped))
            assertEquals("grouped=$grouped", RemoteList.None to none, reduce(RemoteList.None, Clear, grouped))
        }
    }

    /** Table §8: an enqueue with nothing live is refused, list or not. */
    @Test
    fun `an enqueue with nothing live is refused`() {
        EnqueueMode.entries.forEach { mode ->
            assertEquals("$mode", RemoteList.None to refused, reduce(RemoteList.None, Enqueue(listOf(B), mode, null)))
            assertEquals("$mode", Playing(listOf(A, B), 0) to refused, reduce(Playing(listOf(A, B), 0), Enqueue(listOf(C), mode, null)))
        }
    }

    /** Table §8: [A, B, A] at 2 steps back by index, to 1 then 0, never by matching the id. */
    @Test
    fun `previous walks a list naming the item twice by index`() {
        var list: RemoteList = Playing(listOf(A, B, A), 2)
        listOf(1, 0).forEach { at ->
            val to = successor(list, NeighborLookup.Unknown, ListStep.Previous)
            assertEquals(Successor.ListItem(listOf(A, B, A)[at], at), to)
            list = reduce(list, adoptedFor(to)).first
            assertEquals(Playing(listOf(A, B, A), at), list)
        }
    }

    /** Table §8: off the front of the list the previous episode plays and the list ends. */
    @Test
    fun `previous at the first item plays the previous episode and ends the list`() {
        val list = Playing(listOf(A, B), 0)
        val to = successor(list, KNOWN, ListStep.Previous)
        assertEquals(Successor.Episode(PREV), to)
        assertEquals(RemoteList.None to changed, reduce(list, adoptedFor(to)))
    }

    @Test
    fun `next at the last item plays the next episode and ends the list, and with none there is nothing`() {
        val list = Playing(listOf(A, B), 1)
        val to = successor(list, KNOWN, ListStep.Next)
        assertEquals(Successor.Episode(NEXT), to)
        assertEquals(RemoteList.None to changed, reduce(list, adoptedFor(to)))
        assertEquals(Successor.None, successor(list, NeighborLookup.Known(null, null), ListStep.Next))
        assertEquals(Successor.None, successor(list, NeighborLookup.Unknown, ListStep.Next))
    }

    @Test
    fun `PlayNext twice plays the newest next`() {
        val once = reduce(Playing(listOf(A, B), 0), Enqueue(listOf(C), EnqueueMode.Next, A))
        assertEquals(Playing(listOf(A, C, B), 0) to changed, once)
        val twice = reduce(once.first, Enqueue(listOf(D), EnqueueMode.Next, A))
        assertEquals(Playing(listOf(A, D, C, B), 0) to changed, twice)
    }

    @Test
    fun `PlayLast appends after the whole list`() {
        assertEquals(Playing(listOf(A, B, C, D), 0) to changed, reduce(Playing(listOf(A, B), 0), Enqueue(listOf(C, D), EnqueueMode.Last, A)))
    }

    /** docs/31 §4.1 (approved Q4): an enqueue over a video with no list starts one from that video. */
    @Test
    fun `an enqueue over a video without a list starts with that video`() {
        EnqueueMode.entries.forEach { mode ->
            assertEquals("$mode", Playing(listOf(A, B), 0) to changed, reduce(RemoteList.None, Enqueue(listOf(B), mode, A)))
        }
        assertEquals(Playing(listOf(D, C), 0) to changed, reduce(Playing(listOf(A, B), 0), Enqueue(listOf(C), EnqueueMode.Last, D)))
    }

    /** docs/31 §4.1.1 (approved Q2): the list's next wins over the next episode; the end of the list hands over to it. */
    @Test
    fun `the list's next wins over the next episode until the list's last item`() {
        val list = Playing(listOf(A, B), 0)
        val to = successor(list, KNOWN, ListStep.Next)
        assertEquals(Successor.ListItem(B, 1), to)
        val last = reduce(list, adoptedFor(to))
        assertEquals(Playing(listOf(A, B), 1) to changed, last)
        assertEquals(Successor.Episode(NEXT), successor(last.first, KNOWN, ListStep.Next))
    }

    /** The item on screen adopted again, by a same-item remote list or a fallback reload, keeps the list where it is. */
    @Test
    fun `the item on screen adopted again keeps the position`() {
        val installed = reduce(RemoteList.None, Open(listOf(A, B), 0)).first
        assertEquals(installed to none, reduce(installed, Adopted(A, null)))
        val list = Playing(listOf(A, B, C), 1)
        assertEquals(list to none, reduce(list, Adopted(B, null)))
        assertEquals(list to none, reduce(list, Open(listOf(A, B, C), 1)))
    }

    /**
     * docs/31 §4.1.1 (table v2): Rust names a remote Play's ids by core/ffi object.rs `canonicalize_item_id` (the uuid
     * crate's lowercase dashed form); an externally started item is named by [ExternalPlaybackContract.canonicalItemId],
     * so an enqueue over it compares one spelling and keeps its list.
     */
    @Test
    fun `a remote list and an externally started item spell a uuid the same way`() {
        val rust = "00112233-4455-6677-8899-aabbccddeeff"
        val compact = rust.replace("-", "")
        listOf(compact, compact.uppercase(), "00112233-4455-6677-8899-AABBCCDDEEFF", rust).forEach {
            assertEquals(it, rust, ExternalPlaybackContract.canonicalItemId(it))
        }
        val live = ExternalPlaybackContract.canonicalItemId(compact.uppercase())
        assertEquals(Playing(listOf(rust, C, B), 0) to changed, reduce(Playing(listOf(rust, B), 0), Enqueue(listOf(C), EnqueueMode.Next, live)))
    }

    /** Table §8: every sequence of four inputs, solo and grouped, keeps I1-I5 and the successor rule after every step. */
    @Test
    fun `every input sequence up to length four keeps the invariants`() {
        var steps = 0
        fun walk(list: RemoteList, depth: Int) {
            if (depth == 4) return
            for (input in alphabet) {
                val (next, effects) = apply(list, input)
                checkInvariants(list, input, next, effects)
                steps++
                walk(next, depth + 1)
            }
        }
        walk(RemoteList.None, 0)
        val n = alphabet.size
        assertEquals(n + n * n + n * n * n + n * n * n * n, steps)
    }

    /** One input: an event under a mode, or a solo step, which adopts its successor as the view model's transition does. */
    private sealed interface Input {
        data class Event(val e: RemoteListEvent, val grouped: Boolean) : Input

        data class Step(val step: ListStep) : Input
    }

    private fun apply(list: RemoteList, input: Input): Pair<RemoteList, List<RemoteListEffect>> = when (input) {
        is Input.Event -> reduce(list, input.e, input.grouped)
        is Input.Step -> when (val to = successor(list, KNOWN, input.step)) {
            Successor.None -> list to none
            else -> reduce(list, adoptedFor(to))
        }
    }

    private fun checkInvariants(before: RemoteList, input: Input, after: RemoteList, effects: List<RemoteListEffect>) {
        val label = "$input from $before"
        // I1: a list holds at least two ids and plays one of them.
        if (after is Playing) ensure(after.ids.size >= 2 && after.at in after.ids.indices) { "I1: $after; $label" }
        val e = (input as? Input.Event)?.e
        val grouped = (input as? Input.Event)?.grouped == true
        // I2: an accepted enqueue over the live item keeps its index and everything up to it; over another, the live item leads.
        if (e is Enqueue && !grouped && e.live != null) {
            if (before is Playing && before.ids[before.at] == e.live) {
                ensure(after is Playing && after.at == before.at && after.ids.take(before.at + 1) == before.ids.take(before.at + 1)) { "I2: $after; $label" }
            } else if (after is Playing) {
                ensure(after.at == 0 && after.ids.first() == e.live) { "I2 live: $after; $label" }
            }
        }
        // I3: under a group an Open or Enqueue changes nothing and is refused; a group with no list never gains one.
        if (grouped) {
            if (e is Open || e is Enqueue) ensure(after == before && effects == refused) { "I3 refused: $after $effects; $label" }
            if (before == RemoteList.None) ensure(after == RemoteList.None) { "I3: $after; $label" }
        }
        // I4: each change reports once and nothing else does; only what the table refuses is refused.
        val change = after != before
        ensure(effects.count { it == Report } == (if (change) 1 else 0)) { "I4: $effects; $label" }
        val refuses = (e is Open && grouped) || (e is Enqueue && (grouped || e.live == null))
        ensure(effects.count { it == Refused } == (if (refuses) 1 else 0)) { "refused: $effects; $label" }
        // I5: a list step plays exactly the neighbour by index and lands there; an episode step ends the list.
        if (input is Input.Step) {
            when (val to = successor(before, KNOWN, input.step)) {
                is Successor.ListItem -> ensure(after is Playing && after.at == to.at && after.ids[to.at] == to.id && after.ids == (before as Playing).ids) { "I5: $after; $label" }
                is Successor.Episode -> ensure(after == RemoteList.None) { "I5 episode: $after; $label" }
                Successor.None -> ensure(after == before) { "I5 none: $after; $label" }
            }
        }
        // hasNext/hasPrevious (I8): a step has a successor exactly when the list has that neighbour or the lookup knows one.
        LOOKUPS.forEach { lookup ->
            ListStep.entries.forEach { step ->
                val at = (after as? Playing)?.let { if (step == ListStep.Next) it.at + 1 else it.at - 1 }
                val listed = (after as? Playing)?.ids?.getOrNull(at ?: -1)
                val known = (lookup as? NeighborLookup.Known)?.let { if (step == ListStep.Next) it.next else it.previous }
                val expected = when {
                    listed != null -> Successor.ListItem(listed, at!!)
                    known != null -> Successor.Episode(known)
                    else -> Successor.None
                }
                ensure(successor(after, lookup, step) == expected) { "successor $step $lookup: $after; $label" }
            }
        }
    }

    /** As the view model's transition: a list item names its index, an episode none. */
    private fun adoptedFor(to: Successor): RemoteListEvent = when (to) {
        is Successor.ListItem -> Adopted(to.id, to.at)
        is Successor.Episode -> Adopted(to.card.id, null)
        Successor.None -> error("no successor")
    }

    /** Throws only when [ok] is false, so the message is built only for a failure. */
    private inline fun ensure(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError(message())
    }

    private companion object {
        const val A = "item-a"
        const val B = "item-b"
        const val C = "item-c"
        const val D = "item-d"
        val PREV = testCard(id = "item-p", itemType = "Episode")
        val NEXT = testCard(id = "item-n", itemType = "Episode")
        val KNOWN = NeighborLookup.Known(PREV, NEXT)
        val LOOKUPS = listOf(NeighborLookup.Unknown, KNOWN, NeighborLookup.Known(null, null))

        /** Opens of two, a duplicate, one and an out-of-range start; a clear; enqueues over A, B and nothing; adoptions; each solo and grouped; plus solo steps. */
        val alphabet: List<Input> = buildList {
            val events = buildList {
                add(Open(listOf(A, B), 0))
                add(Open(listOf(A, B, A), 1))
                add(Open(listOf(B), 0))
                add(Open(listOf(A, C), 2))
                add(Clear)
                EnqueueMode.entries.forEach { mode -> listOf(A, B, null).forEach { live -> add(Enqueue(listOf(C), mode, live)) } }
                add(Adopted(A, null))
                add(Adopted(B, 1))
                add(Adopted(A, 2))
                add(Adopted(C, null))
            }
            listOf(false, true).forEach { grouped -> events.forEach { add(Input.Event(it, grouped)) } }
            ListStep.entries.forEach { add(Input.Step(it)) }
        }
    }
}
