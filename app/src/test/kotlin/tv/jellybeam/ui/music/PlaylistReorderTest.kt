package tv.jellybeam.ui.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.PlaylistEntry

/** docs/29 §9 E4 and the K6 reorder table: named rows, then every event sequence up to length four. */
class PlaylistReorderTest {

    private fun entry(n: Int) = PlaylistEntry("e$n", testCard(id = "s$n", itemType = "Audio", name = "Song $n"))
    private val abc = listOf(entry(1), entry(2), entry(3))

    private fun run(start: PlaylistReorder, vararg events: ReorderEvent): ReorderStep =
        events.fold(ReorderStep(start)) { step, e -> reduceReorder(step.state, e) }

    private fun ids(list: List<PlaylistEntry>) = list.map { it.entryId }

    private val loaded = PlaylistReorder(entries = abc)

    @Test
    fun `Reorder songs enters reordering with nothing held`() {
        assertEquals(ReorderMode.Browsing, run(loaded, ReorderEvent.Enter).state.mode)
    }

    @Test
    fun `a playlist with fewer than two songs never enters reordering`() {
        assertEquals(ReorderMode.Off, run(PlaylistReorder(entries = listOf(entry(1))), ReorderEvent.Enter).state.mode)
    }

    @Test
    fun `OK picks a row up and Up and Down move it live`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Step(1))
        assertEquals(ReorderMode.Holding("e1", from = 0, at = 2), step.state.mode)
        assertEquals(listOf("e2", "e3", "e1"), ids(step.state.shown()))
        assertEquals("e1", step.state.heldEntry())
        assertNull("nothing reaches the server while held", step.move)
    }

    @Test
    fun `a held row stops at either end`() {
        val up = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(-1))
        assertEquals(ReorderMode.Holding("e1", 0, 0), up.state.mode)
        val down = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(2), ReorderEvent.Step(1))
        assertEquals(ReorderMode.Holding("e3", 2, 2), down.state.mode)
    }

    @Test
    fun `OK drops the row with one move naming its entry and new index`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop)
        assertEquals(ReorderMove("e1", newIndex = 1, tag = 1), step.move)
        assertEquals(ReorderMode.Dropping("e1", 0, 1, tag = 1), step.state.mode)
        assertEquals("the drop shows where it lands", listOf("e2", "e1", "e3"), ids(step.state.shown()))
    }

    @Test
    fun `dropping where it was picked up sends nothing`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(1), ReorderEvent.Drop)
        assertNull(step.move)
        assertEquals(ReorderMode.Browsing, step.state.mode)
    }

    @Test
    fun `the server confirming the drop keeps the new order and stays in reordering`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop, ReorderEvent.Moved(1))
        assertEquals(ReorderMode.Browsing, step.state.mode)
        assertEquals(listOf("e2", "e1", "e3"), ids(step.state.entries))
        assertNull(step.state.failed)
    }

    @Test
    fun `a failed drop restores the order from before the pick-up and names the song`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(2), ReorderEvent.Drop, ReorderEvent.MoveFailed(1))
        assertEquals(ReorderMode.Browsing, step.state.mode)
        assertEquals(listOf("e1", "e2", "e3"), ids(step.state.shown()))
        assertEquals("Song 1", step.state.failed)
        assertNull("shown once", run(step.state, ReorderEvent.NoticeShown).state.failed)
    }

    @Test
    fun `Back while holding puts the row back`() {
        val step = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(2), ReorderEvent.Step(-2), ReorderEvent.Back)
        assertEquals(ReorderMode.Browsing, step.state.mode)
        assertEquals(listOf("e1", "e2", "e3"), ids(step.state.shown()))
        assertEquals(ReorderMode.Off, run(step.state, ReorderEvent.Back).state.mode)
    }

    @Test
    fun `a stale or out-of-order result changes nothing`() {
        val first = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop, ReorderEvent.Moved(1))
        val second = run(first.state, ReorderEvent.PickUp(2), ReorderEvent.Step(-2), ReorderEvent.Drop)
        assertEquals(2L, second.move?.tag)
        for (stale in listOf(ReorderEvent.Moved(1), ReorderEvent.MoveFailed(1), ReorderEvent.Moved(7), ReorderEvent.MoveFailed(0))) {
            assertEquals(stale.toString(), second.state, reduceReorder(second.state, stale).state)
        }
        assertEquals(loaded, reduceReorder(loaded, ReorderEvent.MoveFailed(1)).state)
    }

    @Test
    fun `a refresh while holding or dropping cancels to Browsing on the fresh list`() {
        val fresh = listOf(entry(3), entry(1), entry(2), entry(4))
        val holding = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1)).state
        val dropping = run(holding, ReorderEvent.Drop).state
        for (s in listOf(holding, dropping)) {
            val after = reduceReorder(s, ReorderEvent.Loaded(fresh)).state
            assertEquals(ReorderMode.Browsing, after.mode)
            assertEquals(ids(fresh), ids(after.shown()))
        }
        val late = run(dropping, ReorderEvent.Loaded(fresh), ReorderEvent.MoveFailed(1)).state
        assertNull("the cancelled drop's result is stale", late.failed)
    }

    @Test
    fun `while a drop is at the server Back and OK are held off`() {
        val dropping = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop).state
        for (e in listOf(ReorderEvent.Back, ReorderEvent.PickUp(1), ReorderEvent.Drop, ReorderEvent.Step(1), ReorderEvent.Enter)) {
            val step = reduceReorder(dropping, e)
            assertEquals(e.toString(), dropping, step.state)
            assertNull(step.move)
        }
    }

    @Test
    fun `a page read at the end keeps a held row where it is`() {
        val holding = run(loaded, ReorderEvent.Enter, ReorderEvent.PickUp(1), ReorderEvent.Step(1)).state
        val after = reduceReorder(holding, ReorderEvent.Appended(listOf(entry(4)))).state
        assertEquals(holding.mode, after.mode)
        assertEquals(listOf("e1", "e3", "e2", "e4"), ids(after.shown()))
    }

    private val alphabet = listOf(
        ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.PickUp(2), ReorderEvent.Step(1), ReorderEvent.Step(-1),
        ReorderEvent.Drop, ReorderEvent.Back, ReorderEvent.Moved(1), ReorderEvent.MoveFailed(1), ReorderEvent.Moved(2),
        ReorderEvent.Loaded(listOf(entry(2), entry(1), entry(3))), ReorderEvent.Loaded(listOf(entry(9))),
        ReorderEvent.Appended(listOf(entry(4))), ReorderEvent.NoticeShown,
    )

    @Test
    fun `every event sequence up to length four keeps the invariants`() {
        var steps = 0
        var moves = 0
        fun walk(s: PlaylistReorder, depth: Int) {
            if (depth == 4) return
            for (e in alphabet) {
                val step = reduceReorder(s, e)
                steps++
                checkInvariants(s, e, step)
                if (step.move != null) moves++
                walk(step.state, depth + 1)
            }
        }
        // Seeds past Enter and PickUp, so drops and their results fit inside four steps.
        val seeds = listOf(loaded, loaded.copy(mode = ReorderMode.Browsing), loaded.copy(mode = ReorderMode.Holding("e1", 0, 1)))
        seeds.forEach { walk(it, 0) }
        assertTrue("the sweep reached drops ($moves)", moves > 100)
        println("reorder sequences: $steps steps")
    }

    private fun checkInvariants(before: PlaylistReorder, e: ReorderEvent, step: ReorderStep) {
        val s = step.state
        val where = "$before + $e"
        assertEquals("$where: shown is a permutation of entries", ids(s.entries).sorted(), ids(s.shown()).sorted())
        when (val m = s.mode) {
            ReorderMode.Off, ReorderMode.Browsing -> assertEquals(where, s.entries, s.shown())
            is ReorderMode.Holding -> {
                assertTrue(where, m.at in s.entries.indices && m.from in s.entries.indices)
                assertEquals(where, m.entry, s.entries[m.from].entryId)
                assertEquals(where, m.entry, s.shown()[m.at].entryId)
            }
            is ReorderMode.Dropping -> {
                assertEquals("$where: the drop in flight is the newest tag", s.lastTag, m.tag)
                assertEquals(where, m.entry, s.entries[m.from].entryId)
            }
        }
        if (s.mode != ReorderMode.Off) assertTrue("$where: reordering needs two songs", s.entries.size > 1)
        val move = step.move
        if (move != null) {
            val held = before.mode as ReorderMode.Holding
            assertTrue("$where: a move leaves only Holding by Drop", e == ReorderEvent.Drop)
            assertEquals(where, ReorderMove(held.entry, held.at, s.lastTag), move)
            assertEquals(where, ReorderMode.Dropping(held.entry, held.from, held.at, move.tag), s.mode)
        }
        val result = e as? ReorderEvent.Moved ?: e as? ReorderEvent.MoveFailed
        val inFlight = before.mode as? ReorderMode.Dropping
        val tag = (e as? ReorderEvent.Moved)?.tag ?: (e as? ReorderEvent.MoveFailed)?.tag
        if (result != null && inFlight?.tag != tag) assertEquals("$where: a stale result changes nothing", before, s)
        if (e is ReorderEvent.MoveFailed && inFlight?.tag == tag) {
            assertEquals("$where: failure keeps the order from before the pick-up", before.entries, s.entries)
            assertTrue(where, s.failed != null)
        }
        if (s.failed != null && before.failed == null) assertTrue("$where: only a failure sets the notice", e is ReorderEvent.MoveFailed)
    }
}

