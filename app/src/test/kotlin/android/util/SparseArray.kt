package android.util

/**
 * Test-only stand-ins for the framework collections Media3's extractors keep state in: the unit
 * test android.jar stubs them to no-ops, so a real parser (AttachmentSeekingExtractorTest) would
 * find no tracks. Map-backed, keys kept sorted like the framework's.
 */
open class SparseArray<E> {
    private val map = sortedMapOf<Int, E>()

    open fun get(key: Int): E? = map[key]
    open fun get(key: Int, valueIfKeyNotFound: E): E = map[key] ?: valueIfKeyNotFound
    open fun put(key: Int, value: E) {
        map[key] = value
    }
    open fun append(key: Int, value: E) = put(key, value)
    open fun remove(key: Int) {
        map.remove(key)
    }
    open fun delete(key: Int) = remove(key)
    open fun size(): Int = map.size
    open fun keyAt(index: Int): Int = map.keys.elementAt(index)
    open fun valueAt(index: Int): E = map.values.elementAt(index)
    open fun indexOfKey(key: Int): Int = map.keys.indexOf(key)
    open fun clear() = map.clear()
}

open class LongSparseArray<E> {
    private val map = sortedMapOf<Long, E>()

    open fun get(key: Long): E? = map[key]
    open fun get(key: Long, valueIfKeyNotFound: E): E = map[key] ?: valueIfKeyNotFound
    open fun put(key: Long, value: E) {
        map[key] = value
    }
    open fun append(key: Long, value: E) = put(key, value)
    open fun remove(key: Long) {
        map.remove(key)
    }
    open fun delete(key: Long) = remove(key)
    open fun size(): Int = map.size
    open fun keyAt(index: Int): Long = map.keys.elementAt(index)
    open fun valueAt(index: Int): E = map.values.elementAt(index)
    open fun indexOfKey(key: Long): Int = map.keys.indexOf(key)
    open fun clear() = map.clear()
}
