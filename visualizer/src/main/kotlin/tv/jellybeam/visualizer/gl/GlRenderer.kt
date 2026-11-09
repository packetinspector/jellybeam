package tv.jellybeam.visualizer.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.color.GainCurve
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.pattern.CompositeMode
import tv.jellybeam.visualizer.pattern.DrawSpec
import tv.jellybeam.visualizer.pattern.FrameParams
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternSpec
import tv.jellybeam.visualizer.pattern.PatternSpecs

/**
 * docs/30 §6 (E1, E2): GL objects and one frame. A 480×270 RG8 layer (intensity, palette position)
 * ping-pongs: the layer pass warps or paints it and injects shapes; the composite colours it through
 * the palette onto the screen; a native overlay draws on top. Every pass reads its own slot of one
 * uniform buffer, written once per frame. GL thread only, with a current context.
 */
internal class GlRenderer(private val assets: AssetManager) {
    /** One pattern drawing this frame with [weight] (1, or a crossfade share) and its [params]. */
    class Active {
        var pattern: Pattern = Pattern.BARS
        var weight = 0f

        /** The pattern's own params (kept per pattern by the engine, so travel survives a crossfade hand-off). */
        var params = FrameParams()
    }

    private val programs = HashMap<String, Int>()
    private var vao = 0
    private val layerTex = IntArray(2)
    private val layerFbo = IntArray(2)
    private val simTex = IntArray(2)
    private val simFbo = IntArray(2)
    private val statTex = IntArray(2)
    private var statFbo = 0
    private var histTex = 0
    private var waveTex = 0
    private var lutTex = 0
    private var ubo = 0
    private val injectProgram = HashMap<DrawSpec, Int>()
    private val overlayProgram = HashMap<DrawSpec, Int>()
    private var cur = 0
    private var simCur = 0
    private var head = 0
    private var validRows = 0
    private var width = 1
    private var height = 1
    private var slotStride = 256

    /** Bytes this renderer allocated on the GPU (E1: the visualizer stays under 1 MB). */
    var gpuBytes = 0L
        private set

    /** This frame's mean shown luminance at each of [GainCurve.GAINS], measured before the composite (docs/30 §4). */
    val shown = FloatArray(GainCurve.GAINS.size)

    /** How long the last frame's stats read waited on the GPU, nanoseconds. */
    var statsWaitNanos = 0L
        private set

    private val statLow: ByteBuffer = direct(STAT_BYTES)
    private val statHigh: ByteBuffer = direct(STAT_BYTES)
    private val rowBuf: FloatBuffer = direct(Features.BANDS * 4).asFloatBuffer()
    private val waveBuf: FloatBuffer = direct(WAVE_W * WAVE_H * 4).asFloatBuffer()
    private val uboBytes: ByteBuffer = direct(SLOTS * 256)
    private val uboFloats: FloatBuffer = uboBytes.asFloatBuffer()
    private val uboInts: IntBuffer = uboBytes.asIntBuffer()
    private var slotsUsed = 0

