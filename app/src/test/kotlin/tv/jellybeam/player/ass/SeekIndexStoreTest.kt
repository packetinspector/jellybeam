package tv.jellybeam.player.ass

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/18 §5.4: the seek-index files: format, keys, validators, atomic writes and the LRU bound. */
class SeekIndexStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private var clock = 1_000_000L
    private val dir by lazy { File(folder.root, "seekidx") }
    private val store by lazy { SeekIndexStore({ dir }, TestScope(dispatcher), dispatcher) { clock } }

    private fun index(n: Int = 4, length: Long = 1_000_000L, validator: Long = 7L, ssa: Int = 0) = SeekIndex(
        contentLength = length, validator = validator, segmentStart = 52, firstClusterPos = 400, durationUs = 60_000_000,
        timecodeScaleNs = 1_000_000, timesUs = LongArray(n) { it * 1_000_000L }, positions = LongArray(n) { 400L + it * 1000 },
        subtitleCues = List(ssa) { SubtitleCue(track = 3, timeUs = it * 2_000_000L, durationUs = 1_500_000, clusterOffset = 400L + it * 1000, relativePosition = 12) },
    )

    private fun save(key: String, index: SeekIndex) {
        store.save(key) { index }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `an index round-trips through its file`() {
        val original = index(ssa = 3)
        save("k1", original)
        val read = requireNotNull(store.read("k1"))
        assertEquals(original.contentLength, read.contentLength)
        assertEquals(original.validator, read.validator)
        assertEquals(original.segmentStart, read.segmentStart)
        assertEquals(original.firstClusterPos, read.firstClusterPos)
        assertEquals(original.durationUs, read.durationUs)
        assertEquals(original.timecodeScaleNs, read.timecodeScaleNs)
        assertEquals(original.subtitleCues, read.subtitleCues)
        assertArrayEquals(original.timesUs, read.timesUs)
        assertArrayEquals(original.positions, read.positions)
    }

    @Test
    fun `a damaged, foreign-version or disordered file is rejected and deleted`() {
        val good = encodeSeekIndex(index())
        val damaged = mapOf(
            "truncated" to good.copyOf(good.size - 3),
            "bad magic" to good.copyOf().also { it[0] = 0 },
            "a version-1 file" to good.copyOf().also { it[7] = 1 },
            "too many SSA entries" to encodeSeekIndex(index(ssa = MAX_STORED_SUBTITLE_CUES + 1)),
            "count of one" to encodeSeekIndex(index(n = 1)),
            "times out of order" to encodeSeekIndex(index().also { it.timesUs[2] = 0 }),
            "empty" to ByteArray(0),
        )
        dir.mkdirs()
        for ((name, bytes) in damaged) {
            val file = File(dir, "bad$INDEX_SUFFIX")
            file.writeBytes(bytes)
            assertNull(name, store.read("bad"))
            assertFalse("$name is deleted", file.exists())
        }
    }

    @Test
    fun `a write lands whole by rename, and a temp file left by a dead write is cleared`() {
        dir.mkdirs()
        File(dir, "k0.part").apply { writeBytes(ByteArray(10)) }.setLastModified(1_000L)
        assertNull("a temp file is never read as an index", store.read("k0"))
        save("k1", index())
        assertEquals(listOf("k1$INDEX_SUFFIX"), dir.list()!!.toList())
    }

    @Test
    fun `the store keeps the most recently used files within the count bound`() {
        for (i in 0 until MAX_INDEX_FILES) {
            save("k$i", index())
            File(dir, "k$i$INDEX_SUFFIX").setLastModified(1_000L * (i + 1))
        }
        clock = 500_000L
        assertNotNull("a hit is marked recently used", store.read("k0"))
        save("new", index())
        assertEquals(MAX_INDEX_FILES, dir.list()!!.size)
        assertNull("the least recently used goes", store.read("k1"))
        assertNotNull(store.read("k0"))
        assertNotNull(store.read("new"))
    }

    @Test
    fun `the store keeps the most recently used files within the byte bound`() {
        val big = index(n = MAX_SEEK_INDEX_CUES)
        val fit = (MAX_INDEX_DIR_BYTES / encodeSeekIndex(big).size).toInt()
        for (i in 0..fit) {
            save("k$i", big)
            File(dir, "k$i$INDEX_SUFFIX").setLastModified(1_000L * (i + 1))
        }
        assertEquals(fit, dir.list()!!.size)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= MAX_INDEX_DIR_BYTES)
        assertFalse("the oldest goes", File(dir, "k0$INDEX_SUFFIX").exists())
    }

    @Test
    fun `nothing is stored when the build declines, and a delete removes the file`() {
        store.save("k1") { null }
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
        save("k1", index())
        store.delete("k1")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(store.read("k1"))
    }

    @Test
    fun `forgetting a stream's URL drops the index a load of it stored`() {
        val url = "http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1&ApiKey=tok"
        save(requireNotNull(seekIndexKey(url)), index())
        save("other", index())
        store.forget("http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1&ApiKey=renewed")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("other$INDEX_SUFFIX"), dir.list()!!.toList())
    }

    @Test
    fun `the key names the static stream and nothing that identifies the viewer or the play`() {
        val url = "http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1&ApiKey=SECRETTOKEN"
        val key = requireNotNull(seekIndexKey(url))
        assertEquals("another token, a session, the legacy key", key, seekIndexKey("http://example.test:8096/Videos/item-1/stream?mediaSourceId=src-1&static=true&api_key=OTHER&PlaySessionId=p9"))
        assertEquals("no token at all", key, seekIndexKey("http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1"))
        assertNotEquals("another source", key, seekIndexKey("http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-2"))
        assertNotEquals("another server", key, seekIndexKey("http://other.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1"))
        assertNull("a transcode", seekIndexKey("http://example.test:8096/Videos/item-1/stream.mkv?mediaSourceId=src-1&VideoCodec=h264"))
        assertNull("no query", seekIndexKey("http://example.test:8096/Videos/item-1/stream"))
        assertFalse(key.contains("SECRET", ignoreCase = true))
        save(key, index())
        for (file in dir.listFiles()!!) {
            assertFalse(file.name.contains("SECRET"))
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("SECRET"))
        }
    }

    @Test
    fun `the file's size and validators come from the response headers`() {
        assertEquals(1_000_000_000L, contentLengthOf(mapOf("content-length" to listOf("1000000000"))))
        assertEquals("a range response gives the whole file", 5_000L, contentLengthOf(mapOf("Content-Range" to listOf("bytes 0-99/5000"), "Content-Length" to listOf("100"))))
        assertNull("unknown total", contentLengthOf(mapOf("Content-Range" to listOf("bytes 0-99/*"), "Content-Length" to listOf("100"))))
        assertNull("chunked", contentLengthOf(emptyMap()))
        assertEquals("neither validator", 0L, validatorOf(mapOf("content-length" to listOf("5"))))
        val lm = validatorOf(mapOf("last-modified" to listOf("Thu, 19 Nov 2026 14:09:30 GMT")))
        assertNotEquals(0L, lm)
        assertEquals("names match in any case", lm, validatorOf(mapOf("Last-Modified" to listOf("Thu, 19 Nov 2026 14:09:30 GMT"))))
        assertNotEquals(lm, validatorOf(mapOf("Last-Modified" to listOf("Fri, 20 Nov 2026 14:09:30 GMT"))))
        assertNotEquals(lm, validatorOf(mapOf("Last-Modified" to listOf("Thu, 19 Nov 2026 14:09:30 GMT"), "ETag" to listOf("\"v2\""))))
    }
}
