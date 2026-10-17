package tv.jellybeam.ui.focus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/15 §5: `frozen` is derived from placement tokens and `offTop`, never written. */
class PlacementTokenTest {

    @Test
    fun `concurrent placers swallow re-entry and keep frozen until the last one ends`() {
        val memory = FocusMemory("card:a", null)
        memory.seeded = true
        val restore = memory.beginPlacement()
        val drawer = memory.beginPlacement()

        assertTrue(memory.frozen)
        assertEquals(Reentry.Swallow, reentryAction(ready = true, seeded = memory.seeded, placing = memory.isPlacing))

        memory.endPlacement(restore)
        assertTrue("one placer still holds its token", memory.frozen)
        assertEquals(Reentry.Swallow, reentryAction(ready = true, seeded = memory.seeded, placing = memory.isPlacing))

        memory.endPlacement(drawer)
        assertFalse(memory.frozen)
        assertEquals(Reentry.Restore, reentryAction(ready = true, seeded = memory.seeded, placing = memory.isPlacing))
    }

    @Test
    fun `ending a token twice does not release another placer`() {
        val memory = FocusMemory(null, null)
        val first = memory.beginPlacement()
        memory.beginPlacement()
        memory.endPlacement(first)
        memory.endPlacement(first)
        assertTrue(memory.frozen)
    }

    @Test
    fun `focus events are not recorded while any token is held`() {
        val memory = FocusMemory("original", null)
        val token = memory.beginPlacement()
        memory.noteFocused("stray")
        assertEquals("original", memory.lastKey)
        memory.endPlacement(token)
        memory.noteFocused("chosen")
        assertEquals("chosen", memory.lastKey)
    }

    @Test
    fun `offTop freezes with no token held`() {
        val memory = FocusMemory(null, null)
        memory.offTop = true
        assertTrue(memory.frozen)
        assertFalse(memory.isPlacing)
        memory.offTop = false
        assertFalse(memory.frozen)
    }

    @Test
    fun `frozenBesides discounts only the caller's own tokens`() {
        val memory = FocusMemory(null, null)
        memory.beginPlacement()
        assertFalse("the caller's own running restore", memory.frozenBesides(own = 1))
        assertTrue(memory.frozenBesides(own = 0))
        memory.beginPlacement()
        assertTrue("a second placer is someone else's", memory.frozenBesides(own = 1))
        memory.offTop = true
        assertTrue(FocusMemory(null, null).apply { offTop = true }.frozenBesides(own = 1))
    }

    @Test
    fun `a cancelled placement releases its token`() = runTest {
        val memory = FocusMemory(null, null)
        val gate = CompletableDeferred<Unit>()
        val job = launch { memory.withPlacement { gate.await() } }
        runCurrent()
        assertTrue(memory.frozen)

        job.cancel()
        runCurrent()

        assertFalse("cancellation must not leak the token", memory.frozen)
    }
}
