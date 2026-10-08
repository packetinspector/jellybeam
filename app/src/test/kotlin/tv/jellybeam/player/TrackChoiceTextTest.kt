package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

class TrackChoiceTextTest {
    private val strings = ResourceUiStrings.default

    private fun info(title: String?, lang: String?, codec: String?) = TrackInfo(
        id = 1L, kind = TrackKindFfi.SUBTITLE, title = title, lang = lang, codec = codec,
        isDefault = false, isSelected = false, isForced = false,
    )

    @Test
    fun `a blank title falls back to the language, then to Track N`() {
        assertEquals("Commentary", TrackChoiceText.label(strings, info("Commentary", "en", null), 0))
        assertEquals("en", TrackChoiceText.label(strings, info("", "en", null), 0))
        assertEquals("en", TrackChoiceText.label(strings, info("  ", "en", null), 0))
        assertEquals("Track 3", TrackChoiceText.label(strings, info("", "", null), 2))
    }

    @Test
    fun `a side-loaded subtitle is tagged External`() {
        val srt = info(null, "en", "application/x-subrip")
        assertEquals("en application/x-subrip", TrackChoiceText.meta(strings, srt, external = false))
        assertEquals("en application/x-subrip · External", TrackChoiceText.meta(strings, srt, external = true))
        assertEquals("External", TrackChoiceText.meta(strings, info(null, null, null), external = true))
        assertNull(TrackChoiceText.meta(strings, info(null, null, null), external = false))
    }

    @Test
    fun `a sidecar row reads its fetch state`() {
        val srt = info(null, "en", "srt")
        assertEquals("en srt · External · Loading…", TrackChoiceText.meta(strings, srt, external = true, status = SidecarStatus.LOADING))
        assertEquals("External · Unavailable", TrackChoiceText.meta(strings, info(null, null, null), external = true, status = SidecarStatus.UNAVAILABLE))
    }
}
