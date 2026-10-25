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

    @Test
    fun `group loads never arm the keyframe resume`() {
        // docs/28 §5 inv 11: a keyframe-snapped landing would make the server re-send Seek forever.
        for (planStartTicks in listOf(0L, 420_000_000L)) {
            for (transcode in listOf(false, true)) {
                val opening = loadOpening(planStartTicks, transcode, LoadStart.GroupExactPaused(ticks = 300_000_000L))
                assertFalse("start=$planStartTicks transcode=$transcode", opening.armResume)
            }
        }
    }

    @Test
    fun `a group load starts paused at its exact position`() {
        val opening = loadOpening(planStartTicks = 0L, transcode = false, LoadStart.GroupExactPaused(ticks = 123_456_789L))

        assertEquals(12_345L, opening.startPositionMs)
        assertFalse(opening.playWhenReady)
    }

    @Test
    fun `a resume load still lands on the keyframe and plays, except a transcode`() {
        assertEquals(LoadOpening(42_000L, armResume = true, playWhenReady = true), loadOpening(420_000_000L, false, LoadStart.Resume))
        assertEquals(LoadOpening(42_000L, armResume = false, playWhenReady = true), loadOpening(420_000_000L, true, LoadStart.Resume))
        assertEquals(LoadOpening(0L, armResume = false, playWhenReady = true), loadOpening(0L, false, LoadStart.Resume))
    }

    @Test
    fun `play resumes a paused player`() {
        // Pins the test player's contract the executor's view-model tests rely on.
        val player = FakePlaybackPlayer()
        player.pause()

        player.play()

        assertTrue(player.playWhenReady)
    }
}
