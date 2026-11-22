package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder

/**
 * Media3's ParsableByteArray reads `Build.FINGERPRINT` once to detect tests, and the plain-JVM
 * android.jar stub leaves it null, which throws; any non-null value lets the real parser run.
 */
internal fun fakeBuildFingerprint() {
    val field = android.os.Build::class.java.getField("FINGERPRINT")
    val (unsafeClass, unsafe) = jvmUnsafe()
    val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
        .invoke(unsafe, base, offset, "jvm-unit-test")
}

/** sun.misc.Unsafe and its instance: not on the android.jar compile classpath, but the test JVM has it. */
private fun jvmUnsafe(): Pair<Class<*>, Any> {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    return unsafeClass to unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
}

/** An instance of [type] with no constructor run: for platform types the android.jar stubs can't build. */
internal fun <T> uninitialized(type: Class<T>): T {
    val (unsafeClass, unsafe) = jvmUnsafe()
    return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
}

/**
 * Media3's loader loop over one file: [run] reads from a position until the end or until [run]'s `stop`
 * says so, reopening wherever a read asks to seek; [seeks] holds those targets, [position] the reader's.
 * From [ioErrorAt] on (when set), every read fails as a dropped connection would.
 */
internal class FileLoader(private val file: ByteArray, private val extractor: Extractor, private val ioErrorAt: Long = -1L) {
    var bytesRead = 0L
        private set
    val seeks = mutableListOf<Long>()
    private var input: DefaultExtractorInput? = null
    val position: Long get() = input?.position ?: 0L

    private fun inputAt(position: Long): DefaultExtractorInput {
        var next = position.toInt()
        val reader = DataReader { buffer, offset, length ->
            if (next >= file.size) return@DataReader C.RESULT_END_OF_INPUT
            if (ioErrorAt in 0..next) throw java.io.IOException("network dropped")
            val n = minOf(length, file.size - next)
            file.copyInto(buffer, offset, next, next + n)
            next += n
            bytesRead += n
            n
        }
        return DefaultExtractorInput(reader, position, file.size.toLong()).also { input = it }
    }

    /** True when the file ended, false when [stop] held after a read. */
    fun run(from: Long = 0L, stop: () -> Boolean = { false }): Boolean {
        var input = inputAt(from)
        repeat(10_000) {
            val holder = PositionHolder()
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return true
                Extractor.RESULT_SEEK -> {
                    seeks += holder.position
                    input = inputAt(holder.position)
                }
            }
            if (stop()) return false
        }
        error("extractor never reached the end")
    }
}

/** Media3's loader loop over [file]: reads to the end, reopening wherever a read asks to seek; returns the bytes read. */
internal fun extractAll(file: ByteArray, extractor: Extractor, output: ExtractorOutput): Long {
    extractor.init(output)
    return FileLoader(file, extractor).also { it.run() }.bytesRead
}

/** A [RangeReader] over this file's bytes (fewer at its end, null past it); [onRead] sees each read. */
internal fun ByteArray.rangeReader(onRead: () -> Unit = {}) = RangeReader { offset, length ->
    onRead()
    if (offset !in indices) null else copyOfRange(offset.toInt(), minOf(size, offset.toInt() + length))
}

/** A synthetic Matroska track: [type] is the Matroska TrackType (1 video, 2 audio, 0x11 subtitle); null [isDefault] omits FlagDefault. */
internal class MkvTrack(val number: Int, val type: Int, val codec: String, val isDefault: Boolean? = null, val codecPrivate: ByteArray? = null)

/** One CuePoint: its time, track and the index of the cluster it points at. */
internal class MkvCue(val timeMs: Long, val track: Int, val cluster: Int)

/** A synthetic file and where its parts landed: absolute element starts, and the segment's data start. */
internal class SyntheticMkv(val bytes: ByteArray, val segmentStart: Long, val clusterStarts: List<Long>, val cuesStart: Long) {
    /** The first cluster's content position, which follows its 4-byte ID and 8-byte size. */
    val firstClusterContent: Long get() = clusterStarts.first() + 12
}

/**
 * A Matroska file built from parts: EBML header, Segment(SeekHead, Info, Tracks, Clusters, Cues), Cues
 * first when [cuesBeforeClusters]. Every size and position field is 8 bytes wide, so a layout's offsets
 * don't depend on its values. Each cluster holds one keyframe block per video or audio track.
 */
