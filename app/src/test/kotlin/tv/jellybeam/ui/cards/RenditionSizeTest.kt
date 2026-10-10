package tv.jellybeam.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.ImageKind

class RenditionSizeTest {
    @Test
    fun `size comes from the URL's maxWidth and the aspect`() {
        val url = "http://localhost:8096/Items/i/Images/Primary?tag=t&maxWidth=320&quality=90"
        assertEquals(320 to 480, renditionSizePx(url, 3f / 2f))
        assertEquals(320 to 180, renditionSizePx(url, 9f / 16f))
    }

    @Test
    fun `a URL without maxWidth leaves sizing to layout`() {
        assertNull(renditionSizePx("http://localhost:8096/Items/i/Images/Primary?tag=t", 1f))
    }

    @Test
    fun `primary art is a poster, every other kind is wide`() {
        assertEquals(3f / 2f, renditionAspect(ArtSource.Own("i", "t", ImageKind.PRIMARY)), 0f)
        assertEquals(3f / 2f, renditionAspect(ArtSource.Fallback("i", "t", ImageKind.PRIMARY)), 0f)
        assertEquals(9f / 16f, renditionAspect(ArtSource.Own("i", "t", ImageKind.BACKDROP)), 0f)
        assertEquals(9f / 16f, renditionAspect(ArtSource.Fallback("i", "t", ImageKind.THUMB)), 0f)
        assertEquals(9f / 16f, renditionAspect(ArtSource.None), 0f)
    }
}
