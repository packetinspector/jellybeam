package tv.jellybeam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uniffi.jellybeam_core.ImageKind

/** Parity with the core's `jellyfin-api` `image_url_*` tests: same inputs, same strings. */
class ImageUrlsTest {
    private val base = ImageUrlBase(epoch = 3uL, baseUrl = "http://localhost:8096", token = "tok en/with?special")

    @Test
    fun expectedQueryMatchesTheCore() {
        assertEquals(
            "http://localhost:8096/Items/item%201/Images/Primary?tag=tag%261&maxWidth=300&quality=90&format=Webp&ApiKey=tok%20en%2Fwith%3Fspecial",
            base.url("item 1", ImageKind.PRIMARY, "tag&1", 300u),
        )
    }

    @Test
    fun kindsMapToTheirPathSegment() {
        val plain = ImageUrlBase(0uL, "http://localhost:8096", "t")
        assert(plain.url("i", ImageKind.BACKDROP, "tag", 100u).contains("/Images/Backdrop?"))
        assert(plain.url("i", ImageKind.THUMB, "tag", 100u).contains("/Images/Thumb?"))
    }

    @Test
    fun backdropsShipAtLowerQualityThanSharpArt() {
        val plain = ImageUrlBase(0uL, "http://localhost:8096", "t")
        assert(plain.url("i", ImageKind.BACKDROP, "tag", 1280u).contains("quality=80"))
        assert(plain.url("i", ImageKind.PRIMARY, "tag", 320u).contains("quality=90"))
        assert(plain.url("i", ImageKind.THUMB, "tag", 400u).contains("quality=90"))
    }

    @Test
    fun nonAsciiIsEncodedPerUtf8Byte() {
        assertEquals("%C3%A9", ImageUrls.percentEncode("é"))
        assertEquals("A-z_0.~", ImageUrls.percentEncode("A-z_0.~"))
    }

    @Test
    fun theTokenNeverReachesToString() {
        assertFalse(base.toString().contains("tok"))
    }
}
