package tv.jellybeam.visualizer

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/30 §3: high output rates are analysed at 44.1/48 kHz, so the 1024 window keeps its span. */
class DecimationTest {
    @Test
    fun ratesMapToTheirAnalysisFactor() {
        assertEquals(1, decimation(44_100))
        assertEquals(1, decimation(48_000))
        assertEquals(2, decimation(88_200))
        assertEquals(2, decimation(96_000))
        assertEquals(4, decimation(176_400))
        assertEquals(4, decimation(192_000))
        assertEquals(1, decimation(22_050))
    }

    @Test
    fun ratesOutsideTheAnalyserRangeHaveNoAnalysisRate() {
        assertEquals(null, analysisRate(7_350))
        assertEquals(null, analysisRate(0))
        assertEquals(8_000, analysisRate(8_000))
        assertEquals(48_000, analysisRate(96_000))
        assertEquals(48_000, analysisRate(192_000))
    }
}
