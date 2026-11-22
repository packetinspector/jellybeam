package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File
import java.util.TreeMap
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * docs/18 §5.4: runs the real MatroskaExtractor over synthetic files through the app's factory: a cold
 * load stores its primary track's cues, a later load of the same file never seeks to the Cues and gets
 * a seek map that answers as Media3's own does.
 */
class MatroskaSeekIndexTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun giveBuildAFingerprint() {
            fakeBuildFingerprint()
            // Media3 warns when it drops a cue past the duration; the stub android Log can't format that off-device.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        }

        const val URL = "http://example.test:8096/Videos/item-1/stream?static=true&mediaSourceId=src-1&ApiKey=tok"
        const val LAST_MODIFIED = "Thu, 19 Nov 2026 14:09:30 GMT"
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val dir by lazy { File(folder.root, "seekidx") }
    private val store by lazy { SeekIndexStore({ dir }, TestScope(dispatcher), dispatcher) }
    private val key = requireNotNull(seekIndexKey(URL))

    private class Fonts(private val wants: Boolean) : AssFontSink {
        /** Each subtitleCues delivery: its tracks, entries and time scale. */
        val delivered = mutableListOf<Triple<Set<Int>, List<SubtitleCue>, Long>>()
        override fun wantsFonts() = wants
        override fun attachments(block: AttachmentsBlock) = Unit
        override fun subtitleCues(cues: SubtitleCues, timecodeScaleNs: Long) {
            delivered += Triple(cues.tracks, cues.entries(), timecodeScaleNs)
        }
    }

    /** [this] with its layout facts or offsets changed, the rest kept. */
    private fun SeekIndex.with(firstClusterPos: Long = this.firstClusterPos, durationUs: Long = this.durationUs, positions: LongArray = this.positions) =
        SeekIndex(contentLength, validator, segmentStart, firstClusterPos, durationUs, timecodeScaleNs, timesUs, positions, subtitleCues)

