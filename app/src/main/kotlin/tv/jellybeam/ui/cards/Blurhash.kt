package tv.jellybeam.ui.cards

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

/**
 * Dependency-free port of the BlurHash decode algorithm (https://blurha.sh) for docs/07 §2's
 * placeholder chain. No `android.*` import, so decode is unit-testable on the JVM; `CardArt.kt`
 * turns [decode] into a `Bitmap` where `android.graphics` is available.
 */
object Blurhash {

    /** The format's fixed 83-character alphabet (10 digits + 26 upper + 26 lower + 21 symbols). */
    private const val BASE83_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    /**
     * Decodes [hash] into a [width]x[height] ARGB_8888 pixel array (row-major, matching
     * `Bitmap.createBitmap`'s `IntArray` overload). `null` on anything malformed (fails open to
     * the flat pulsing tile). [punch] exaggerates (>1) or flattens (<1) AC contrast; `1f` = native.
     */
    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (width <= 0 || height <= 0 || hash.length < 6) return null

        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val numX = (sizeFlag % 9) + 1
        val numY = (sizeFlag / 9) + 1
        val componentCount = numX * numY
        if (hash.length != 4 + 2 * componentCount) return null

        val quantisedMaxValue = decode83(hash, 1, 2) ?: return null
        val maxAcValue = (quantisedMaxValue + 1) / 166.0 * punch

        val colors = arrayOfNulls<DoubleArray>(componentCount)
        colors[0] = decode83(hash, 2, 6)?.let(::decodeDc) ?: return null
        for (i in 1 until componentCount) {
            val start = 6 + (i - 1) * 2
            val value = decode83(hash, start, start + 2) ?: return null
            colors[i] = decodeAc(value, maxAcValue)
        }

        // Standard blurhash decode optimisation: each axis's cosine basis depends only on that
        // axis's own coordinate, so precomputing it here turns the pixel loop's cos() calls (up
        // to width*height*componentCount of them) into just width*numX + height*numY.
        val cosX = Array(width) { x -> DoubleArray(numX) { i -> cos(PI * x * i / width) } }
        val cosY = Array(height) { y -> DoubleArray(numY) { j -> cos(PI * y * j / height) } }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val cosYRow = cosY[y]
            for (x in 0 until width) {
                val cosXRow = cosX[x]
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until numY) {
                    val basisY = cosYRow[j]
                    for (i in 0 until numX) {
                        val basis = cosXRow[i] * basisY
                        val color = colors[j * numX + i] ?: continue
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[y * width + x] =
                    (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return pixels
    }

    /** Decodes base83 digits `hash[from, to)` into an integer, or `null` on an unknown char. */
    private fun decode83(hash: String, from: Int, to: Int): Int? {
        if (to > hash.length) return null
        var value = 0
        for (i in from until to) {
            val digit = BASE83_ALPHABET.indexOf(hash[i])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    /** The DC (average color) component: a packed 24-bit sRGB value, converted to linear light. */
    private fun decodeDc(value: Int): DoubleArray {
        val r = (value shr 16) and 0xFF
        val g = (value shr 8) and 0xFF
        val b = value and 0xFF
        return doubleArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
    }

    /** One AC (cosine-basis) component: three base-19 quantised, sign-preserving-squared values. */
    private fun decodeAc(value: Int, maxAcValue: Double): DoubleArray {
        val quantR = value / (19 * 19)
        val quantG = (value / 19) % 19
        val quantB = value % 19
        return doubleArrayOf(
            signPow((quantR - 9) / 9.0, 2.0) * maxAcValue,
            signPow((quantG - 9) / 9.0, 2.0) * maxAcValue,
            signPow((quantB - 9) / 9.0, 2.0) * maxAcValue,
        )
    }

    private fun signPow(value: Double, exponent: Double): Double = sign(value) * abs(value).pow(exponent)

    private fun srgbToLinear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** Linear light back to 8-bit sRGB, clamped (AC reconstruction can overshoot 0..1). */
    private fun linearToSrgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return round(srgb * 255.0).toInt().coerceIn(0, 255)
    }
}

/**
 * Thread-safe LRU cache, plain Kotlin (no `android.util.LruCache`) so it's unit-testable on the
 * JVM. `CardArt.kt` keys one by blurhash string, valued by the decoded `Bitmap`. `get`/`put`
 * synchronize population on [kotlinx.coroutines.Dispatchers.Default] against composition lookup.
 */
class BoundedLruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }
}
