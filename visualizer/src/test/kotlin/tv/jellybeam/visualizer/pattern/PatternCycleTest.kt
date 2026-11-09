package tv.jellybeam.visualizer.pattern

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/30 §9 U12 (E7–E9): the cycle order, wrapping, stored keys and the retired-key migration. */
class PatternCycleTest {
    @Test
    fun theCycleFollowsTheRosterOrderAndWraps() {
        assertEquals(
            listOf("bars", "radial", "waterfall", "kaleido", "swirl", "fire", "phosphor", "superscope", "ridgeline", "ripples"),
            Pattern.entries.map { it.key },
        )
        assertEquals(Pattern.BARS, PatternCycle.next(Pattern.RIPPLES))
        assertEquals(Pattern.RIPPLES, PatternCycle.previous(Pattern.BARS))
        var p = Pattern.BARS
        repeat(Pattern.entries.size) { p = PatternCycle.next(p) }
        assertEquals(Pattern.BARS, p)
    }

    @Test
    fun everyCurrentKeyRoundTrips() {
        for (p in Pattern.entries) assertEquals(p, PatternCycle.fromKey(p.key))
    }

    @Test
    fun retiredKeysOpenTheirSuccessors() {
        val expected = mapOf(
            "scope" to Pattern.PHOSPHOR,
            "xy" to Pattern.PHOSPHOR,
            "tunnel" to Pattern.SWIRL,
            "spiral" to Pattern.SWIRL,
            "pulse" to Pattern.SUPERSCOPE,
            "starfield" to Pattern.SUPERSCOPE,
            "ring" to Pattern.SUPERSCOPE,
            "ribbon" to Pattern.SUPERSCOPE,
            "plasma" to Pattern.SWIRL,
            "tube" to Pattern.SWIRL,
        )
        for ((key, p) in expected) assertEquals(key, p, PatternCycle.fromKey(key))
    }

    @Test
    fun unknownIsBarsAndAFreshProfileIsSwirl() {
        assertEquals(Pattern.BARS, PatternCycle.fromKey("aurora"))
        assertEquals(Pattern.BARS, PatternCycle.fromKey(""))
        assertEquals(Pattern.SWIRL, PatternCycle.fromKey(null))
        assertEquals(Pattern.SWIRL, PatternCycle.DEFAULT)
    }
}
