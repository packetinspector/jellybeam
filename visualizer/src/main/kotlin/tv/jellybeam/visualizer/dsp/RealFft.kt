package tv.jellybeam.visualizer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** docs/30 §3: radix-2 complex FFT of n/2 plus a split, all tables preallocated so [transform] allocates nothing. */
internal class RealFft(val n: Int) {
    private val m = n / 2
    private val rev = IntArray(m)
    private val cosT = FloatArray(m / 2)
    private val sinT = FloatArray(m / 2)
    private val splitCos = FloatArray(m + 1)
    private val splitSin = FloatArray(m + 1)
    private val zr = FloatArray(m)
    private val zi = FloatArray(m)

    init {
        require(n >= 4 && n and (n - 1) == 0) { "n must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(m)
        for (i in 0 until m) rev[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
        for (k in 0 until m / 2) {
            cosT[k] = cos(2.0 * PI * k / m).toFloat()
            sinT[k] = (-sin(2.0 * PI * k / m)).toFloat()
        }
        for (k in 0..m) {
            splitCos[k] = cos(2.0 * PI * k / n).toFloat()
            splitSin[k] = (-sin(2.0 * PI * k / n)).toFloat()
        }
    }

    /** Bins 0..n/2 of the DFT of [x] into [re]/[im] (size n/2+1); [x] is not modified. */
    fun transform(x: FloatArray, re: FloatArray, im: FloatArray) {
        for (i in 0 until m) {
            val j = rev[i]
            zr[j] = x[2 * i]
            zi[j] = x[2 * i + 1]
        }
        var len = 2
        while (len <= m) {
            val half = len shr 1
            val step = m / len
            var i = 0
            while (i < m) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val a = i + j
                    val b = a + half
                    val tr = zr[b] * wr - zi[b] * wi
                    val ti = zr[b] * wi + zi[b] * wr
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        for (k in 0..m) {
            val a = if (k == m) 0 else k
            val b = if (k == 0 || k == m) 0 else m - k
            val ar = zr[a]
            val ai = zi[a]
            val br = zr[b]
            val bi = -zi[b]
            val er = 0.5f * (ar + br)
            val ei = 0.5f * (ai + bi)
            val oR = 0.5f * (ai - bi)
            val oI = -0.5f * (ar - br)
            re[k] = er + splitCos[k] * oR - splitSin[k] * oI
            im[k] = ei + splitCos[k] * oI + splitSin[k] * oR
        }
    }
}
