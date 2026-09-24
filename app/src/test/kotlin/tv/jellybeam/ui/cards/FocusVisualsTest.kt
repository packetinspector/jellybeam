package tv.jellybeam.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Test

/** [focusVisuals]'s endpoints lock the visual contract (docs/07 §6: 1.04 scale, 12% white lift,
 * 16dp shadow, full ring). Scale is driven by its own `scaleProgress` (§0.4's timing exception,
 * see [FOCUS_SCALE_ANIM_MS]); ring/lift/shadow share the other `progress` argument.
 */
class FocusVisualsTest {

    @Test
    fun `progress 0 is the resting state`() {
        val visuals = focusVisuals(progress = 0f, scaleProgress = 0f)

        assertEquals(1f, visuals.scale, 0f)
        assertEquals(0f, visuals.ringAlpha, 0f)
        assertEquals(0f, visuals.brightnessLift, 0f)
        assertEquals(0f, visuals.shadowDp, 0f)
    }

    @Test
    fun `progress 1 is the fully focused state`() {
        val visuals = focusVisuals(progress = 1f, scaleProgress = 1f)

        assertEquals(1.04f, visuals.scale, 0.0001f)
        assertEquals(1f, visuals.ringAlpha, 0f)
        assertEquals(0.12f, visuals.brightnessLift, 0.0001f)
        assertEquals(16f, visuals.shadowDp, 0.0001f)
    }

    @Test
    fun `midpoint interpolates linearly on every axis`() {
        val visuals = focusVisuals(progress = 0.5f, scaleProgress = 0.5f)

        assertEquals(1.02f, visuals.scale, 0.0001f)
        assertEquals(0.5f, visuals.ringAlpha, 0f)
        assertEquals(0.06f, visuals.brightnessLift, 0.0001f)
        assertEquals(8f, visuals.shadowDp, 0.0001f)
    }

    @Test
    fun `scale runs on its own progress, independent of ring, lift and shadow`() {
        // Scale's 120ms animation finishes well before the other three's 180/240ms one, so at any
        // instant they're commonly at different points -- verify they're read independently.
        val visuals = focusVisuals(progress = 0.25f, scaleProgress = 1f)

        assertEquals(1.04f, visuals.scale, 0.0001f)
        assertEquals(0.25f, visuals.ringAlpha, 0f)
        assertEquals(0.03f, visuals.brightnessLift, 0.0001f)
        assertEquals(4f, visuals.shadowDp, 0.0001f)
    }

    @Test
    fun `each progress is clamped independently, not extrapolated`() {
        assertEquals(focusVisuals(1f, 1f), focusVisuals(1.5f, 1.5f))
        assertEquals(focusVisuals(0f, 0f), focusVisuals(-0.5f, -0.5f))
        // A clamp on one axis doesn't affect the other.
        assertEquals(focusVisuals(0f, 1f), focusVisuals(-0.5f, 1.5f))
    }
}