    /** What Media3 would see: seek maps in order, sample times in order, and where the reader was at endTracks. */
    private class Recorder : ExtractorOutput {
        val maps = mutableListOf<SeekMap>()
        val sampleTimes = mutableListOf<Long>()
        var position: () -> Long = { -1L }
        var endTracksAt = -1L

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val scratch = ByteArray(64 * 1024)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                input.read(scratch, 0, minOf(length, scratch.size))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                sampleTimes += timeUs
            }
        }

        override fun endTracks() {
            endTracksAt = position()
        }

        override fun seekMap(seekMap: SeekMap) {
            maps += seekMap
        }
    }

    private class Load(val extractor: Extractor, val loader: FileLoader, val output: Recorder)

    private fun headers(length: Long, lastModified: String? = LAST_MODIFIED): Map<String, List<String>> =
        mapOf("content-length" to listOf(length.toString())) + (lastModified?.let { mapOf("last-modified" to listOf(it)) } ?: emptyMap())

    /** The app's extractor for [mkv], made as the factory makes it for a load with [headers]. */
    private fun open(
        mkv: SyntheticMkv,
        headers: Map<String, List<String>> = headers(mkv.bytes.size.toLong()),
        wantsFonts: Boolean = false,
        fonts: Fonts = Fonts(wantsFonts),
    ): Load {
        val factory = AssExtractorsFactory(fonts, store)
        factory.setSubtitleParserFactory(AssAwareSubtitleParserFactory())
        val extractor = factory.swap(arrayOf(MatroskaExtractor()), factory.seekIndexSlot(URL, headers)).single()
        val output = Recorder()
        val loader = FileLoader(mkv.bytes, extractor)
        output.position = { loader.position }
        extractor.init(output)
        return Load(extractor, loader, output)
    }

    private fun play(
        mkv: SyntheticMkv,
        headers: Map<String, List<String>> = headers(mkv.bytes.size.toLong()),
        wantsFonts: Boolean = false,
        fonts: Fonts = Fonts(wantsFonts),
    ): Load =
        open(mkv, headers, wantsFonts, fonts).also {
            it.loader.run()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun stored(): SeekIndex? = store.read(key)

    @Test
    fun `a cold load stores the primary track's cues as absolute cluster offsets`() {
        val mkv = syntheticMkv()
        val cold = play(mkv)
        assertTrue("the cold load read the Cues", mkv.cuesStart in cold.loader.seeks)
        val index = requireNotNull(stored())
        assertArrayEquals(LongArray(10) { it * 1_000_000L }, index.timesUs)
        assertArrayEquals("segment start + CueClusterPosition", mkv.clusterStarts.toLongArray(), index.positions)
        assertEquals(mkv.segmentStart, index.segmentStart)
        assertEquals(mkv.firstClusterContent, index.firstClusterPos)
        assertEquals(10_000_000L, index.durationUs)
        assertEquals(mkv.bytes.size.toLong(), index.contentLength)
        assertTrue("no SSA tracks, no SSA cues", index.subtitleCues.isEmpty())
    }

    @Test
    fun `a warm load never seeks to the Cues, ends tracks before the first cluster and gets a seekable map`() {
        val mkv = syntheticMkv()
        play(mkv)
        val warm = open(mkv)
        warm.loader.run { warm.output.maps.isNotEmpty() }
        assertTrue("no seek before the first seek map: ${warm.loader.seeks}", warm.loader.seeks.isEmpty())
        assertTrue("tracks ended at ${warm.output.endTracksAt}", warm.output.endTracksAt in 0..mkv.clusterStarts.first())
        assertTrue(warm.output.maps.single().isSeekable)
        warm.loader.run()
        assertTrue("never sought to the Cues: ${warm.loader.seeks}", warm.loader.seeks.isEmpty())
    }

    @Test
    fun `the cached map answers every time exactly as Media3's own map`() {
        val layouts = mapOf(
            "cues inside the duration" to syntheticMkv(),
            "a cue at the duration, which Media3 drops" to syntheticMkv(durationMs = 9_000.0),
            "first cue after zero" to syntheticMkv(clusterTimesMs = (1..8).map { it * 1500L }),
        )
        for ((name, mkv) in layouts) {
            folder.root.resolve("seekidx").deleteRecursively()
            val real = play(mkv).output.maps.single()
            assertTrue(name, real.isSeekable)
            val warm = play(mkv)
            assertTrue("$name: the second load hits", warm.loader.seeks.isEmpty())
            val cached = warm.output.maps.single()
            assertTrue(name, cached.isSeekable)
            assertEquals(name, real.durationUs, cached.durationUs)
            val times = listOf(0L, 1L, 999_999L, 1_000_000L, 1_500_000L, 4_500_000L, 8_999_999L, 9_000_000L, 9_500_000L, 10_000_000L, 60_000_000L)
            for (t in times) assertEquals("$name at $t", real.getSeekPoints(t), cached.getSeekPoints(t))
        }
    }

    @Test
    fun `a seek to a cached point reads the same first sample as one to Media3's point`() {
        val mkv = syntheticMkv()
        val cold = play(mkv)
        val warm = open(mkv)
        warm.loader.run { warm.output.maps.isNotEmpty() }
        fun firstSampleAfterSeek(load: Load, timeUs: Long): Long {
            val point = load.output.maps.single().getSeekPoints(timeUs).first
            load.extractor.seek(point.position, point.timeUs)
            val before = load.output.sampleTimes.size
            load.loader.run(point.position) { load.output.sampleTimes.size > before }
            return load.output.sampleTimes[before]
        }
        assertEquals(4_000_000L, firstSampleAfterSeek(cold, 4_500_000L))
        assertEquals(4_000_000L, firstSampleAfterSeek(warm, 4_500_000L))
    }

    @Test
    fun `a warm load reaching the Cues at the end emits no second map and stores nothing`() {
        val mkv = syntheticMkv()
        play(mkv)
        val warm = open(mkv)
        File(dir, "$key$INDEX_SUFFIX").delete()
        warm.loader.run()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, warm.output.maps.size)
        assertNull("not stored again", stored())
    }

    @Test
    fun `only the primary track's cues are kept`() {
        val tracks = listOf(MkvTrack(1, 2, "A_AC3"), MkvTrack(2, 1, "V_VP9", isDefault = false), MkvTrack(3, 1, "V_VP9"))
        val clusterTimes = (0 until 6).map { it * 1000L }
        val cues = clusterTimes.indices.flatMap { i ->
            listOf(MkvCue(clusterTimes[i], 3, i), MkvCue(clusterTimes[i] + 250, 1, i), MkvCue(clusterTimes[i] + 600, 2, i))
        }
        val mkv = syntheticMkv(tracks = tracks, clusterTimesMs = clusterTimes, cues = cues)
        play(mkv)
        assertArrayEquals("the default video track's", LongArray(6) { it * 1_000_000L }, requireNotNull(stored()).timesUs)
    }

    @Test
    fun `Media3's seek track is the default video, then video, then default audio, then audio, then the first track`() {
        fun primary(vararg tracks: Triple<Int, Int, Boolean?>): Int {
            val types = TreeMap<Int, Int>()
            val defaults = HashMap<Int, Boolean>()
            for ((n, type, def) in tracks) {
                types[n] = type
                def?.let { defaults[n] = it }
            }
            return primarySeekTrack(types, defaults)
        }
        val v = C.TRACK_TYPE_VIDEO
        val a = C.TRACK_TYPE_AUDIO
        val t = C.TRACK_TYPE_TEXT
        assertEquals("the last default video", 3, primary(Triple(1, a, null), Triple(2, v, null), Triple(3, v, true)))
        assertEquals("FlagDefault defaults to on", 2, primary(Triple(1, a, true), Triple(2, v, null)))
        assertEquals("the first video when none is default", 2, primary(Triple(1, a, true), Triple(2, v, false), Triple(3, v, false)))
        assertEquals("the last default audio", 3, primary(Triple(1, t, null), Triple(2, a, true), Triple(3, a, true)))
        assertEquals("the first audio", 2, primary(Triple(1, t, null), Triple(2, a, false), Triple(3, a, false)))
        assertEquals("the lowest-numbered track", 4, primary(Triple(7, t, null), Triple(4, t, null)))
        assertEquals("no tracks", -1, primary())
    }

    @Test
    fun `cues closer than half a second to the last kept one are dropped`() {
        val clusterTimes = listOf(0L, 200, 600, 1000, 1100, 1700, 3000)
        val mkv = syntheticMkv(clusterTimesMs = clusterTimes, durationMs = 4000.0)
        play(mkv)
        val index = requireNotNull(stored())
        assertArrayEquals(longArrayOf(0, 600_000, 1_100_000, 1_700_000, 3_000_000), index.timesUs)
        assertArrayEquals(listOf(0, 2, 4, 5, 6).map { mkv.clusterStarts[it] }.toLongArray(), index.positions)
    }

    @Test
    fun `an index past the cue cap is abandoned`() {
        fun collect(cues: Int): SeekIndex? {
            File(dir, "$key$INDEX_SUFFIX").delete()
            val slot = SeekIndexSlot(store, key, contentLength = Long.MAX_VALUE, validator = 0, hit = null, persist = true)
            val indexer = MatroskaSeekIndexer(slot)
            indexer.track(1, C.TRACK_TYPE_VIDEO)
            indexer.cluster(100)
            indexer.cuesStarted()
            val times = LongArray(cues) { it * MIN_CUE_GAP_US }
            val positions = LongArray(cues) { 100L + it }
            for (i in 0 until cues) indexer.cue(1, times[i], positions[i])
            val media3 = CachedSeekMap(SeekIndex(0, 0, 0, 0, 0, 1_000_000, times, positions, emptyList()), Long.MAX_VALUE)
            indexer.seekMap(media3, segmentStart = 0)
            indexer.cuesEnded(segmentStart = 0, subtitles = null, timecodeScaleNs = 1_000_000)
            dispatcher.scheduler.advanceUntilIdle()
            return stored()
        }
        assertEquals(MAX_SEEK_INDEX_CUES, collect(MAX_SEEK_INDEX_CUES)?.timesUs?.size)
        assertNull(collect(MAX_SEEK_INDEX_CUES + 1))
    }

    @Test
    fun `nothing is stored from Cues before the clusters, from one cue, or past the file's length`() {
        play(syntheticMkv(cuesBeforeClusters = true))
        assertNull("Cues before the clusters cost no extra request", stored())
        play(syntheticMkv(cues = listOf(MkvCue(0, 1, 0))))
        assertNull("one cue", stored())
        val mkv = syntheticMkv()
        play(mkv, headers(mkv.clusterStarts.last()))
        assertNull("a cluster at or past the length", stored())
    }

    @Test
    fun `a different size or validator, or no length, reads the Cues as before`() {
        val mkv = syntheticMkv()
        val size = mkv.bytes.size.toLong()
        play(mkv)
        val rows = mapOf(
            "another size" to headers(size + 1),
            "another Last-Modified" to headers(size, "Fri, 20 Nov 2026 14:09:30 GMT"),
            "no Last-Modified where one was stored" to headers(size, lastModified = null),
            "no length" to mapOf("last-modified" to listOf(LAST_MODIFIED)),
        )
        for ((name, h) in rows) {
            val load = open(mkv, h)
            load.loader.run()
            assertTrue("$name: ${load.loader.seeks}", mkv.cuesStart in load.loader.seeks)
        }
        val load = open(mkv, headers(size))
        load.loader.run()
        assertTrue("the original still hits", load.loader.seeks.isEmpty())
    }

    @Test
    fun `the no-argument factory call never uses the cache`() {
        val mkv = syntheticMkv()
        play(mkv)
        val extractor = AssExtractorsFactory(Fonts(false), store).swap(arrayOf(MatroskaExtractor()), seekIndex = null).single()
        val loader = FileLoader(mkv.bytes, extractor)
        extractor.init(Recorder())
        loader.run()
        assertTrue(mkv.cuesStart in loader.seeks)
    }

    @Test
    fun `a file whose layout moved drops its index and fails as a miss, so the player retries cold`() {
        val mkv = syntheticMkv()
        play(mkv)
        val moved = requireNotNull(stored()).with(firstClusterPos = mkv.firstClusterContent + 1)
        store.save(key) { moved }
        dispatcher.scheduler.advanceUntilIdle()
        val warm = open(mkv)
        val miss = runCatching { warm.loader.run { warm.output.maps.isNotEmpty() } }.exceptionOrNull()
        assertTrue("never left unseekable: $miss", miss is SeekIndexMiss)
        assertTrue(warm.output.maps.isEmpty())
        assertNull("dropped at once", stored())
        val retry = play(mkv)
        assertTrue("the retry reads the Cues cold", mkv.cuesStart in retry.loader.seeks)
        assertTrue(retry.output.maps.single().isSeekable)
    }

    @Test
    fun `a hit on a file with SSA tracks hands styled subtitles the same Cues entries a cold pass does`() {
        val ssaHeader = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        val tracks = listOf(MkvTrack(1, 1, "V_VP9"), MkvTrack(2, 2, "A_AC3"), MkvTrack(3, 0x11, "S_TEXT/ASS", codecPrivate = ssaHeader.toByteArray()))
        val clusterTimes = (0 until 8).map { it * 1000L }
        val cues = clusterTimes.indices.flatMap { listOf(MkvCue(clusterTimes[it], 1, it), MkvCue(clusterTimes[it] + 300, 3, it)) }
        val mkv = syntheticMkv(tracks = tracks, clusterTimesMs = clusterTimes, cues = cues)
        // Stored from a cold pass with styling off, so the entries are kept for a later styled start.
        play(mkv, wantsFonts = false)
        val coldFonts = Fonts(wants = true)
        val uncached = AssExtractorsFactory(coldFonts).apply { setSubtitleParserFactory(AssAwareSubtitleParserFactory()) }
            .swap(arrayOf(MatroskaExtractor()), seekIndex = null).single()
        uncached.init(Recorder())
        FileLoader(mkv.bytes, uncached).run()
        val cold = coldFonts.delivered.first()
        assertEquals(8, cold.second.size)
        val warmFonts = Fonts(wants = true)
        val warm = open(mkv, fonts = warmFonts)
        warm.loader.run { warm.output.maps.isNotEmpty() }
        assertTrue("styled: hits", warm.loader.seeks.isEmpty())
        assertEquals("delivered by the first cluster, as the cold pass would", cold, warmFonts.delivered.single())
    }

    @Test
    fun `a parse failure on a stored index drops it at once and comes back marked, a cold one and a network one don't`() {
        val mkv = syntheticMkv()
        play(mkv)
        val good = requireNotNull(stored())
        // Mid-element offsets: what a file rewritten under the same size and Last-Modified would leave.
        val mid = good.with(positions = LongArray(good.positions.size) { good.positions[it] + 5 })
        store.save(key) { mid }
        dispatcher.scheduler.advanceUntilIdle()
        val warm = open(mkv)
        warm.loader.run { warm.output.maps.isNotEmpty() }
        val point = warm.output.maps.single().getSeekPoints(4_500_000).first
        warm.extractor.seek(point.position, point.timeUs)
        val miss = runCatching { warm.loader.run(point.position) }.exceptionOrNull()
        assertTrue("marked: $miss", miss is SeekIndexMiss)
        assertTrue(miss?.cause is IllegalStateException)
        assertNull("dropped before the retry's lookup", stored())
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(File(dir, "$key$INDEX_SUFFIX").exists())

        val cold = AttachmentSeekingExtractor(AssMatroskaExtractor(AssAwareSubtitleParserFactory(), 0, Fonts(false)))
        cold.init(Recorder())
        FileLoader(mkv.bytes, cold).run { false }
        cold.seek(mid.positions[4], 4_000_000)
        val coldError = runCatching { FileLoader(mkv.bytes, cold).run(mid.positions[4]) }.exceptionOrNull()
        assertTrue("a cold load's failure is unchanged: $coldError", coldError is IllegalStateException)

        play(mkv)
        val network = open(mkv)
        val io = runCatching { FileLoader(mkv.bytes, network.extractor, ioErrorAt = mkv.clusterStarts[2]).run() }.exceptionOrNull()
        assertTrue("a network failure on a hit is unchanged: $io", io is java.io.IOException && io !is SeekIndexMiss)
        assertTrue("and keeps the index", stored() != null)
    }

    @Test
    fun `a stale offset at another cluster reads on from there without a crash`() {
        val mkv = syntheticMkv()
        play(mkv)
        val good = requireNotNull(stored())
        val shifted = LongArray(good.positions.size) { good.positions[minOf(it + 1, good.positions.lastIndex)] }
        val stale = good.with(positions = shifted)
        store.save(key) { stale }
        dispatcher.scheduler.advanceUntilIdle()
        val warm = open(mkv)
        warm.loader.run { warm.output.maps.isNotEmpty() }
        val point = warm.output.maps.single().getSeekPoints(4_500_000).first
        warm.extractor.seek(point.position, point.timeUs)
        val before = warm.output.sampleTimes.size
        assertTrue(warm.loader.run(point.position))
        assertEquals("reads on from the cluster it landed on", 5_000_000L, warm.output.sampleTimes[before])
    }
}
