package tv.jellybeam.visualizer.lab

import tv.jellybeam.visualizer.FeatureSource
import tv.jellybeam.visualizer.Features

/**
 * docs/30 §9: a deterministic source. [play] serves a fixed frame, [playSequence] steps through a list;
 * either way audio time advances exactly 1/60 s per update and the source stops running when the
 * allowance is spent (a paused, settled frame). [allow] grants more updates. Thread-safe setters.
 */
class LabSource : FeatureSource {
    @Volatile
    private var sequence: List<Features> = listOf(Features())

    @Volatile
    private var index = 0

    @Volatile
    private var remaining = 0

    @Volatile
    private var forever = false

    @Volatile
    private var wake: Runnable? = null

    @Volatile
    var updates = 0
        private set

    @Volatile
    override var encoded: Boolean = false

    private var timeS = 0f

    override val running: Boolean get() = forever || remaining > 0

    /** Serves [features] for [frames] updates ([frames] < 0: until [stop]) and wakes the engine. */
    fun play(features: Features, frames: Int) = playSequence(listOf(features), frames)

    /** Serves [seq] in order (holding its last entry) for [frames] updates ([frames] < 0: until [stop]). */
    fun playSequence(seq: List<Features>, frames: Int) {
        sequence = seq
        index = 0
        forever = frames < 0
        remaining = maxOf(frames, 0)
        wake?.run()
    }

    /** Lets [n] more updates through, continuing the current sequence. */
    fun allow(n: Int) {
        remaining += n
        wake?.run()
    }

    fun stop() {
        forever = false
        remaining = 0
    }

    override fun open(wake: Runnable) {
        this.wake = wake
    }

    override fun update(frameNanos: Long, out: Features): Boolean {
        if (!forever && remaining <= 0) return false
        if (!forever) remaining--
        val seq = sequence
        out.copyFrom(seq[minOf(index, seq.size - 1)])
        index++
        timeS += 1f / 60f
        out.timeS = timeS
        updates++
        return true
    }

    override fun close() {
        wake = null
    }
}
