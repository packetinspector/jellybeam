package tv.jellybeam.ui.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [refreshGuardEligible] over the token-derived freeze (docs/15 §3, §5). */
class RefreshRestoreOwnershipTest {

    @Test
    fun `a top, seeded, resumed page that lost focus to a refresh is eligible`() {
        assertTrue(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = false, frozenByOthers = false))
    }

    @Test
    fun `a drawer or navigation freeze blocks the guard`() {
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = false, frozenByOthers = true))
    }

    @Test
    fun `not top, not seeded, not resumed, or holding focus each block eligibility`() {
        assertFalse(refreshGuardEligible(isTop = false, seeded = true, resumed = true, hasFocus = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = false, resumed = true, hasFocus = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = false, hasFocus = false, frozenByOthers = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = true, frozenByOthers = false))
    }

    @Test
    fun `a refresh supersedes its own running restore but not another placer`() {
        val memory = FocusMemory(null, null)
        memory.beginPlacement() // the refresh owner's running restore
        assertTrue(refreshGuardEligible(true, true, true, false, frozenByOthers = memory.frozenBesides(own = 1)))
        memory.beginPlacement() // e.g. the drawer
        assertFalse(refreshGuardEligible(true, true, true, false, frozenByOthers = memory.frozenBesides(own = 1)))
    }
}
