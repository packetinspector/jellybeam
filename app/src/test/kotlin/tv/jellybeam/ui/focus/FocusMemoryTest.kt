package tv.jellybeam.ui.focus

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMemoryTest {

    // -- resolveRestoreOrder --------------------------------------------

    @Test
    fun `invoker outranks last outranks selected`() {
        assertEquals(
            listOf("invoker", "last", "selected"),
            resolveRestoreOrder(lastKey = "last", invokerKey = "invoker", selectedKey = "selected"),
        )
    }

    @Test
    fun `nulls are skipped`() {
        assertEquals(
            listOf("last"),
            resolveRestoreOrder(lastKey = "last", invokerKey = null, selectedKey = null),
        )
        assertEquals(
            emptyList<String>(),
            resolveRestoreOrder(lastKey = null, invokerKey = null, selectedKey = null),
        )
    }

    @Test
    fun `duplicate keys across roles are collapsed`() {
        assertEquals(
            listOf("a", "b"),
            resolveRestoreOrder(lastKey = "a", invokerKey = "a", selectedKey = "b"),
        )
        assertEquals(
            listOf("a"),
            resolveRestoreOrder(lastKey = "a", invokerKey = "a", selectedKey = "a"),
        )
    }

    // -- register / unregister -------------------------------------------

    private class FakeTarget : FocusTarget {
        override fun requestFocus(): Boolean = true
    }

    @Test
    fun `unregister removes a registered target`() {
        val memory = FocusMemory(null, null)
        val target = FakeTarget()
        memory.register("k", target)
        assertTrue(memory.hasKey("k"))

        memory.unregister("k", target)
        assertFalse(memory.hasKey("k"))
        assertNull(memory.target("k"))
    }

    @Test
    fun `unregister is a no-op when a different instance now owns the key`() {
        val memory = FocusMemory(null, null)
        val stale = FakeTarget()
        val fresh = FakeTarget()
        memory.register("k", stale)
        // A recycled lazy item re-registers the same key before the old node's onDetach runs.
        memory.register("k", fresh)

        memory.unregister("k", stale)

        assertTrue(memory.hasKey("k"))
        assertTrue(memory.target("k") === fresh)
    }

    @Test
    fun `noteFocused writes lastKey when not frozen`() {
        val memory = FocusMemory(null, null)
        memory.noteFocused("k")
        assertEquals("k", memory.lastKey)
    }

    @Test
    fun `noteFocused is ignored while frozen`() {
        val memory = FocusMemory("original", null)
        memory.frozen = true
        memory.noteFocused("k")
        assertEquals("original", memory.lastKey)
    }

    @Test
    fun `captureInvoker copies the current lastKey`() {
        val memory = FocusMemory(null, null)
        memory.noteFocused("button")
        memory.captureInvoker()
        assertEquals("button", memory.invokerKey)
    }

    @Test
    fun `clearInvoker nulls the invoker`() {
        val memory = FocusMemory(null, "button")
        memory.clearInvoker()
        assertNull(memory.invokerKey)
    }

    // -- Saver round trip ---------------------------------------------------
    // Saver/SaverScope are plain JVM classes, so this runs as a plain unit test -- no Robolectric,
    // no composition host.

    @Test
    fun `Saver round trips both keys`() {
        val memory = FocusMemory("last", "invoker")
        val scope = SaverScope { true }
        val saved = with(FocusMemory.Saver) { scope.save(memory) }
        assertTrue(saved != null)

        @Suppress("UNCHECKED_CAST")
        val restored = (FocusMemory.Saver as androidx.compose.runtime.saveable.Saver<FocusMemory, Any>).restore(saved!!)

        assertEquals("last", restored?.lastKey)
        assertEquals("invoker", restored?.invokerKey)
    }

    @Test
    fun `Saver round trips null keys`() {
        val memory = FocusMemory(null, null)
        val scope = SaverScope { true }
        val saved = with(FocusMemory.Saver) { scope.save(memory) }

        @Suppress("UNCHECKED_CAST")
        val restored = (FocusMemory.Saver as androidx.compose.runtime.saveable.Saver<FocusMemory, Any>).restore(saved!!)

        assertNull(restored?.lastKey)
        assertNull(restored?.invokerKey)
    }
}
