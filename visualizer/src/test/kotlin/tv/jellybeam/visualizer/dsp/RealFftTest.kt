package tv.jellybeam.visualizer.dsp

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFftTest {
    @Test
    fun `fft matches a naive dft on twenty seeded frames`() {
        val n = 1024
        val fft = RealFft(n)
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        var worst = 0.0
        for (seed in 1..20) {
            val rnd = Random(seed.toLong())
            val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            fft.transform(x, re, im)
            for (k in 0..n / 2) {
                var sr = 0.0
                var si = 0.0
                for (t in 0 until n) {
                    val a = -2.0 * PI * k * t / n
                    sr += x[t] * cos(a)
                    si += x[t] * sin(a)
                }
                worst = max(worst, max(abs(re[k] - sr), abs(im[k] - si)) / n)
            }
        }
        assertTrue("max normalised error $worst", worst <= 1e-4)
    }
}
