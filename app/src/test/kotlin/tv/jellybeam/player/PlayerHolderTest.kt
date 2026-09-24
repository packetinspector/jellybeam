package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/18-playback-quality.md §3.1: [trackSelectionBaseline] is the per-item reset
 * [PlayerHolder.load] applies before [PlayerHolder.applyTrackDecision], extracted top-level so
 * it's testable against a plain [TrackSelectionParameters] without a real [PlayerHolder] (needs
 * an Android `Context`/`ExoPlayer`). Covers only the pure reset logic, not `load()`/
 * `applyTrackDecision()` end-to-end.
 */
class PlayerHolderTest {

    /** A minimal single-format group, enough to build a [TrackSelectionOverride] against. */
    private fun group(mimeType: String) = TrackGroup(Format.Builder().setSampleMimeType(mimeType).build())

    @Test
    fun `clears a leftover audio override from a previous item`() {
        val previousItemParams = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(group("audio/mp4a-latm"), 0))
            .build()

        val baseline = trackSelectionBaseline(previousItemParams)

        assertTrue("expected no overrides to survive the per-item reset", baseline.overrides.isEmpty())
    }

    @Test
    fun `clears a leftover text override and re-enables text disabled by a previous item's Off`() {
        val previousItemParams = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(group("text/vtt"), 0))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        val baseline = trackSelectionBaseline(previousItemParams)

        assertTrue("expected no overrides to survive the per-item reset", baseline.overrides.isEmpty())
        assertFalse(
            "expected TRACK_TYPE_TEXT to be re-enabled",
            baseline.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
        )
    }

    @Test
    fun `a genuinely global preference is left untouched by the per-item reset`() {
        // Pins that the reset is targeted (overrides + TEXT disabled only), not a wholesale
        // reset to TrackSelectionParameters.DEFAULT.
        val withPreferredLanguage = TrackSelectionParameters.Builder()
            .setPreferredAudioLanguage("eng")
            .build()

        val baseline = trackSelectionBaseline(withPreferredLanguage)

        // Media3 normalises "eng" -> "en"; compare against what the builder actually stored.
        assertEquals(withPreferredLanguage.preferredAudioLanguages, baseline.preferredAudioLanguages)
    }
}
