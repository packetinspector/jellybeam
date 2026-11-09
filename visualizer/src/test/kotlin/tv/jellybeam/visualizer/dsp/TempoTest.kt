package tv.jellybeam.visualizer.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/30 §3 (E5) U3 and U4: tempo, beat phase, phrases and silence on synthetic drums. */
class TempoTest {
    private val sr = 48000
    private val fps = Signals.FPS

    private fun moderate(bpm: Double, seconds: Double, seed: Long = 11L) =
        Signals.drums(sr, seconds, bpm, 0.05, 0.6, 0.3, 0.15, seed)

    private fun loud(bpm: Double, seconds: Double) = Signals.drums(sr, seconds, bpm, 0.2, 0.9, 0.5, 0.3)

    /** Drums parts back to back, with [gapS] of silence after each but the last; beat samples are offset to match. */
    private class Joined(val signal: FloatArray, val beats: IntArray, val starts: IntArray)

    private fun join(parts: List<Signals.Drums>, gapS: Double = 0.0): Joined {
        val gap = (gapS * 48000).toInt()
        val total = parts.sumOf { it.signal.size } + gap * (parts.size - 1)
        val sig = FloatArray(total)
        val beats = ArrayList<Int>()
        val starts = IntArray(parts.size)
        var off = 0
        for ((i, p) in parts.withIndex()) {
            starts[i] = off
            p.signal.copyInto(sig, off)
            for (b in p.beats) beats.add(b + off)
            off += p.signal.size + gap
        }
        return Joined(sig, beats.toIntArray(), starts)
    }

    @Test
    fun `tempo locks within 2 bpm inside 8 s at 90 120 and 174 and never at half or double`() {
        for (fps in intArrayOf(60, 30)) for (bpm in doubleArrayOf(90.0, 120.0, 174.0)) for ((name, d) in listOf("moderate" to moderate(bpm, 20.0), "loud" to loud(bpm, 20.0))) {
            val log = FeatureLog(sr, d.signal, fps)
            val lock = log.firstLocked()
            println("${fps}fps $name $bpm lock at %.2f s bpm %.2f end %.2f".format(lock.toDouble() / fps, log.bpm[lock.coerceAtLeast(0)], log.bpm[log.frames - 1]))
            assertTrue("$name $bpm lock frame $lock", lock in 0..8 * fps)
            for (f in lock until log.frames) {
                assertTrue("$name $bpm unlocked at $f", log.locked[f])
                assertEquals("$name $bpm at frame $f", bpm, log.bpm[f].toDouble(), 2.0)
            }
        }
    }

    /** Kicks alone on 1 and 3 repeat at half the tempo, and a weak kick under a strong snare accents every other beat. */
    @Test
    fun `uneven accents still lock the beat tempo and not half of it`() {
        val cases = listOf(
            120.0 to Signals.drums(sr, 20.0, 120.0, 0.05, 0.6, 0.0, 0.15),
            174.0 to Signals.drums(sr, 20.0, 174.0, 0.05, 0.15, 0.3, 0.15),
        )
        for ((bpm, d) in cases) {
            val log = FeatureLog(sr, d.signal)
            val lock = log.firstLocked()
            println("uneven $bpm lock at %.2f s bpm %.2f".format(lock / 60.0, log.bpm[lock.coerceAtLeast(0)]))
            assertTrue("$bpm lock frame $lock", lock in 0..8 * fps)
            for (f in lock until log.frames) assertEquals("$bpm at frame $f", bpm, log.bpm[f].toDouble(), 2.0)
        }
    }

    @Test
    fun `a change from 120 to 140 bpm relocks within 8 s`() {
        val j = join(listOf(moderate(120.0, 20.0), moderate(140.0, 20.0, seed = 12L)))
        val log = FeatureLog(sr, j.signal)
        val change = j.starts[1] / log.hop
        val relock = (change until log.frames).firstOrNull { f ->
            (f until log.frames).all { log.locked[it] && abs(log.bpm[it] - 140f) <= 2f }
        } ?: -1
        println("120 to 140 relock after %.2f s, count at change ${log.count[change]} at end ${log.count[log.frames - 1]}".format((relock - change) / 60.0))
        assertTrue("relock frame $relock change $change", relock >= 0 && relock - change <= 8 * fps)
        // A relock is a tempo change, not a new song: the beat count and phrases run on.
        for (f in change + 1 until log.frames) assertTrue("count fell at $f", log.count[f] >= log.count[f - 1])
    }

