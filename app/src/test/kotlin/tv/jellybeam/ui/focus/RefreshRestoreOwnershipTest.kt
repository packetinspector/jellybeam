package tv.jellybeam.ui.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [refreshGuardEligible] over the token-derived freeze (docs/15 §3, §5). */
class RefreshRestoreOwnershipTest {

    @Test
    fun `a top, seeded, resumed page that lost focus to a refresh is eligible`() {
        assertTrue(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = false, focusElsewhere = false, frozenByOthers = false))
    }

    @Test
    fun `a drawer or navigation freeze blocks the guard`() {
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = false, focusElsewhere = false, frozenByOthers = true))
    }

    @Test
    fun `not top, not seeded, not resumed, or holding focus each block eligibility`() {
        assertFalse(refreshGuardEligible(isTop = false, seeded = true, resumed = true, hasFocus = false, focusElsewhere = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = false, resumed = true, hasFocus = false, focusElsewhere = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = false, hasFocus = false, focusElsewhere = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = true, focusElsewhere = false, frozenByOthers = false))
    }

    @Test
    fun `a refresh supersedes its own running restore but not another placer`() {
        val memory = FocusMemory(null, null)
        memory.beginPlacement() // the refresh owner's running restore
        assertTrue(refreshGuardEligible(true, true, true, false, focusElsewhere = false, frozenByOthers = memory.frozenBesides(own = 1)))
        memory.beginPlacement() // e.g. the drawer
        assertFalse(refreshGuardEligible(true, true, true, false, focusElsewhere = false, frozenByOthers = memory.frozenBesides(own = 1)))
    }

    @Test
    fun `focus on the music bar outside the screen blocks the guard`() {
        val seat = FocusSeat().apply { windowHasFocus = { true } }
        assertFalse(
            refreshGuardEligible(
                isTop = true,
                seeded = true,
                resumed = true,
                hasFocus = false,
                focusElsewhere = seat.focusElsewhere(screenHasFocus = false),
                frozenByOthers = false,
            ),
        )
    }

    @Test
    fun `focus is elsewhere only when the window holds it and the screen does not`() {
        val held = FocusSeat().apply { windowHasFocus = { true } }
        val dropped = FocusSeat().apply { windowHasFocus = { false } }
        assertTrue(held.focusElsewhere(screenHasFocus = false))
        assertFalse(held.focusElsewhere(screenHasFocus = true))
        assertFalse(dropped.focusElsewhere(screenHasFocus = false))
    }
}
