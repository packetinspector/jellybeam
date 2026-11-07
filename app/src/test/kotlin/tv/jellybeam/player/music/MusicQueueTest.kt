package tv.jellybeam.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.player.music.QueueOrigin.Source
import tv.jellybeam.player.music.QueueOrigin.UserAdded
import tv.jellybeam.player.music.QueueOrigin.UserNext

/** docs/29 §9 H: where Play next and Add to queue land, the Queue screen's groups, clear, move and shuffle. */
class MusicQueueTest {
    @Test
    fun `Play next lands right after the current entry`() {
        assertEquals(3, insertIndex(EnqueueMode.PlayNext, 2, origins("sssss")))
        assertEquals(3, insertIndex(EnqueueMode.PlayNext, 2, origins("sssnas")))
        assertEquals(1, insertIndex(EnqueueMode.PlayNext, 0, origins("s")))
    }

    @Test
    fun `Add to queue lands after the user group`() {
        assertEquals("current+1+userCount", 2 + 1 + 2, insertIndex(EnqueueMode.AddToQueue, 2, origins("sssnass")))
        assertEquals("empty group", 3, insertIndex(EnqueueMode.AddToQueue, 2, origins("sssss")))
        assertEquals("user entries before current are not the group", 3, insertIndex(EnqueueMode.AddToQueue, 2, origins("nassss")))
        assertEquals("a group running to the end", 4, insertIndex(EnqueueMode.AddToQueue, 0, origins("snaa")))
    }

    @Test
    fun `Play next puts the newest first and Add to queue keeps arrival order`() {
        val start = listOf(QueueEntry("s0", Source), QueueEntry("s1", Source), QueueEntry("s2", Source))
        val queue = start
            .inserted(EnqueueMode.AddToQueue, 0, listOf("a1"))
            .inserted(EnqueueMode.AddToQueue, 0, listOf("a2", "a3"))
            .inserted(EnqueueMode.PlayNext, 0, listOf("n1"))
            .inserted(EnqueueMode.PlayNext, 0, listOf("n2"))
        assertEquals(listOf("s0", "n2", "n1", "a1", "a2", "a3", "s1", "s2"), queue.map { it.item })
        assertEquals(listOf(Source, UserNext, UserNext, UserAdded, UserAdded, UserAdded, Source, Source), queue.map { it.origin })
    }

    @Test
    fun `an entry that becomes current leaves the user group`() {
        assertEquals(Source, originOnceCurrent(UserNext))
        assertEquals(Source, originOnceCurrent(UserAdded))
        assertNull(originOnceCurrent(Source))
        val played = origins("snna").toMutableList().also { it[1] = originOnceCurrent(it[1]) ?: it[1] }
        assertEquals("a played entry is not in the group of the entry before it", 0, userCount(0, played))
    }

    @Test
    fun `the groups are now playing, the user group and the rest from the source`() {
        val o = origins("snassn")
        assertEquals(QueueGroups(0, listOf(1, 2), listOf(3, 4, 5)), queueGroups(o.indices.toList(), 0, o))
        assertEquals("current inside the group", QueueGroups(1, listOf(2), listOf(3, 4, 5)), queueGroups(o.indices.toList(), 1, o))
        assertEquals("nothing follows", QueueGroups(5, emptyList(), emptyList()), queueGroups(o.indices.toList(), 5, o))
        val shuffled = listOf(4, 0, 1, 2, 5, 3)
        assertEquals("shuffled play order", QueueGroups(0, listOf(1, 2), listOf(5, 3)), queueGroups(shuffled, 0, o))
    }

    @Test
    fun `clear keeps the current entry and drops everything after it`() {
        val items = listOf("s0", "s1", "s2", "s3", "s4")
        val orders = listOf(items.indices.toList(), listOf(3, 1, 4, 0, 2))
        for (order in orders) {
            for (current in items.indices) {
                val removed = clearAfterCurrent(order, current)
                assertEquals("highest first", removed.sortedDescending(), removed)
                val left = items.toMutableList().apply { removed.forEach { removeAt(it) } }
                val kept = order.take(order.indexOf(current) + 1).sorted().map { items[it] }
                assertEquals("$order current $current", kept, left)
            }
        }
    }

