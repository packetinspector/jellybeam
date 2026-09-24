package tv.jellybeam.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedLruCacheTest {

    @Test
    fun `get returns a previously put value`() {
        val cache = BoundedLruCache<String, String>(4)
        cache.put("a", "value-a")
        assertEquals("value-a", cache.get("a"))
    }

    @Test
    fun `get is null for a key never put`() {
        val cache = BoundedLruCache<String, String>(4)
        assertNull(cache.get("missing"))
    }

    @Test
    fun `evicts the least recently used entry once past capacity`() {
        val cache = BoundedLruCache<String, String>(2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun `a get refreshes recency, protecting it from the next eviction`() {
        val cache = BoundedLruCache<String, String>(2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a") // touch "a" so "b" becomes the least-recently-used entry
        cache.put("c", "3")

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun `put overwriting an existing key does not evict anything`() {
        val cache = BoundedLruCache<String, String>(2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("a", "1-updated")

        assertEquals("1-updated", cache.get("a"))
        assertEquals("2", cache.get("b"))
    }

    @Test
    fun `rejects a non-positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) { BoundedLruCache<String, String>(0) }
    }
}
