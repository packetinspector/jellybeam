package tv.jellybeam.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerOpenerTest {
    private val own = "tv.jellybeam.test"
    private val key = "dQw4w9WgXcQ"
    private val watchUrl = "https://www.youtube.com/watch?v=$key"

    private val stub = TrailerHandler("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$BrowserStub")
    private val smartTube = TrailerHandler("org.smarttube.stable", "org.smarttube.Main")
    private val tizenTube = TrailerHandler("com.tizentube", "com.tizentube.Main")
    private val browser = TrailerHandler("org.example.browser", "org.example.browser.Main")
    private val ownHandler = TrailerHandler(own, "tv.jellybeam.MainActivity")

    private fun decide(
        url: String,
        vnd: List<TrailerHandler> = emptyList(),
        https: List<TrailerHandler> = emptyList(),
    ): TrailerLaunch = decideTrailerLaunch(url, own) { candidate ->
        when (candidate) {
            is TrailerCandidate.Vnd -> vnd
            is TrailerCandidate.Https -> https
        }
    }

    @Test
    fun `key parses from every supported youtube form`() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=$key",
            "https://youtube.com/watch?v=$key",
            "https://m.youtube.com/watch?v=$key",
            "https://music.youtube.com/watch?v=$key",
            "http://www.youtube.com/watch?v=$key",
            "https://WWW.YouTube.com/watch?v=$key",
            "https://youtu.be/$key",
            "https://www.youtube.com/embed/$key",
            "https://www.youtube.com/shorts/$key",
        )
        for (url in urls) assertEquals(url, key, youtubeKey(url))
    }

    @Test
    fun `key parsing drops extra params and fragments`() {
        assertEquals(key, youtubeKey("https://www.youtube.com/watch?feature=share&v=$key&t=42s"))
        assertEquals(key, youtubeKey("https://youtu.be/$key?si=abc&t=10"))
        assertEquals(key, youtubeKey("https://www.youtube.com/embed/$key?autoplay=1"))
        assertEquals(key, youtubeKey("https://www.youtube.com/watch?v=$key#t=5"))
        assertEquals("a-b_c1", youtubeKey("https://youtu.be/a-b_c1"))
    }

    @Test
    fun `key parsing rejects non youtube hosts and junk`() {
        val junk = listOf(
            "",
            "   ",
            "not a url",
            "youtube.com/watch?v=$key",
            "ftp://www.youtube.com/watch?v=$key",
            "https://example.test/watch?v=$key",
            "https://youtube.com.example.test/watch?v=$key",
            "https://notyoutube.com/watch?v=$key",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?v=bad%20key!!",
            "https://www.youtube.com/watch?x=$key",
            "https://www.youtube.com/playlist?list=$key",
            "https://www.youtube.com/embed/",
            "https://www.youtube.com/channel/$key",
            "https://youtu.be/",
            "https://youtu.be/ab",
            "https://youtu.be/$key/extra/../../",
        )
        for (url in junk) assertNull(url, youtubeKey(url))
    }

    @Test
    fun `one vnd handler gets the https watch url aimed at its component`() {
        assertEquals(TrailerLaunch.Explicit(watchUrl, smartTube), decide(watchUrl, vnd = listOf(smartTube), https = listOf(browser)))
    }

    @Test
    fun `a short link launches the canonical watch url`() {
        assertEquals(TrailerLaunch.Explicit(watchUrl, smartTube), decide("https://youtu.be/$key?t=3", vnd = listOf(smartTube)))
    }

    @Test
    fun `several vnd handlers get a watch url picker limited to them`() {
        assertEquals(
            TrailerLaunch.Chooser(watchUrl, listOf(stub, browser)),
            decide(watchUrl, vnd = listOf(smartTube, tizenTube), https = listOf(stub, smartTube, tizenTube, browser)),
        )
    }

    @Test
    fun `vnd stub only falls through to the https branch`() {
        assertEquals(TrailerLaunch.Explicit(watchUrl, browser), decide(watchUrl, vnd = listOf(stub), https = listOf(stub, browser)))
    }

    @Test
    fun `vnd handlers beat https handlers`() {
        assertEquals(TrailerLaunch.Explicit(watchUrl, smartTube), decide(watchUrl, vnd = listOf(stub, smartTube), https = listOf(stub, tizenTube, browser)))
    }

    @Test
    fun `no vnd handler uses the https url`() {
        assertEquals(TrailerLaunch.Explicit(watchUrl, smartTube), decide(watchUrl, https = listOf(smartTube)))
    }

    @Test
    fun `stub plus two real https handlers is a chooser excluding the stub`() {
        assertEquals(
            TrailerLaunch.Chooser(watchUrl, listOf(stub)),
            decide(watchUrl, https = listOf(stub, smartTube, tizenTube)),
        )
    }

    @Test
    fun `two real https handlers with no stub is a chooser excluding nothing`() {
        assertEquals(TrailerLaunch.Chooser(watchUrl, emptyList()), decide(watchUrl, https = listOf(smartTube, tizenTube)))
    }

    @Test
    fun `nothing real is unavailable`() {
        assertEquals(TrailerLaunch.Unavailable, decide(watchUrl))
        assertEquals(TrailerLaunch.Unavailable, decide(watchUrl, vnd = listOf(stub), https = listOf(stub)))
    }

    @Test
    fun `our own package is never a handler`() {
        assertEquals(TrailerLaunch.Unavailable, decide(watchUrl, vnd = listOf(ownHandler), https = listOf(ownHandler)))
        assertEquals(TrailerLaunch.Explicit(watchUrl, smartTube), decide(watchUrl, vnd = listOf(ownHandler, smartTube)))
        assertEquals(
            TrailerLaunch.Chooser(watchUrl, listOf(ownHandler, stub)),
            decide(watchUrl, https = listOf(ownHandler, stub, smartTube, tizenTube)),
        )
    }

    @Test
    fun `non youtube url only uses the https branch`() {
        val url = "https://videos.example.test/trailer.mp4"
        val queried = mutableListOf<TrailerCandidate>()
        val launch = decideTrailerLaunch(url, own) { candidate ->
            queried += candidate
            listOf(browser)
        }
        assertEquals(TrailerLaunch.Explicit(url, browser), launch)
        assertEquals(listOf<TrailerCandidate>(TrailerCandidate.Https(url)), queried)
    }

    @Test
    fun `non https url is unavailable without querying`() {
        var queried = false
        val launch = decideTrailerLaunch("javascript:alert(1)", own) { queried = true; listOf(browser) }
        assertEquals(TrailerLaunch.Unavailable, launch)
        assertEquals(false, queried)
    }
}