    @Test
    fun `shuffle keeps the user group in order right after the current entry`() {
        val o = origins("ssnnasss")
        permutations(o.indices.toList()).forEach { base ->
            for (current in listOf(1, 0, 6)) {
                val order = shuffledOrder(base, current, o)
                val group = (current + 1..current + userCount(current, o)).toList()
                assertEquals("a permutation", o.indices.toList(), order.sorted())
                val at = order.indexOf(current)
                assertEquals("$base current $current", group, order.subList(at + 1, at + 1 + group.size))
                assertEquals("the rest keeps its base order", base.filter { it !in group }, order.filter { it !in group })
                assertEquals("the Queue screen shows that group", group, queueGroups(order, current, o).nextInQueue)
            }
        }
    }

    @Test
    fun `no move changes the current entry`() {
        val items = listOf("s0", "s1", "n2", "a3", "s4", "s5")
        val o = origins("ssnass")
        for (current in items.indices) {
            for (from in items.indices) {
                for (to in items.indices) {
                    val move = queueMove(o, current, from, to) ?: continue
                    val moved = items.toMutableList().apply { add(move.to, removeAt(move.from)) }
                    assertEquals("move $from to $to over current $current", items[current], moved[current])
                }
            }
        }
        assertNull("moving the current entry is refused", queueMove(o, 1, 1, 4))
        assertNull("moving above the current entry is refused", queueMove(o, 1, 4, 0))
    }

    @Test
    fun `a move into the user group joins it, further down joins the source, and the boundary keeps its group`() {
        val o = origins("snass")
        assertEquals("source into the group", QueueMove(3, 1, UserAdded), queueMove(o, 0, 3, 1))
        assertEquals("source into the group's middle", QueueMove(4, 2, UserAdded), queueMove(o, 0, 4, 2))
        assertEquals("source to the boundary", QueueMove(4, 3, Source), queueMove(o, 0, 4, 3))
        assertEquals("user to the boundary", QueueMove(1, 2, UserNext), queueMove(o, 0, 1, 2))
        assertEquals("user below the boundary", QueueMove(1, 4, Source), queueMove(o, 0, 1, 4))
        assertEquals("within the group", QueueMove(2, 1, UserAdded), queueMove(o, 0, 2, 1))
    }

    @Test
    fun `a shuffled fetch starts from any row, a plain one from its row`() {
        val random = kotlin.random.Random(7)
        val rows = (1..200).map { fetchStartRow(requested = 0, size = 5, shuffled = true, random = random) }.toSet()
        assertEquals("every row can start a shuffled queue", (0..4).toSet(), rows)
        assertEquals(3, fetchStartRow(requested = 3, size = 5, shuffled = false, random = random))
        assertEquals("a row past the end is the last", 4, fetchStartRow(requested = 9, size = 5, shuffled = false, random = random))
    }

    @Test
    fun `a Queue screen move in order is the timeline move`() {
        val o = origins("snass")
        val order = listOf(0, 1, 2, 3, 4)
        assertEquals(QueueReorder(3, 1, UserAdded, null), queueReorder(o, order, 0, shuffled = false, fromPos = 3, toPos = 1))
        assertEquals(QueueReorder(1, 4, Source, null), queueReorder(o, order, 0, shuffled = false, fromPos = 1, toPos = 4))
        assertNull("the playing row never moves", queueReorder(o, order, 0, shuffled = false, fromPos = 0, toPos = 2))
        assertNull("nothing lands above the playing row", queueReorder(o, order, 2, shuffled = false, fromPos = 3, toPos = 1))
        assertNull("a drop where it was is no move", queueReorder(o, order, 0, shuffled = false, fromPos = 2, toPos = 2))
    }

    /** Timeline `ssnasss`, playing 1, its user group 2 and 3 lifted right after it in the shuffled play order. */
    private val shuffledOrigins = origins("ssnasss")
    private val shuffledPlay = listOf(5, 1, 2, 3, 6, 0, 4)

    @Test
    fun `in shuffle a source row dropped into the user group joins it in the timeline too`() {
        assertEquals(
            QueueReorder(from = 4, to = 2, origin = UserAdded, order = listOf(5, 1, 2, 3, 4, 6, 0)),
            queueReorder(shuffledOrigins, shuffledPlay, 1, shuffled = true, fromPos = 6, toPos = 2),
        )
    }

    @Test
    fun `in shuffle a source row from before the playing one lands inside the group`() {
        assertEquals(
            QueueReorder(from = 0, to = 2, origin = UserAdded, order = listOf(5, 0, 1, 2, 3, 6, 4)),
            queueReorder(shuffledOrigins, shuffledPlay, 1, shuffled = true, fromPos = 5, toPos = 3),
        )
    }