    @Test
    fun `once locked every tick lands within one frame of a beat and every beat gets one`() {
        for (fps in intArrayOf(60, 30)) for (bpm in doubleArrayOf(90.0, 120.0, 174.0)) for ((n, d) in listOf("moderate" to moderate(bpm, 20.0), "loud" to loud(bpm, 20.0))) {
            val name = "${fps}fps $n"
            val log = FeatureLog(sr, d.signal, fps)
            val lock = log.firstLocked()
            assertTrue(lock >= 0)
            val beats = d.beats.map { Signals.frameOf(it, log.hop) }
            val lastBeat = beats.last()
            val ticks = log.ticks.filter { it > lock && it <= lastBeat + 1 }
            val errors = ticks.map { t -> beats.minOf { abs(it - t) } }
            val signed = ticks.map { t -> beats.minByOrNull { abs(it - t) }!!.let { t - it } }
            println("$name $bpm ticks ${ticks.size} max phase error ${errors.maxOrNull()} frames, mean signed %.2f".format(signed.average()))
            for ((t, e) in ticks.zip(errors)) assertTrue("$name $bpm tick $t is $e frames from a beat", e <= 1)
            val periodFrames = fps * 60.0 / bpm
            var worst = 0.0
            for (b in beats.filter { it > lock + 1 && it < log.frames }) {
                assertTrue("$name $bpm beat $b has no tick", ticks.any { abs(it - b) <= 1 })
                val p = log.phase[b].toDouble()
                val error = minOf(p, 1.0 - p) * periodFrames
                if (b > lock + periodFrames) worst = maxOf(worst, error)
                // One frame, from the first beat the PLL has corrected.
                assertTrue("$name $bpm phase $p at beat $b", b <= lock + periodFrames || error <= 1.0)
            }
            println("$name $bpm worst beat phase error %.2f frames".format(worst))
        }
    }

    @Test
    fun `the phrase counter ticks every 16 beats`() {
        val log = FeatureLog(sr, moderate(120.0, 60.0).signal)
        val lock = log.firstLocked()
        assertTrue(lock >= 0)
        var expected = 0
        for (f in lock until log.frames) {
            if (f in log.ticks) expected++
            assertEquals("count at $f", expected, log.count[f])
            assertEquals("phrase at $f", expected / 16, log.phrase[f])
            assertEquals("phrase tick at $f", f in log.ticks && expected % 16 == 0 && expected > 0, f in log.phraseTicks)
        }
        assertTrue("phrase ticks ${log.phraseTicks.size} of ${log.ticks.size} ticks", log.phraseTicks.size >= log.ticks.size / 16)
    }

    @Test
    fun `silence stops onsets and ticks then unlocks after 4 s and re-primes on return`() {
        val gapS = 8.0
        val j = join(listOf(moderate(120.0, 15.0), moderate(120.0, 15.0, seed = 12L)), gapS)
        val log = FeatureLog(sr, j.signal)
        val quiet = (15 * fps) until (15 * fps + (gapS * fps).toInt())
        assertTrue("locked before the gap", log.locked[quiet.first - 1])
        val inGap = quiet.drop(2)
        for (f in inGap) {
            assertFalse("kick at $f", f in log.kicks)
            assertFalse("snare at $f", f in log.snares)
            assertFalse("hat at $f", f in log.hats)
            if (log.silent[f]) assertFalse("tick at $f", f in log.ticks)
        }
        for (f in quiet.first + fps until quiet.last + 1) {
            assertTrue("silent at $f", log.silent[f])
            assertEquals("count at $f", 0, log.count[f])
        }
        assertTrue("still locked 3.5 s in", log.locked[quiet.first + (3.5 * fps).toInt()])
        assertFalse("unlocked 4.5 s in", log.locked[quiet.first + (4.5 * fps).toInt()])
        assertEquals(0f, log.bpm[quiet.first + (4.5 * fps).toInt()], 0f)
        // Re-primed: kicks after the gap are onsets again (the first lands with the noise bed's own start), and the
        // tempo locks again.
        val back = j.starts[1] / log.hop
        val secondKick = Signals.frameOf(j.starts[1] + 1300 + 48000, log.hop)
        assertTrue("second kick after the gap at $secondKick: ${log.kicks.filter { it > back }.take(3)}", log.kicks.any { it - secondKick in -1..2 })
        val relock = log.firstLocked(back)
        assertTrue("relock ${relock - back} frames after return", relock >= 0 && relock - back <= 8 * fps)
        assertEquals(120.0, log.bpm[log.frames - 1].toDouble(), 2.0)
    }
}
