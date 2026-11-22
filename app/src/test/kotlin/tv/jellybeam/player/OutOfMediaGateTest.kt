package tv.jellybeam.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.ass.uninitialized

/** docs/18 §7: [OutOfMediaGate] and [TsVideoPesCounter] decisions, and their wiring inside [CancelingDataSource]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OutOfMediaGateTest {
    /** android.jar stubs build no Uri, so no real DataSpec; an uninitialized one (position 0) passes the non-null
     * parameter, and [CancelingDataSource] reads the URL through the injected `urlOf` instead. */
    private val anySpec: DataSpec = uninitialized(DataSpec::class.java)

    /** A resumed load's spec: Media3 retries a failed chunk from where its extractor stopped. */
    private val resumedSpec: DataSpec = uninitialized(DataSpec::class.java).also {
        DataSpec::class.java.getDeclaredField("position").apply { isAccessible = true }.setLong(it, 9_400L)
    }
    private fun url(index: Int, prefix: String = "http://server", ticks: Boolean = true) =
        "$prefix/Videos/abc/hls1/main/$index.ts?MediaSourceId=abc&VideoCodec=h264" +
            if (ticks) "&runtimeTicks=${index * 60_000_000L}&actualSegmentLengthTicks=60000000" else ""

    private fun seg(index: Int) = OutOfMediaGate.segmentOf(url(index))!!

    /** One 188-byte TS packet on [pid]; [pes] stream_id starts a PES there, else a PSI section or continuation. */
    private fun packet(pid: Int, pes: Int? = null, adaptation: Int = 0): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        var o = 4
        if (adaptation > 0) {
            p[3] = 0x30
            p[4] = (adaptation - 1).toByte()
            o += adaptation
        } else {
            p[3] = 0x10
        }
        if (pes != null) {
            p[o] = 0; p[o + 1] = 0; p[o + 2] = 1; p[o + 3] = pes.toByte()
        } else {
            p[o] = 0; p[o + 1] = 0x02; p[o + 2] = 0xB0.toByte() // pointer_field, a PMT section
        }
        return p
    }

    /** The server's past-the-end stub: PAT, PMT, SDT and one audio PES (measured on Jellyfin 10.11), no video. */
    private val stub = packet(0) + packet(0x1000) + packet(0x11) + packet(0x101, pes = 0xC0) + packet(0x101)

    /** A real segment: tables, then a keyframe's video PES behind an adaptation field (PCR), then audio. */
    private val real = packet(0) + packet(0x1000) + packet(0x100, pes = 0xE0, adaptation = 8) +
        ByteArray(188 * 40) { if (it % 188 == 0) 0x47 else 0 } + packet(0x101, pes = 0xC0)

    /** An upstream that serves [body] for each index and counts the opens that reach it; [held] indices time out the
     * way OkHttp reports a request the server never answers. */
    private class Upstream(val held: Set<Int> = emptySet(), val body: (Int) -> ByteArray) : DataSource {
        var opens = 0
        var nextIndex = 0
        private var bytes = ByteArray(0)
        private var at = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            opens++
            if (nextIndex in held) throw java.io.IOException(java.net.SocketTimeoutException("timeout"))
            bytes = body(nextIndex)
            at = 0
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at == bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - at, 1_000) // odd chunks split packets
            System.arraycopy(bytes, at, buffer, offset, n)
            at += n
            return n
        }
        override fun getUri(): Uri? = null
        override fun close() {}
    }

    /** Loads segment [index] the way an HLS chunk load does, reading to the end; false when refused. */
    private fun load(
        gate: OutOfMediaGate?,
        upstream: Upstream,
        index: Int,
        url: String = url(index),
        spec: DataSpec = anySpec,
    ): Boolean {
        upstream.nextIndex = index
        val source = CancelingDataSource(upstream, ThreadLocal(), gate = gate, urlOf = { url })
        return try {
            source.open(spec)
            val buffer = ByteArray(4_096)
            while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
            true
        } catch (_: OutOfMediaException) {
            false
        } catch (e: java.io.IOException) {
            if (e.cause !is java.net.SocketTimeoutException) throw e
            false
        } finally {
            source.close()
        }
    }

    /** Every event the gate posts for load 1, in order. */
    private val posted = mutableListOf<EndEvent>()
    private fun recordingGate() = OutOfMediaGate(1L) { posted += it }
    private val trips get() = posted.filterIsInstance<EndEvent.Tripped>()
    private val lastRead get() = posted.filterIsInstance<EndEvent.MediaArrived>().lastOrNull()

    private fun truncatedAt(firstStub: Int) = Upstream { if (it >= firstStub) stub else real }

    @Test
    fun `only the tail of a Jellyfin ffmpeg segment path is gated, behind any proxy prefix`() {
        assertEquals(OutOfMediaGate.Segment(7, 42_000L, 48_000L), OutOfMediaGate.segmentOf(url(7)))
        assertEquals("index x 6 s without times", OutOfMediaGate.Segment(7, 42_000L, 48_000L), OutOfMediaGate.segmentOf(url(7, ticks = false)))
        assertEquals(7, OutOfMediaGate.segmentOf(url(7, prefix = "https://proxy.example/jf/sub"))?.index)
        assertEquals(3, OutOfMediaGate.segmentOf("http://s/videos/x/HLS1/main/3.ts")?.index)
        for (other in listOf(
            "http://s/Videos/x/stream.mkv?static=true",
            "http://s/Videos/x/master.m3u8?a=1",
            "http://s/Videos/x/main.m3u8?a=1",
            "http://s/Videos/x/hls1/main/-1.mp4",
            "http://s/Videos/x/x/Subtitles/2/0/Stream.vtt",
        )) assertNull(other, OutOfMediaGate.segmentOf(other))
    }

    @Test
    fun `the stub has no video PES and a real segment does, however the reads split packets`() {
        for (chunk in listOf(1, 7, 187, 188, 189, 4_096)) {
            fun count(bytes: ByteArray) = TsVideoPesCounter().apply {
                var i = 0
                while (i < bytes.size) { val n = minOf(chunk, bytes.size - i); feed(bytes, i, n); i += n }
            }.videoPesStarts
            assertEquals("chunk $chunk", 0, count(stub))
            assertEquals("chunk $chunk", 1, count(real))
        }
    }

    @Test
    fun `two consecutive stubs trip at the first stub and a real segment in between resets the run`() {
        val gate = recordingGate()
        gate.onSegmentRead(seg(0), hasVideo = true)
        gate.onSegmentRead(seg(1), hasVideo = false)
        gate.onSegmentRead(seg(2), hasVideo = true)
        gate.onSegmentRead(seg(3), hasVideo = false)
        gate.onSegmentRead(seg(5), hasVideo = false) // not consecutive: a seek, the run restarts
        assertFalse(gate.tripped)
        gate.onSegmentRead(seg(6), hasVideo = false)
        assertTrue(gate.tripped)
        assertEquals(5, gate.endIndex)
        assertEquals(listOf(EndEvent.Tripped(1L, 5, 30_000L)), trips)
        assertEquals("a seek landed in the run: the last real segment read", EndEvent.MediaArrived(1L, 2, 18_000L), lastRead)
        gate.onSegmentRead(seg(7), hasVideo = false)
        assertEquals("the same run never re-trips", 5, gate.endIndex)
        assertEquals(1, trips.size)
    }

    @Test
    fun `black, silent or slideshow stretches of any length never trip - each segment still carries a frame`() {
        // A 6 s segment of one static frame and no sound is tables plus a single video PES: a few hundred bytes.
        val static = packet(0) + packet(0x1000) + packet(0x100, pes = 0xE0)
        val gate = recordingGate()
        val upstream = Upstream { if (it in 10..30) static else real }
        for (i in 0..40) assertTrue(load(gate, upstream, i))
        assertFalse(gate.tripped)
        assertTrue(trips.isEmpty())
        assertEquals(EndEvent.MediaArrived(1L, 40, 246_000L), lastRead)
    }

    @Test
    fun `a copied segment whose frames include no keyframe still has video`() {
        // Stream copy cuts where it likes: a PES of a non-IDR slice (NAL type 1) is a frame all the same.
        val nonIdr = packet(0x100, pes = 0xE0).also { it[13] = 0; it[14] = 0; it[15] = 1; it[16] = 0x41 }
        val copied = packet(0) + packet(0x1000) + nonIdr + packet(0x101, pes = 0xC0)
        assertEquals(true, TsVideoPesCounter().apply { feed(copied, 0, copied.size) }.hasVideo())
    }

    @Test
    fun `a held-open request counts as video-less only right after one that was`() {
        // The server holds the second post-end request open: it continues a run, so the gate trips on it.
        val afterStub = Upstream(held = setOf(9)) { if (it >= 8) stub else real }
        val gate = OutOfMediaGate()
        for (i in 0..9) load(gate, afterStub, i)
        assertTrue(gate.tripped)
        assertEquals(8, gate.endIndex)
        assertFalse("the held retry is refused locally", load(gate, afterStub, 9))
        assertEquals(10, afterStub.opens)

        // A slow real segment that times out after real media is a network problem, never the end.
        val afterReal = Upstream(held = setOf(8)) { real }
        val healthy = OutOfMediaGate()
        for (i in 0..7) load(healthy, afterReal, i)
        repeat(5) { load(healthy, afterReal, 8) }
        assertFalse(healthy.tripped)
    }

    @Test
    fun `a slow healthy transcode with a short final segment never trips`() {
        val gate = OutOfMediaGate()
        repeat(1_199) { gate.onSegmentRead(seg(it), hasVideo = true) }
        gate.onSegmentRead(seg(1_199), hasVideo = true) // the file's real, short last segment
        assertFalse(gate.tripped)
    }

    @Test
    fun `exactly two opens reach the server past the end, then none - not a back-skip refill, a seek, or a held retry`() {
        val upstream = truncatedAt(8)
        val gate = recordingGate()
        var index = 0
        while (load(gate, upstream, index)) index++
        assertEquals("segments 0-9: 8 real, 2 stubs", 10, upstream.opens)
        assertEquals(8, gate.endIndex)
        assertEquals("the run directly follows a read segment: it starts where media ends", listOf(EndEvent.Tripped(1L, 8, 48_000L)), trips)
        assertEquals(EndEvent.MediaArrived(1L, 7, 48_000L), lastRead)

        for (i in 5..7) assertTrue("a back-skip re-reads real media", load(gate, upstream, i))
        assertFalse("its refill stops at the end", load(gate, upstream, 8))
        assertFalse("a seek past the end", load(gate, upstream, 20))
        repeat(20) { assertFalse("a held retry", load(gate, upstream, 9)) }
        assertEquals("only the 3 real re-reads reached the server", 13, upstream.opens)
        assertEquals("tripped once", 1, trips.size)
    }

    @Test
    fun `without segment times in the URL the gate still bounds opens and ends at index times segment length`() {
        val upstream = truncatedAt(8)
        val gate = recordingGate()
        var index = 0
        while (load(gate, upstream, index, url = url(index, ticks = false))) index++
        assertEquals(10, upstream.opens)
        assertEquals(listOf(EndEvent.Tripped(1L, 8, 8 * OutOfMediaGate.SEGMENT_MS)), trips)
    }

    @Test
    fun `a partial read cut short never counts as a stub`() {
        val gate = OutOfMediaGate()
        val upstream = Upstream { stub }
        repeat(5) { i ->
            upstream.nextIndex = i
            val source = CancelingDataSource(upstream, ThreadLocal(), gate = gate, urlOf = { url(i) })
            source.open(anySpec)
            source.read(ByteArray(100), 0, 100) // canceled before the end
            source.close()
        }
        assertFalse(gate.tripped)
    }

    @Test
    fun `an earlier load's late segments never count toward the current load's gate`() {
        val earlier = OutOfMediaGate(1L) { posted += it } // the previous plan or item, still held by its own data sources
        val current = OutOfMediaGate(2L) { posted += it }
        val upstream = Upstream { stub }
        repeat(2) { load(earlier, upstream, 30 + it) }
        assertTrue(earlier.tripped)
        assertFalse(current.tripped)
        assertEquals("tagged with its own load, which the reducer drops once stale", listOf(EndEvent.Tripped(1L, 30, 180_000L)), posted)
        assertTrue(load(current, truncatedAt(Int.MAX_VALUE), 30))
    }

    @Test
    fun `without a gate (Direct Play) a source never parses the URL or counts bytes`() {
        val upstream = Upstream { stub }
        repeat(5) { assertTrue(load(null, upstream, it, url = "unused")) }
        val source = CancelingDataSource(upstream, ThreadLocal(), urlOf = { error("Direct Play parsed a URL") })
        source.open(anySpec)
        source.close()
    }

    @Test
    fun `CancelingDataSource declares every DataSource method itself`() {
        // A Java default method left to the interface would skip the delegate; each must be declared here.
        for (method in DataSource::class.java.methods) {
            if (java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
            CancelingDataSource::class.java.getDeclaredMethod(method.name, *method.parameterTypes)
        }
    }

    @Test
    fun `a tripped gate refuses any segment at or past its end and admits the rest`() {
        val gate = OutOfMediaGate().apply { repeat(2) { onSegmentRead(seg(5 + it), hasVideo = false) } }
        assertThrows(OutOfMediaException::class.java) { gate.admit(seg(5)) }
        assertThrows(OutOfMediaException::class.java) { gate.admit(seg(99)) }
        assertEquals(seg(4), gate.admit(seg(4)))
        assertNull(gate.admit(null))
    }

    @Test
    fun `a resumed read of the last real segment still ends media at the run's start`() {
        val upstream = truncatedAt(8)
        val gate = recordingGate()
        for (i in 0..6) load(gate, upstream, i)
        load(gate, upstream, 7, spec = resumedSpec) // a network blip: Media3 resumed segment 7 mid-way
        for (i in 8..9) load(gate, upstream, i)
        assertEquals(8, gate.endIndex)
        assertEquals("a resumed read is real media read", EndEvent.MediaArrived(1L, 7, 48_000L), lastRead)
        assertEquals(listOf(EndEvent.Tripped(1L, 8, 48_000L)), trips)
    }

    @Test
    fun `resumed stub reads never count, since a resume starts mid-segment`() {
        val gate = OutOfMediaGate()
        val upstream = Upstream { stub }
        repeat(5) { assertTrue(load(gate, upstream, it, spec = resumedSpec)) }
        assertFalse(gate.tripped)
    }

    @Test
    fun `bytes that are not plain 188-byte TS prove nothing - an error page, M2TS or leading junk never trip`() {
        val html = "<html><body>Bad gateway</body></html>".toByteArray()
        val m2ts = stub.toList().chunked(188).flatMap { listOf<Byte>(0, 0, 0, 0) + it }.toByteArray()
        val junk = byteArrayOf(0x00) + stub
        for (body in listOf(html, m2ts, junk)) {
            val gate = OutOfMediaGate()
            for (i in 0..5) load(gate, Upstream { body }, i)
            assertFalse(gate.tripped)
            assertEquals(null, TsVideoPesCounter().apply { feed(body, 0, body.size) }.hasVideo())
        }
        // Unknown in the middle of a run breaks it.
        val gate = OutOfMediaGate()
        gate.onSegmentRead(seg(0), hasVideo = false)
        gate.onSegmentRead(seg(1), hasVideo = null)
        gate.onSegmentRead(seg(2), hasVideo = false)
        assertFalse(gate.tripped)
    }

    @Test
    fun `after a void trip, playing forward into the true end launches at most 2, then re-trips lower`() {
        val upstream = truncatedAt(50)
        val gate = recordingGate()
        for (i in 0..3) assertTrue(load(gate, upstream, i))
        for (i in 100..101) load(gate, upstream, i) // a seek into the void
        assertEquals(listOf(EndEvent.Tripped(1L, 100, 600_000L)), trips)
        val before = upstream.opens

        var index = 4
        while (load(gate, upstream, index)) index++ // the seek back plays forward
        assertEquals("46 real segments, then 2 stubs", 48, upstream.opens - before)
        assertEquals(50, gate.endIndex)
        assertEquals(listOf(EndEvent.Tripped(1L, 100, 600_000L), EndEvent.Tripped(1L, 50, 300_000L)), trips)
        assertEquals(EndEvent.MediaArrived(1L, 49, 300_000L), lastRead)
        assertFalse("a stub between the true end and the void stays refused", load(gate, upstream, 70))

        for (i in 100..101) load(gate, upstream, i)
        assertEquals("the void stays refused, so nothing raises the end", 2, trips.size)
    }
}
