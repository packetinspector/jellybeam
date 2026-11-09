package tv.jellybeam.visualizer.lab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.pattern.Pattern

/**
 * docs/30 §9 L5: every pattern × the four signals, captured at frame 180 of a fixed feature sequence
 * (feedback depends on history), against reviewed goldens: at most 0.5% of pixels may differ by more
 * than 8/255 in any channel. `-e record true` writes new goldens to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class GoldenTest {
    private val record = InstrumentationRegistry.getArguments().getString("record") == "true"

    @Test
    fun everyPatternMatchesItsGoldens() {
        val failures = mutableListOf<String>()
        val sequences = Preset.entries.associateWith { LabSignals.sequence(it, FRAMES) }
        val only = InstrumentationRegistry.getArguments().getString("patterns")?.split(',')?.toSet()
        for (p in Pattern.entries) {
            if (only != null && p.key !in only) continue
            for (preset in Preset.entries) {
                Lab(p).use { lab ->
                    val frame = lab.captureAfter(sequences.getValue(preset))
                    val name = "${p.key}_${preset.name.lowercase()}.png"
                    if (record) {
                        save(frame, name)
                    } else {
                        val diff = diffFraction(frame, golden(name))
                        if (diff > MAX_DIFF) failures += "$name differs on ${"%.3f".format(diff * 100)}% of pixels"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun save(frame: Frame, name: String) {
        val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("golden")) { "no external files dir" }
        File(dir, name).outputStream().use { frame.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun golden(name: String): Frame {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open("golden/$name").use { Frame.of(BitmapFactory.decodeStream(it)) }
    }

    private companion object {
        const val FRAMES = 180
        const val MAX_DIFF = 0.005
        const val CHANNEL_TOLERANCE = 8

        fun diffFraction(a: Frame, b: Frame): Double {
            if (a.width != b.width || a.height != b.height) return 1.0
            var bad = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                if (Math.abs(a.r(x, y) - b.r(x, y)) > CHANNEL_TOLERANCE ||
                    Math.abs(a.g(x, y) - b.g(x, y)) > CHANNEL_TOLERANCE ||
                    Math.abs(a.b(x, y) - b.b(x, y)) > CHANNEL_TOLERANCE
                ) bad++
            }
            return bad.toDouble() / (a.width * a.height)
        }
    }
}
