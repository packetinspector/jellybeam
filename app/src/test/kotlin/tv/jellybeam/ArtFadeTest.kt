package tv.jellybeam

import coil.decode.DataSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtFadeTest {
    @Test
    fun `only network art fades in`() {
        assertEquals(
            listOf(DataSource.NETWORK),
            DataSource.entries.filter { fadesIn(it) },
        )
    }
}
