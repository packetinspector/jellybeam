package tv.jellybeam.visualizer.dsp

import kotlin.math.abs

/** Every frame's E5 outputs from a [Feed] run over [signal], for the onset and tempo tests. */
internal class FeatureLog(sr: Int, signal: FloatArray, fps: Int = Signals.FPS, repeatEvery: Int = 0) {
    val hop = sr / fps
    val kicks = ArrayList<Int>()
    val snares = ArrayList<Int>()
    val hats = ArrayList<Int>()
    val ticks = ArrayList<Int>()
    val phraseTicks = ArrayList<Int>()
    val frames: Int
    val locked: BooleanArray
    val bpm: FloatArray
    val count: IntArray
    val phrase: IntArray
    val silent: BooleanArray
    val phase: FloatArray
    val beat = ArrayList<Int>()

    init {
        val feed = Feed(sr, signal, fps)
        frames = feed.frames
        locked = BooleanArray(frames)
        bpm = FloatArray(frames)
        count = IntArray(frames)
        phrase = IntArray(frames)
        silent = BooleanArray(frames)
        phase = FloatArray(frames)
        for (f in 0 until frames) {
            feed.step(f)
            val o = feed.out
            if (o.kick) kicks.add(f)
            if (o.snare) snares.add(f)
            if (o.hat) hats.add(f)
            if (o.beat) beat.add(f)
            if (o.beatTick) ticks.add(f)
            if (o.phraseTick) phraseTicks.add(f)
            locked[f] = o.tempoLocked
            bpm[f] = o.tempoBpm
            count[f] = o.beatCount
            phrase[f] = o.phrase
            silent[f] = o.silent
            phase[f] = o.beatPhase
            Signals.assertFinite(o)
            org.junit.Assert.assertFalse("tick with count 0 at $f", o.beatTick && o.beatCount == 0)
            if (repeatEvery > 0 && f % repeatEvery == 0) {
                feed.repeat()
                org.junit.Assert.assertFalse("repeated frame $f fired", o.kick || o.snare || o.hat || o.beatTick)
            }
        }
    }

    fun firstLocked(from: Int = 0): Int = (from until frames).firstOrNull { locked[it] } ?: -1
}

/**
 * Recall and precision of [fired] frames against [truth] onset samples, and the share of [others] it fired on. A
 * frame matches an onset heard from one frame before it until 45 ms after it (the picture-late detectability
 * limit of ITU-R BT.1359), or one frame after it where frames are longer.
 */
internal class OnsetScore(
    fired: List<Int>, truth: IntArray, others: IntArray, private val hop: Int, warmFrames: Int = Signals.FPS,
) {
    private val late = maxOf(hop, (0.045 * hop * warmFrames).toInt())
    private val truthAfter = truth.filter { it >= warmFrames * hop }
    private val firedAfter = fired.filter { it >= warmFrames - 1 }
    private fun onTime(f: Int, t0: Int): Boolean = ((f + 1) * hop - 512 - t0) in -hop..late
    val found = truthAfter.count { t -> firedAfter.any { onTime(it, t) } }
    val good = firedAfter.count { f -> truthAfter.any { onTime(f, it) } }
    val recall = found.toDouble() / truthAfter.size
    val precision = if (firedAfter.isEmpty()) 1.0 else good.toDouble() / firedAfter.size
    private val otherAfter = others.filter { o -> o >= warmFrames * hop && truthAfter.none { abs(it - o) <= hop } }
    val crossHits = otherAfter.count { o -> firedAfter.any { onTime(it, o) } }
    val crossTalk = if (otherAfter.isEmpty()) 0.0 else crossHits.toDouble() / otherAfter.size

    override fun toString() = "r%.2f p%.2f x%.2f(%d/%d)".format(recall, precision, crossTalk, crossHits, otherAfter.size)
}