    @Test
    fun `in shuffle a user row dropped among the source leaves the group and keeps its play slot`() {
        assertEquals(
            QueueReorder(from = 2, to = 3, origin = Source, order = listOf(5, 1, 2, 6, 0, 3, 4)),
            queueReorder(shuffledOrigins, shuffledPlay, 1, shuffled = true, fromPos = 2, toPos = 5),
        )
    }

    @Test
    fun `in shuffle a source row moved within the source changes only the play order`() {
        assertEquals(
            QueueReorder(from = 6, to = 6, origin = Source, order = listOf(5, 1, 2, 3, 0, 4, 6)),
            queueReorder(shuffledOrigins, shuffledPlay, 1, shuffled = true, fromPos = 4, toPos = 6),
        )
    }

    @Test
    fun `Play next on a queued row moves it to the top of the user group as play next`() {
        val order = listOf(0, 1, 2, 3, 4)
        assertEquals("a source row", QueueReorder(3, 1, UserNext, null), queuePlayNext(order, 0, shuffled = false, index = 3))
        assertEquals("an added row inside the group", QueueReorder(2, 1, UserNext, null), queuePlayNext(order, 0, shuffled = false, index = 2))
        assertEquals("the row already next is only retagged", QueueReorder(1, 1, UserNext, null), queuePlayNext(order, 0, shuffled = false, index = 1))
        assertNull("the playing row", queuePlayNext(order, 2, shuffled = false, index = 2))
        assertNull("a row that already played", queuePlayNext(order, 2, shuffled = false, index = 1))
        assertNull("a row that is gone", queuePlayNext(order, 2, shuffled = false, index = 7))
    }

    @Test
    fun `in shuffle Play next lifts a row from before the playing one to right after it in both orders`() {
        assertEquals(
            QueueReorder(from = 0, to = 1, origin = UserNext, order = listOf(5, 0, 1, 2, 3, 6, 4)),
            queuePlayNext(shuffledPlay, 1, shuffled = true, index = 0),
        )
    }

    @Test
    fun `Play next keeps the playing song and the rest of the queue, in order and in shuffle`() {
        val items = listOf("e0", "e1", "e2", "e3", "e4")
        val o = origins("snass")
        for (current in items.indices) {
            val orders = listOf(false to items.indices.toList()) +
                permutations(items.indices.toList()).map { true to shuffledOrder(it, current, o) }
            for ((shuffled, order) in orders) {
                for (index in items.indices) {
                    val case = "shuffled $shuffled order $order current $current index $index"
                    val pc = order.indexOf(current)
                    val pn = queuePlayNext(order, current, shuffled, index)
                    if (order.indexOf(index) <= pc) {
                        assertNull(case, pn)
                        continue
                    }
                    requireNotNull(pn) { case }
                    val timeline = items.indices.sortedBy { movedIndex(it, pn.from, pn.to) }.map { items[it] }
                    val origins = o.indices.sortedBy { movedIndex(it, pn.from, pn.to) }.map { if (it == index) pn.origin else o[it] }
                    val played = (pn.order ?: timeline.indices.toList()).map { timeline[it] }
                    val newCurrent = timeline.indexOf(items[current])
                    val expected = order.map { items[it] }.let { it.take(pc + 1) + items[index] + it.drop(pc + 1).filter { e -> e != items[index] } }
                    assertEquals("$case: play order", expected, played)
                    assertEquals("$case: right after the playing song in the timeline", items[index], timeline[newCurrent + 1])
                    assertEquals("$case: tagged play next", UserNext, origins[newCurrent + 1])
                    val groups = queueGroups(pn.order ?: timeline.indices.toList(), newCurrent, origins)
                    assertEquals("$case: first in Next in queue", newCurrent + 1, groups.nextInQueue.first())
                }
            }
        }
    }

    @Test
    fun `moveMediaItem shifts the entries between from and to by one`() {
        // The timeline after the move, listed by each entry's old index.
        fun after(from: Int, to: Int): List<Int> = (0..4).sortedBy { movedIndex(it, from, to) }
        assertEquals(listOf(0, 3, 1, 2, 4), after(from = 3, to = 1))
        assertEquals(listOf(0, 2, 3, 1, 4), after(from = 1, to = 3))
        assertEquals(listOf(0, 1, 2, 3, 4), after(from = 4, to = 4))
    }

    private fun origins(spec: String): List<QueueOrigin> = spec.map {
        when (it) {
            'n' -> UserNext
            'a' -> UserAdded
            else -> Source
        }
    }

    private fun permutations(list: List<Int>): List<List<Int>> =
        if (list.size <= 1) listOf(list) else list.flatMap { head -> permutations(list - head).map { listOf(head) + it } }
}