    /** Compiles every program and allocates every texture; returns compile/link logs that were non-empty. */
    fun create(): Map<String, String> {
        val logs = HashMap<String, String>()
        val common = asset("viz2/common.glsl")
        val geom = asset("viz2/geom.glsl")
        val fullscreen = "$HEADER$common\n${asset("viz2/fullscreen.vert.glsl")}"
        fun fragSrc(path: String) = "$HEADER$common\n${asset(path)}"
        program(KEY_COMPOSITE, fullscreen, fragSrc("viz2/composite.frag.glsl"), logs)
        program(KEY_LUM, fullscreen, fragSrc("viz2/lum.frag.glsl"), logs)
        program(KEY_WARP, "$HEADER$common\n${asset("viz2/warp.vert.glsl")}", fragSrc("viz2/warp.frag.glsl"), logs)
        val inject = fragSrc("viz2/inject.frag.glsl")
        val overlay = fragSrc("viz2/overlay.frag.glsl")
        for (p in Pattern.entries) {
            val s = PatternSpecs.spec(p)
            s.field?.let { program(it, fullscreen, fragSrc(it), logs) }
            s.sim?.let { program(it, fullscreen, fragSrc(it), logs) }
            for (d in s.inject) injectProgram[d] = program(injectKey(d), "$HEADER$common\n$geom\n${asset(d.vertexShader)}", inject, logs)
            for (d in s.overlay) overlayProgram[d] = program(overlayKey(d), "$HEADER$common\n$geom\n${asset(d.vertexShader)}", overlay, logs)
        }

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        GLES30.glGenTextures(2, layerTex, 0)
        GLES30.glGenFramebuffers(2, layerFbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layerTex[i])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RG8, PatternSpec.LAYER_W, PatternSpec.LAYER_H)
            params(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_CLAMP_TO_EDGE)
            gpuBytes += PatternSpec.LAYER_W.toLong() * PatternSpec.LAYER_H * 2
            attach(layerFbo[i], layerTex[i], 0, logs)
            clear(layerFbo[i], 0f, 0f, 0f, 0f)
        }

        GLES30.glGenTextures(2, simTex, 0)
        GLES30.glGenFramebuffers(2, simFbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, simTex[i])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, PatternSpec.SIM_W, PatternSpec.SIM_H)
            params(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_CLAMP_TO_EDGE)
            gpuBytes += PatternSpec.SIM_W.toLong() * PatternSpec.SIM_H * 4
            attach(simFbo[i], simTex[i], 0, logs)
        }
        resetSim()

        GLES30.glGenTextures(2, statTex, 0)
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        statFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statFbo)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, statTex[i])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, STAT_W, STAT_H)
            params(GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_CLAMP_TO_EDGE)
            gpuBytes += STAT_BYTES.toLong()
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_TEXTURE_2D, statTex[i], 0)
        }
        GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) logs["fbo:stats"] = "incomplete 0x${Integer.toHexString(status)}"

        val tex = IntArray(3)
        GLES30.glGenTextures(3, tex, 0)
        histTex = tex[0]
        waveTex = tex[1]
        lutTex = tex[2]
        texture(histTex, GLES30.GL_R16F, Features.BANDS, HISTORY_ROWS, GLES30.GL_RED, GLES30.GL_FLOAT, direct(Features.BANDS * HISTORY_ROWS * 4))
        params(GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_CLAMP_TO_EDGE)
        gpuBytes += Features.BANDS.toLong() * HISTORY_ROWS * 2
        texture(waveTex, GLES30.GL_R16F, WAVE_W, WAVE_H, GLES30.GL_RED, GLES30.GL_FLOAT, direct(WAVE_W * WAVE_H * 4))
        params(GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_CLAMP_TO_EDGE)
        gpuBytes += WAVE_W.toLong() * WAVE_H * 2
        texture(lutTex, GLES30.GL_RGBA8, Palettes.POSITIONS, LUT_ROWS, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, direct(Palettes.POSITIONS * LUT_ROWS * 4))
        params(GLES30.GL_LINEAR, GLES30.GL_REPEAT, GLES30.GL_CLAMP_TO_EDGE)
        gpuBytes += Palettes.POSITIONS.toLong() * LUT_ROWS * 4

        val align = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, align, 0)
        val a = align[0].coerceIn(1, 256)
        slotStride = (UBO_BYTES + a - 1) / a * a
        GLES30.glGenBuffers(1, ids, 0)
        ubo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, SLOTS * 256, null, GLES30.GL_DYNAMIC_DRAW)
        gpuBytes += SLOTS * 256L

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        head = 0
        validRows = 0
        return logs
    }

    fun resize(w: Int, h: Int) {
        width = maxOf(1, w)
        height = maxOf(1, h)
    }

    /** Uploads the five palette families (docs/30 §4), [Palettes.toRgba8] layout. */
    fun setPalettes(rgba: ByteArray) {
        val b = direct(rgba.size)
        b.put(rgba).flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Palettes.POSITIONS, LUT_ROWS, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
    }

    /** Appends [f]'s bands as the newest history row (only when audio advanced). */
    fun pushHistory(f: Features) {
        head = (head + 1) % HISTORY_ROWS
        validRows = minOf(validRows + 1, HISTORY_ROWS)
        rowBuf.clear()
        rowBuf.put(f.bands).flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, histTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, head, Features.BANDS, 1, GLES30.GL_RED, GLES30.GL_FLOAT, rowBuf)
    }

    /** Clears the feedback layer and the ripple grid (a fresh start, e.g. a manual switch). */
    fun resetLayer() {
        for (fbo in layerFbo) clear(fbo, 0f, 0f, 0f, 0f)
        resetSim()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Draws one frame: [a] at its weight and, during a crossfade, [b] (weight > 0) on the same layer.
     * [cycle] is the palette offset; [policy] picks the composite gain from [shown] once it is measured
     * (docs/30 §4). Returns that gain.
     */
    fun draw(f: Features, a: Active, b: Active?, cycle: Float, kickPulse: Float, dtS: Float, policy: GainPolicy): Float {
        val one = 1f
        // A crossfade's first frame passes b at weight 0: it must not draw (or warp) yet.
        val bw = b?.takeIf { it.weight > 0f }
        val sa = PatternSpecs.spec(a.pattern)
        val sb = bw?.let { PatternSpecs.spec(it.pattern) }
        uploadWave(f)
        slotsUsed = 0
        // Orphan the uniform buffer once a frame, so this frame's slot writes never wait on the last frame's reads.
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, SLOTS * 256, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindVertexArray(vao)
        bind(0, histTex)
        bind(1, waveTex)
        bind(2, lutTex)
        val next = 1 - cur

        for (act in actives(a, b)) {
            if (act == null) continue
            val s = PatternSpecs.spec(act.pattern)
            val sim = s.sim ?: continue
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, simFbo[1 - simCur])
            GLES30.glViewport(0, 0, PatternSpec.SIM_W, PatternSpec.SIM_H)
            GLES30.glDisable(GLES30.GL_BLEND)
            bind(4, simTex[simCur])
            useSlot(fill(f, act, s, PatternSpec.SIM_W, PatternSpec.SIM_H, act.weight, 0, cycle, one, kickPulse, dtS))
            fullscreen(sim)
            simCur = 1 - simCur
        }
        bind(4, simTex[simCur])

        // MAX blending: each shape keeps the brighter of itself and what is already in the layer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, layerFbo[next])
        GLES30.glViewport(0, 0, PatternSpec.LAYER_W, PatternSpec.LAYER_H)
        bind(3, layerTex[cur])
        val warper = when {
            sa.warp && sb?.warp == true -> null
            sa.warp -> a
            sb?.warp == true -> bw
            else -> null
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        if (sa.warp || sb?.warp == true) {
            val slot = if (warper != null) {
                fill(f, warper, PatternSpecs.spec(warper.pattern), PatternSpec.LAYER_W, PatternSpec.LAYER_H, 1f, 0, cycle, one, kickPulse, dtS)
            } else {
                blendWarp(a, bw!!)
                fill(f, blended, sa, PatternSpec.LAYER_W, PatternSpec.LAYER_H, 1f, 0, cycle, one, kickPulse, dtS)
            }
            useSlot(slot)
            GLES30.glUseProgram(programs.getValue(KEY_WARP))
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, WARP_CELLS)
        } else {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        for (act in actives(a, b)) {
            if (act == null) continue
            val s = PatternSpecs.spec(act.pattern)
            val field = s.field ?: continue
            useSlot(fill(f, act, s, PatternSpec.LAYER_W, PatternSpec.LAYER_H, act.weight, 0, cycle, one, kickPulse, dtS))
            fullscreen(field)
        }
        for (act in actives(a, b)) {
            if (act == null) continue
            val s = PatternSpecs.spec(act.pattern)
            for (i in s.inject.indices) {
                val d = s.inject[i]
                useSlot(fill(f, act, s, PatternSpec.LAYER_W, PatternSpec.LAYER_H, act.weight, i, cycle, one, kickPulse, dtS))
                GLES30.glUseProgram(injectProgram.getValue(d))
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, d.vertsPerInstance, d.instances)
            }
        }

        // The flash limiter needs this frame's shown luminance before the composite, so the 32×18 stats are
        // read synchronously: a late reading lets a fast full-field swing through (docs/30 §4).
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statFbo)
        GLES30.glViewport(0, 0, STAT_W, STAT_H)
        bind(3, layerTex[next])
        val main = if (bw != null && bw.weight > a.weight) bw else a
        useSlot(fill(f, main, PatternSpecs.spec(main.pattern), STAT_W, STAT_H, 1f, 0, cycle, one, kickPulse, dtS, sa.family.ordinal, (sb ?: sa).family.ordinal, bw?.weight ?: 0f))
        fullscreen(KEY_LUM)
        val t0 = System.nanoTime()
        readStats()
        statsWaitNanos = System.nanoTime() - t0
        val gain = policy.gain(shown)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        bind(3, layerTex[next])
        // The composite mode follows the heavier pattern; the two families mix by the incoming weight.
        useSlot(fill(f, main, PatternSpecs.spec(main.pattern), width, height, 1f, 0, cycle, gain, kickPulse, dtS, sa.family.ordinal, (sb ?: sa).family.ordinal, bw?.weight ?: 0f))
        fullscreen(KEY_COMPOSITE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (act in actives(a, b)) {
            if (act == null) continue
            val s = PatternSpecs.spec(act.pattern)
            for (i in s.overlay.indices) {
                val d = s.overlay[i]
                useSlot(fill(f, act, s, width, height, act.weight, i, cycle, gain, kickPulse, dtS))
                GLES30.glUseProgram(overlayProgram.getValue(d))
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, d.vertsPerInstance, d.instances)
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        cur = next
        return gain
    }

    /** docs/30 §4: chooses the composite gain from the frame's shown-luminance curve. */
    fun interface GainPolicy {
        fun gain(shown: FloatArray): Float
    }

    /** Test hook (docs/30 §9): the current back buffer as RGBA rows, bottom row first. */
    fun readPixels(): ByteBuffer {
        val out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        return out
    }

    fun release() {
        for (p in programs.values) GLES30.glDeleteProgram(p)
        programs.clear()
        GLES30.glDeleteFramebuffers(2, layerFbo, 0)
        GLES30.glDeleteFramebuffers(2, simFbo, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(statFbo), 0)
        GLES30.glDeleteTextures(2, statTex, 0)
        GLES30.glDeleteTextures(2, layerTex, 0)
        GLES30.glDeleteTextures(2, simTex, 0)
        GLES30.glDeleteTextures(3, intArrayOf(histTex, waveTex, lutTex), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(ubo), 0)
        injectProgram.clear()
        overlayProgram.clear()
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        gpuBytes = 0
    }

    // -- frame helpers ---------------------------------------------------------------------------

    private val pair = arrayOfNulls<Active>(2)
    private val blended = Active()

    private fun actives(a: Active, b: Active?): Array<Active?> {
        pair[0] = a
        pair[1] = b?.takeIf { it.weight > 0f }
        return pair
    }

    /** Both patterns warp during a crossfade: their warp parameters mix by weight. */
    private fun blendWarp(a: Active, b: Active) {
        val t = b.weight / (a.weight + b.weight).coerceAtLeast(1e-6f)
        for (i in 0 until 12) blended.params.warp[i] = a.params.warp[i] * (1 - t) + b.params.warp[i] * t
        blended.pattern = a.pattern
    }

    private fun uploadWave(f: Features) {
        waveBuf.clear()
        waveBuf.put(f.wave).put(f.waveL).put(f.waveR).put(f.peaks)
        for (i in Features.BANDS until WAVE_W) waveBuf.put(0f)
        waveBuf.flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, WAVE_W, WAVE_H, GLES30.GL_RED, GLES30.GL_FLOAT, waveBuf)
    }

    /** Writes one Frame block into the next free slot and returns its index. */
    private fun fill(
        f: Features, act: Active, s: PatternSpec, w: Int, h: Int, weight: Float, drawIndex: Int,
        cycle: Float, gain: Float, kickPulse: Float, dtS: Float, famA: Int = s.family.ordinal, famB: Int = famA, mixB: Float = 0f,
    ): Int {
        val slot = slotsUsed++
        val base = slot * slotStride / 4
        uboFloats.position(base)
        uboFloats.put(w.toFloat()).put(h.toFloat()).put(1f / w).put(1f / h)
        uboFloats.put(f.level).put(f.bass).put(f.mid).put(f.treble)
        uboFloats.put(f.timeS).put(kickPulse).put(act.params.travel).put(dtS)
        uboFloats.put(f.beatAges)
        uboFloats.put(act.params.param)
        uboFloats.put(act.params.warp)
        uboFloats.put(kickPulse).put(f.snarePulse).put(f.hatPulse).put(f.flux)
        uboFloats.put(f.beatPhase).put(f.tempoBpm / 200f).put(f.loudShort).put(f.loudLong)
        uboFloats.put(cycle).put(gain).put(famA.toFloat()).put(weight)
        val mode = if (s.composite == CompositeMode.KALEIDO) 1f else 0f
        uboFloats.put(mode).put(act.params.compParam).put(famB.toFloat()).put(mixB)
        uboInts.position(base + INFO_OFFSET / 4)
        uboInts.put(head).put(validRows).put(drawIndex).put(f.phrase)
        // Uploaded per slot as it is filled: each pass reads its own range, so no pass sees a later write.
        uboBytes.position(slot * slotStride)
        uboBytes.limit(slot * slotStride + UBO_BYTES)
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo)
        GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, slot * slotStride, UBO_BYTES, uboBytes)
        uboBytes.clear()
        return slot
    }

    private fun useSlot(slot: Int) {
        GLES30.glBindBufferRange(GLES30.GL_UNIFORM_BUFFER, 0, ubo, slot * slotStride, UBO_BYTES)
    }

    private fun fullscreen(key: String) {
        GLES30.glUseProgram(programs.getValue(key))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Reads both stats targets and averages their cells; each cell holds square roots, so squares undo that. */
    private fun readStats() {
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        statLow.clear()
        GLES30.glReadPixels(0, 0, STAT_W, STAT_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, statLow)
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT1)
        statHigh.clear()
        GLES30.glReadPixels(0, 0, STAT_W, STAT_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, statHigh)
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        for (k in 0 until 4) {
            var low = 0f
            var high = 0f
            for (i in 0 until STAT_W * STAT_H) {
                low += SQUARES[statLow.get(i * 4 + k).toInt() and 0xFF]
                high += SQUARES[statHigh.get(i * 4 + k).toInt() and 0xFF]
            }
            shown[k] = low / (STAT_W * STAT_H)
            shown[k + 4] = high / (STAT_W * STAT_H)
        }
    }

    // -- setup helpers ---------------------------------------------------------------------------

    private fun resetSim() {
        // The grid stores two 16-bit values with 32768 as zero: (0.5, 0, 0.5, 0) is a flat surface at rest.
        for (fbo in simFbo) clear(fbo, 128f / 255f, 0f, 128f / 255f, 0f)
    }

    private fun clear(fbo: Int, r: Float, g: Float, b: Float, a: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun attach(fbo: Int, tex: Int, level: Int, logs: MutableMap<String, String>) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, level)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) logs["fbo:$fbo:$level"] = "incomplete 0x${Integer.toHexString(status)}"
    }

    private fun bind(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun params(filter: Int, wrapS: Int, wrapT: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)
    }

    private fun texture(id: Int, internal: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data)
    }

    private fun program(key: String, vert: String, frag: String, logs: MutableMap<String, String>): Int {
        programs[key]?.let { return it }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vert, "$key:vert", logs)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag, "$key:frag", logs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        val log = GLES30.glGetProgramInfoLog(prog).orEmpty()
        if (status[0] == 0 || log.isNotBlank()) logs["link:$key"] = log.ifBlank { "link failed" }
        // A program that didn't link would draw nothing forever; the engine's init catch shows the bare surface instead.
        check(status[0] != 0) { "link failed: $key" }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        GLES30.glUseProgram(prog)
        val block = GLES30.glGetUniformBlockIndex(prog, "Frame")
        if (block != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(prog, block, 0)
        uniform1i(prog, "u_hist", 0)
        uniform1i(prog, "u_wave", 1)
        uniform1i(prog, "u_lut", 2)
        uniform1i(prog, "u_layer", 3)
        uniform1i(prog, "u_sim", 4)
        programs[key] = prog
        return prog
    }

    private fun compile(type: Int, src: String, label: String, logs: MutableMap<String, String>): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        val log = GLES30.glGetShaderInfoLog(s).orEmpty()
        if (status[0] == 0 || log.isNotBlank()) logs[label] = log.ifBlank { "compile failed" }
        check(status[0] != 0) { "compile failed: $label" }
        return s
    }

    private fun uniform1i(prog: Int, name: String, v: Int) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform1i(loc, v)
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        const val HISTORY_ROWS = 512
        const val WAVE_W = 512
        const val WAVE_H = 4
        private const val HEADER = "#version 300 es\n"
        private const val KEY_COMPOSITE = "viz2/composite.frag.glsl"
        private const val KEY_WARP = "viz2/warp.frag.glsl"
        private const val KEY_LUM = "viz2/lum.frag.glsl"
        // 480×270 layer texels in 15×15 cells.
        private const val STAT_W = 32
        private const val STAT_H = 18
        private const val STAT_BYTES = STAT_W * STAT_H * 4
        private const val WARP_CELLS = 32 * 24
        private const val LUT_ROWS = Palettes.LEVELS * 5

        /** std140 Frame block: 14 vec4 + one ivec4 (see common.glsl). */
        const val UBO_BYTES = 15 * 16
        private const val INFO_OFFSET = 14 * 16

        /** Enough slots for a crossfade of two patterns at the E2 draw limit, plus the composite. */
        private const val SLOTS = 24

        private fun injectKey(d: DrawSpec) = "inject:${d.vertexShader}"
        private fun overlayKey(d: DrawSpec) = "overlay:${d.vertexShader}"

        private val SQUARES = FloatArray(256) { (it / 255f) * (it / 255f) }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
