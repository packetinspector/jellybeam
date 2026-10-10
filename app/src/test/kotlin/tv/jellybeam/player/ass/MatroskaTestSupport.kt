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
    // sun.misc.Unsafe isn't on the android.jar compile classpath; the test JVM has it.
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
        .invoke(unsafe, base, offset, "jvm-unit-test")
}

/** Media3's loader loop over [file]: reads to the end, reopening wherever a read asks to seek; returns the bytes read. */
internal fun extractAll(file: ByteArray, extractor: Extractor, output: ExtractorOutput): Long {
    extractor.init(output)
    var bytesRead = 0L
    fun inputAt(position: Long): DefaultExtractorInput {
        var next = position.toInt()
        val reader = DataReader { buffer, offset, length ->
            if (next >= file.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, file.size - next)
            file.copyInto(buffer, offset, next, next + n)
            next += n
            bytesRead += n
            n
        }
        return DefaultExtractorInput(reader, position, file.size.toLong())
    }
    var input = inputAt(0)
    repeat(10_000) {
        val holder = PositionHolder()
        when (extractor.read(input, holder)) {
            Extractor.RESULT_END_OF_INPUT -> return bytesRead
            Extractor.RESULT_SEEK -> input = inputAt(holder.position)
        }
    }
    error("extractor never reached the end")
}

/** A [RangeReader] over this file's bytes (fewer at its end, null past it); [onRead] sees each read. */
internal fun ByteArray.rangeReader(onRead: () -> Unit = {}) = RangeReader { offset, length ->
    onRead()
    if (offset !in indices) null else copyOfRange(offset.toInt(), minOf(size, offset.toInt() + length))
}