/** docs/29 §9 E4 through [MusicPlaylistViewModel]: the drop reaches the server once, and its result settles the page. */
class MusicPlaylistReorderViewModelTest {

    @get:org.junit.Rule val main = tv.jellybeam.MainDispatcherRule()

    private val playlist = testCard(id = "pl", itemType = "Playlist", name = "Mix tape")
    private fun entry(n: Int) = PlaylistEntry("e$n", testCard(id = "s$n", itemType = "Audio", name = "Song $n"))
    private val gateway = tv.jellybeam.data.FakeCoreGateway().apply { playlistItemsByPlaylist = mapOf("pl" to listOf(entry(1), entry(2), entry(3))) }

    private fun opened() = MusicPlaylistViewModel(gateway, playlist, clock = { 0L }).apply { setActive(true) }

    private fun MusicPlaylistViewModel.ids() = state.value.reorder.shown().map { it.entryId }

    @Test
    fun `OK on a moved row sends one move with its entry and new index and keeps the order`() {
        val vm = opened()
        listOf(ReorderEvent.Enter, ReorderEvent.PickUp(2), ReorderEvent.Step(-1), ReorderEvent.Step(-1), ReorderEvent.Drop).forEach(vm::reorder)
        assertEquals(listOf("move:pl:e3:0"), gateway.playlistWrites)
        assertEquals(listOf("e3", "e1", "e2"), vm.ids())
        assertEquals(ReorderMode.Browsing, vm.state.value.reorder.mode)
    }

    @Test
    fun `a refused move puts the rows back and names the song`() {
        gateway.playlistWriteError = IllegalStateException("synthetic")
        val vm = opened()
        listOf(ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop).forEach(vm::reorder)
        assertEquals(listOf("e1", "e2", "e3"), vm.ids())
        assertEquals("Song 1", vm.state.value.reorder.failed)
    }

    @Test
    fun `a re-read while the drop is at the server wins over the drop's late failure`() = kotlinx.coroutines.test.runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.movePlaylistGate = gate
        val vm = opened()
        listOf(ReorderEvent.Enter, ReorderEvent.PickUp(0), ReorderEvent.Step(1), ReorderEvent.Drop).forEach(vm::reorder)
        gateway.playlistItemsByPlaylist = mapOf("pl" to listOf(entry(2), entry(1), entry(3), entry(4)))
        gateway.addToPlaylist("pl", listOf("s4"))
        assertEquals(ReorderMode.Browsing, vm.state.value.reorder.mode)
        gate.completeExceptionally(IllegalStateException("synthetic"))
        assertEquals(listOf("e2", "e1", "e3", "e4"), vm.ids())
        assertNull(vm.state.value.reorder.failed)
    }
}
