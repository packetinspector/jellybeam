package tv.jellybeam.visualizer.color

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/30 §4: renders every accent × family palette into build/swatches.png for review by eye (rows are
 * accents, columns families; inside a swatch, palette position runs across and level runs up).
 */
class SwatchSheetTest {
    private val accents = listOf(
        0xFFA8CB6B to 0xFFC6DE9B, 0xFF7FCFAE to 0xFFAEE3CB, 0xFF86C3E3 to 0xFFB5DBEF, 0xFFA6B4EE to 0xFFC9D1F6,
        0xFFC6A8E6 to 0xFFDDCAF1, 0xFFEEA0B4 to 0xFFF5C5D1, 0xFF4E6A2C to 0xFFA8CB6B, 0xFF2C6B58 to 0xFF7FCFAE,
        0xFF2B5F82 to 0xFF86C3E3, 0xFF4C4F94 to 0xFFA6B4EE, 0xFF6E3F7C to 0xFFC6A8E6, 0xFF8C2F48 to 0xFFEEA0B4,
    )

    @Test
    fun writesTheSwatchSheet() {
        val sw = Palettes.POSITIONS * 2
        val sh = Palettes.LEVELS * 3
        val gap = 8
        val w = PaletteFamily.entries.size * (sw + gap) + gap
        val h = accents.size * (sh + gap) + gap
        val rgb = ByteArray(w * h * 3) { 0x30 }
        for ((row, ac) in accents.withIndex()) {
            for ((col, fam) in PaletteFamily.entries.withIndex()) {
                val lut = Palettes.build(fam, ac.first.toInt(), ac.second.toInt())
                val x0 = gap + col * (sw + gap)
                val y0 = gap + row * (sh + gap)
                for (y in 0 until sh) for (x in 0 until sw) {
                    val level = Palettes.LEVELS - 1 - y / 3
                    val i = (level * Palettes.POSITIONS + x / 2) * 3
                    val o = ((y0 + y) * w + x0 + x) * 3
                    for (c in 0..2) rgb[o + c] = (lut[i + c].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                }
            }
        }
        val out = File("build/swatches.png")
        out.parentFile.mkdirs()
        out.writeBytes(png(w, h, rgb))
        assertTrue(out.length() > 0)
    }

    /** A minimal RGB PNG with no text or metadata chunks. */
    private fun png(w: Int, h: Int, rgb: ByteArray): ByteArray {
        val raw = ByteArray(h * (w * 3 + 1))
        for (y in 0 until h) System.arraycopy(rgb, y * w * 3, raw, y * (w * 3 + 1) + 1, w * 3)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val t = type.toByteArray()
            out.write(t)
            out.write(data)
            val crc = CRC32()
            crc.update(t)
            crc.update(data)
            out.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0) }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return bytes.toByteArray()
    }
}