internal fun syntheticMkv(
    tracks: List<MkvTrack> = listOf(MkvTrack(1, 1, "V_VP9"), MkvTrack(2, 2, "A_AC3")),
    clusterTimesMs: List<Long> = (0 until 10).map { it * 1000L },
    cues: List<MkvCue> = clusterTimesMs.indices.map { MkvCue(clusterTimesMs[it], 1, it) },
    cuesBeforeClusters: Boolean = false,
    durationMs: Double = clusterTimesMs.last() + 1000.0,
): SyntheticMkv {
    val header = ebml(0x1A45DFA3, ebmlString(0x4282, "matroska"))
    val segmentStart = header.size + 12L
    val info = ebml(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000), ebmlFloat(0x4489, durationMs))
    val trackEntries = tracks.map { t ->
        val parts = mutableListOf(ebmlUint(0xD7, t.number.toLong()), ebmlUint(0x83, t.type.toLong()), ebmlString(0x86, t.codec))
        t.isDefault?.let { parts += ebmlUint(0x88, if (it) 1 else 0) }
        t.codecPrivate?.let { parts += ebmlBinary(0x63A2, it) }
        when (t.type) {
            1 -> parts += ebml(0xE0, ebmlUint(0xB0, 16), ebmlUint(0xBA, 16))
            2 -> parts += ebml(0xE1, ebmlUint(0x9F, 2), ebmlFloat(0xB5, 48_000.0))
        }
        ebml(0xAE, *parts.toTypedArray())
    }
    val tracksElement = ebml(0x1654AE6B, *trackEntries.toTypedArray())
    val clusters = clusterTimesMs.mapIndexed { i, timeMs ->
        val blocks = tracks.filter { it.type == 1 || it.type == 2 }.map { t ->
            val payload = ByteArray(if (t.type == 1) 300 else 100) { (i + t.number).toByte() }
            ebmlBinary(0xA3, byteArrayOf((0x80 or t.number).toByte(), 0, 0, 0x80.toByte()) + payload)
        }
        ebml(0x1F43B675, ebmlUint(0xE7, timeMs), *blocks.toTypedArray())
    }
    fun cuesElement(clusterPositions: List<Long>) = ebml(
        0x1C53BB6B,
        *cues.map { c ->
            ebml(0xBB, ebmlUint(0xB3, c.timeMs), ebml(0xB7, ebmlUint(0xF7, c.track.toLong()), ebmlUint(0xF1, clusterPositions[c.cluster]), ebmlUint(0xF0, 12)))
        }.toTypedArray(),
    )
    fun seekHead(cuesPosition: Long, tracksPosition: Long) = ebml(
        0x114D9B74,
        ebml(0x4DBB, ebmlBinary(0x53AB, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B)), ebmlUint(0x53AC, tracksPosition)),
        ebml(0x4DBB, ebmlBinary(0x53AB, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)), ebmlUint(0x53AC, cuesPosition)),
    )
    // Fixed-width fields: lay out once with zeros to learn the offsets, then again with them.
    val seekHeadSize = seekHead(0, 0).size
    val cuesSize = cuesElement(clusters.map { 0L }).size
    val tracksPosition = (seekHeadSize + info.size).toLong()
    var at = tracksPosition + tracksElement.size + if (cuesBeforeClusters) cuesSize else 0
    val clusterPositions = clusters.map { c -> at.also { at += c.size } }
    val cuesPosition = if (cuesBeforeClusters) tracksPosition + tracksElement.size else at
    val cuesBytes = cuesElement(clusterPositions)
    val body = listOf(seekHead(cuesPosition, tracksPosition), info, tracksElement) +
        if (cuesBeforeClusters) listOf(cuesBytes) + clusters else clusters + listOf(cuesBytes)
    val bytes = header + ebml(0x18538067, *body.toTypedArray())
    return SyntheticMkv(bytes, segmentStart, clusterPositions.map { it + segmentStart }, cuesPosition + segmentStart)
}

private fun ebmlId(id: Long): ByteArray {
    val n = (64 - java.lang.Long.numberOfLeadingZeros(id) + 7) / 8
    return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
}

/** An 8-byte EBML size: marker byte 0x01, then seven bytes of length. */
private fun ebmlSize(size: Long) = byteArrayOf(0x01) + ByteArray(7) { (size shr (8 * (6 - it))).toByte() }

internal fun ebmlBinary(id: Long, content: ByteArray) = ebmlId(id) + ebmlSize(content.size.toLong()) + content

internal fun ebml(id: Long, vararg children: ByteArray) = ebmlBinary(id, children.fold(ByteArray(0)) { acc, b -> acc + b })

private fun ebmlUint(id: Long, value: Long) = ebmlBinary(id, ByteArray(8) { (value shr (8 * (7 - it))).toByte() })

private fun ebmlFloat(id: Long, value: Double) = ebmlBinary(id, ByteArray(8) { (value.toRawBits() shr (8 * (7 - it))).toByte() })

private fun ebmlString(id: Long, value: String) = ebmlBinary(id, value.toByteArray(Charsets.US_ASCII))
