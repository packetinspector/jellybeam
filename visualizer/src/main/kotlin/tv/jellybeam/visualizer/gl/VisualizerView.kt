package tv.jellybeam.visualizer.gl

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import tv.jellybeam.visualizer.FeatureSource
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternCycle

/**
 * docs/30 §6 (D6): the visualizer surface at window size. The engine lives from surfaceCreated to
 * surfaceDestroyed (Back, Home, screen-off all destroy it), so nothing runs while it isn't shown.
 */
class VisualizerView(
    context: Context,
    private val sourceFactory: () -> FeatureSource,
    initialPattern: Pattern,
    initialPalettes: List<FloatArray>,
    private val onEncodedChanged: (Boolean) -> Unit = {},
    /** The pattern changed: a Left/Right [step] ([manual]) or an automatic change (docs/30 §5). */
    private val onPatternChanged: (pattern: Pattern, manual: Boolean) -> Unit = { _, _ -> },
) : SurfaceView(context), SurfaceHolder.Callback {
    private var engine: VisualizerEngine? = null
    /** The engine's pattern as last reported, so a recreated surface starts where the picture was. */
    private var pattern = initialPattern
    private var palettes = initialPalettes
    private var autoChange = true

    init {
        holder.addCallback(this)
        isFocusable = false
    }

    /** Left/Right: the engine steps from what it shows; without an engine yet the step applies here. */
    fun step(delta: Int) {
        val e = engine
        if (e != null) {
            e.step(delta)
            return
        }
        repeat(kotlin.math.abs(delta)) { pattern = if (delta > 0) PatternCycle.next(pattern) else PatternCycle.previous(pattern) }
        onPatternChanged(pattern, true)
    }

    fun setPalettes(p: List<FloatArray>) {
        if (p === palettes) return
        palettes = p
        engine?.setPalettes(p)
    }

    /** E6: the "Change patterns automatically" setting. */
    fun setAutoChange(on: Boolean) {
        if (on == autoChange) return
        autoChange = on
        engine?.setAutoChange(on)
    }

    /** Test hook (docs/30 §9): the running engine, if any. */
    val currentEngine: VisualizerEngine? get() = engine

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val e = engine
        if (e == null) {
            val hz = display?.refreshRate?.takeIf { it > 1f } ?: 60f
            val changed: (Pattern, Boolean) -> Unit = { p, manual ->
                pattern = p
                onPatternChanged(p, manual)
            }
            engine = VisualizerEngine(context.assets, sourceFactory(), pattern, palettes, onEncodedChanged, (1e9 / hz).toLong(), changed).also {
                it.setAutoChange(autoChange)
                it.start(holder.surface, width, height)
            }
        } else {
            e.resize(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.stop()
        engine = null
    }
}
