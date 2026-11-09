package tv.jellybeam.visualizer.lab

import android.app.Activity
import android.os.Bundle
import tv.jellybeam.visualizer.FeatureSource
import tv.jellybeam.visualizer.color.PaletteFamily
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.gl.VisualizerView
import tv.jellybeam.visualizer.pattern.Pattern

/** docs/30 §9: hosts one visualizer surface at window size for the lab's instrumented tests. */
class LabActivity : Activity() {
    lateinit var view: VisualizerView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = VisualizerView(this, { Harness.sourceFactory() }, Harness.pattern, Harness.palettes)
        view.setAutoChange(Harness.autoChange)
        // T23's control: the same window with a plain view, to measure the platform's own per-open cost.
        setContentView(if (Harness.bare) android.view.View(this) else view)
    }

    /** What the next activity draws; set by a test before launching. */
    object Harness {
        /** The default accent's five families (synthetic copy of brand §3 Pistacchio: accent, sheen). */
        val defaultPalettes: List<FloatArray> =
            PaletteFamily.entries.map { Palettes.build(it, 0xFFA8CB6B.toInt(), 0xFFC6DE9B.toInt()) }

        @Volatile
        var sourceFactory: () -> FeatureSource = { LabSource() }

        @Volatile
        var pattern: Pattern = Pattern.BARS

        @Volatile
        var palettes: List<FloatArray> = defaultPalettes

        /** Off by default so a capture never lands mid-transition; transition tests turn it on. */
        @Volatile
        var autoChange = false

        @Volatile
        var bare = false

        /** Back to the defaults, so one test's choices never leak into the next. */
        fun reset() {
            sourceFactory = { LabSource() }
            pattern = Pattern.BARS
            palettes = defaultPalettes
            autoChange = false
            bare = false
        }
    }
}
