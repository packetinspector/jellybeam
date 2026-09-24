package tv.jellybeam.ui.cards

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurhashTest {

    /** blurha.sh's own homepage demo hash: sizeFlag 'L' -> numX=4, numY=3 (12 components, 28 chars). */
    private val knownVector = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    /** CRC32 of the pixel bytes, row-major ARGB -- a cheap way to lock a 576-int array without
     * inlining it. Captured from the pre-precompute decode() before the cosine-table change, so
     * this only passes if the optimisation is bit-identical to the original per-pixel cos() calls.
     */
    private fun crc32Of(pixels: IntArray): Long {
        val crc = CRC32()
        for (pixel in pixels) {
            crc.update((pixel ushr 24) and 0xFF)
            crc.update((pixel ushr 16) and 0xFF)
            crc.update((pixel ushr 8) and 0xFF)
            crc.update(pixel and 0xFF)
        }
        return crc.value
    }

    @Test
    fun `decode's cosine-table precompute matches the original per-pixel calculation`() {
        val pixels = Blurhash.decode(knownVector, 32, 18)!!

        assertEquals(4284862110L, crc32Of(pixels))
        assertEquals(-7887695, pixels[0])
        assertEquals(-8089965, pixels[pixels.size - 1])
        assertEquals(-6390420, pixels[9 * 32 + 16])
        assertEquals(-7953487, pixels[1 * 32 + 0])
        assertEquals(-7756107, pixels[0 * 32 + 31])
        assertEquals(-8022881, pixels[7 * 32 + 5])
    }

    @Test
    fun `a single-component hash's precompute also matches the original`() {
        val pixels = Blurhash.decode("00Trpb", 4, 4)!!

        assertEquals(1705723632L, crc32Of(pixels))
        assertEquals(-16603222, pixels[0])
        assertEquals(-16603222, pixels[pixels.size - 1])
    }

    @Test
    fun `decode produces a bitmap-sized pixel array without throwing`() {
        val pixels = Blurhash.decode(knownVector, 32, 18)

        assertNotNull(pixels)
        assertEquals(32 * 18, pixels!!.size)
    }

    @Test
    fun `decode produces fully opaque pixels`() {
        val pixels = Blurhash.decode(knownVector, 32, 18)!!

        for (pixel in pixels) {
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
        }
    }

    @Test
    fun `decode produces channel values within the 0 to 255 sanity bound`() {
        val pixels = Blurhash.decode(knownVector, 32, 18)!!

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue(r in 0..255)
            assertTrue(g in 0..255)
            assertTrue(b in 0..255)
        }
    }

    @Test
    fun `decode works for a single-component 1x1 hash`() {
        // sizeFlag '0' -> numX=1, numY=1 -> 1 component -> length 6.
        val pixels = Blurhash.decode("00Trpb", 4, 4)

        assertNotNull(pixels)
        assertEquals(16, pixels!!.size)
    }

    @Test
    fun `decode is null for a hash shorter than the minimum`() {
        assertNull(Blurhash.decode("Lx", 32, 18))
    }

    @Test
    fun `decode is null when the length doesn't match the declared component count`() {
        // sizeFlag '0' declares a single 6-char hash; this one is padded past that.
        assertNull(Blurhash.decode("00TrpbXX", 32, 18))
    }

    @Test
    fun `decode is null for an invalid base83 character`() {
        val corrupted = knownVector.replaceFirst('E', ' ')
        assertNull(Blurhash.decode(corrupted, 32, 18))
    }

    @Test
    fun `decode is null for a non-positive size`() {
        assertNull(Blurhash.decode(knownVector, 0, 18))
        assertNull(Blurhash.decode(knownVector, 32, 0))
    }
}
