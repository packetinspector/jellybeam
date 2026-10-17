package android.util

// Test-only: the unit-test android.jar stubs this class to a no-op, which would make every Media3 `Player.Commands` look empty.
open class SparseBooleanArray(@Suppress("UNUSED_PARAMETER") initialCapacity: Int = 10) {
    private val map = sortedMapOf<Int, Boolean>()

    open fun get(key: Int): Boolean = map[key] ?: false
    open fun get(key: Int, valueIfKeyNotFound: Boolean): Boolean = map[key] ?: valueIfKeyNotFound
    open fun put(key: Int, value: Boolean) {
        map[key] = value
    }
    open fun append(key: Int, value: Boolean) = put(key, value)
    open fun delete(key: Int) {
        map.remove(key)
    }
    open fun removeAt(index: Int) {
        map.remove(keyAt(index))
    }
    open fun clear() = map.clear()
    open fun size(): Int = map.size
    open fun keyAt(index: Int): Int = map.keys.elementAt(index)
    open fun valueAt(index: Int): Boolean = map.values.elementAt(index)
    open fun indexOfKey(key: Int): Int = map.keys.indexOf(key).let { if (it < 0) it.inv() else it }
    open fun indexOfValue(value: Boolean): Int = map.values.indexOf(value)
    open fun clone(): SparseBooleanArray = SparseBooleanArray().also { copy -> map.forEach { (k, v) -> copy.put(k, v) } }
    override fun equals(other: Any?): Boolean = other is SparseBooleanArray && other.map == map
    override fun hashCode(): Int = map.hashCode()
}
