package tv.jellybeam.visualizer.pattern

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.color.PaletteFamily

/** docs/30 §9 U9 (E1, E2, E7): each pattern's spec, its budget row, its shaders and its palette family. */
class PatternBudgetTest {
    @Test
    fun everyBudgetRowEqualsItsPatternsRealUsage() {
        for (p in Pattern.entries) {
            val s = PatternSpecs.spec(p)
            val b = PatternSpecs.budget.getValue(p)
            assertEquals("$p passes", s.lowResPasses, b.lowResPasses)
            assertEquals("$p draws", s.draws, b.draws)
            assertEquals("$p fill", s.fill, b.fill)
        }
    }

    @Test
    fun everyPatternFitsTheFrameLimits() {
        for (p in Pattern.entries) {
            val b = PatternSpecs.budget.getValue(p)
            assertTrue("$p passes ${b.lowResPasses}", b.lowResPasses in 1..PatternSpecs.MAX_LOW_RES_PASSES)
            assertTrue("$p draws ${b.draws}", b.draws <= PatternSpecs.MAX_DRAWS)
            assertTrue("$p fill ${b.fill}", b.fill <= PatternSpecs.MAX_FILL)
        }
    }

    @Test
    fun anyCrossfadeFitsTwiceTheLimits() {
        for (a in Pattern.entries) for (b in Pattern.entries) {
            val x = PatternSpecs.budget.getValue(a)
            val y = PatternSpecs.budget.getValue(b)
            assertTrue("$a+$b passes", x.lowResPasses + y.lowResPasses <= 2 * PatternSpecs.MAX_LOW_RES_PASSES)
            assertTrue("$a+$b draws", x.draws + y.draws <= 2 * PatternSpecs.MAX_DRAWS)
            assertTrue("$a+$b fill", x.fill + y.fill <= 2 * PatternSpecs.MAX_FILL)
        }
    }

    @Test
    fun theTableIsPinned() {
        val rows = Pattern.entries.associateWith { p -> PatternSpecs.spec(p).let { listOf(it.lowResPasses, it.draws) } }
        assertEquals(
            mapOf(
                Pattern.BARS to listOf(2, 4), Pattern.RADIAL to listOf(2, 4), Pattern.WATERFALL to listOf(2, 4),
                Pattern.KALEIDO to listOf(2, 3), Pattern.SWIRL to listOf(2, 5), Pattern.FIRE to listOf(2, 4),
                Pattern.PHOSPHOR to listOf(2, 4), Pattern.SUPERSCOPE to listOf(2, 4), Pattern.RIDGELINE to listOf(2, 4),
                Pattern.RIPPLES to listOf(2, 3),
            ),
            rows,
        )
    }

    @Test
    fun everyShaderAssetExists() {
        for (p in Pattern.entries) {
            val s = PatternSpecs.spec(p)
            val paths = listOfNotNull(s.field, s.sim) + (s.inject + s.overlay).map { it.vertexShader }
            for (path in paths) assertTrue("$p $path", File("src/main/assets/$path").isFile)
            for (d in s.inject + s.overlay) assertTrue("$p ${d.vertexShader}", d.vertsPerInstance == 6 && d.instances > 0)
            assertTrue("$p draws something into the layer", s.field != null || s.inject.isNotEmpty())
        }
    }

    @Test
    fun everyPatternHasItsFamilyAndCompositeMode() {
        val expected = mapOf(
            Pattern.BARS to PaletteFamily.ANALOGOUS,
            Pattern.RADIAL to PaletteFamily.TRIAD,
            Pattern.WATERFALL to PaletteFamily.ICE,
            Pattern.KALEIDO to PaletteFamily.SPLIT,
            Pattern.SWIRL to PaletteFamily.TRIAD,
            Pattern.FIRE to PaletteFamily.FIRE,
            Pattern.PHOSPHOR to PaletteFamily.ICE,
            Pattern.SUPERSCOPE to PaletteFamily.SPLIT,
            Pattern.RIDGELINE to PaletteFamily.ANALOGOUS,
            Pattern.RIPPLES to PaletteFamily.ICE,
        )
        assertEquals(expected, Pattern.entries.associateWith { PatternSpecs.spec(it).family })
        for (p in Pattern.entries) {
            val mode = if (p == Pattern.KALEIDO) CompositeMode.KALEIDO else CompositeMode.PLAIN
            assertEquals("$p", mode, PatternSpecs.spec(p).composite)
        }
        // Neighbours in the Left/Right cycle never share a family, so a switch always changes colour.
        for (p in Pattern.entries) assertTrue("$p", PatternSpecs.spec(p).family != PatternSpecs.spec(PatternCycle.next(p)).family)
    }

    @Test
    fun onlyRipplesUsesTheSimGrid() {
        for (p in Pattern.entries) assertEquals("$p", p == Pattern.RIPPLES, PatternSpecs.spec(p).sim != null)
    }
}
