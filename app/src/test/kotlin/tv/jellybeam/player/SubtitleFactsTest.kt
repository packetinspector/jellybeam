package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/12 §16: a Direct Play file's server facts reach a row only when its streams pair up with the player's tracks. */
class SubtitleFactsTest {
    private fun text(id: Long, lang: String?, title: String? = null) = TrackInfo(
        id = id, kind = TrackKindFfi.SUBTITLE, title = title, lang = lang, codec = "text/x-ssa",
        isDefault = false, isSelected = false, isForced = false, isHearingImpaired = false,
    )

    private fun audio(id: Long) = TrackInfo(
        id = id, kind = TrackKindFfi.AUDIO, title = null, lang = "en", codec = "audio/ac3",
        isDefault = false, isSelected = false, isForced = false, isHearingImpaired = false,
    )

    private fun stream(
        index: Int,
        lang: String?,
        sdh: Boolean = false,
        forced: Boolean = false,
        codec: String? = "ass",
        title: String? = null,
        displayTitle: String? = null,
        delivery: SubtitleDeliveryFfi? = null,
    ) = EmbeddedSubtitleFfi(
        index = index, language = lang, title = title, forced = forced, default = false, codec = codec,
        displayTitle = displayTitle, delivery = delivery, hearingImpaired = sdh,
    )

    private fun facts(infos: List<TrackInfo>, streams: List<EmbeddedSubtitleFfi>) = serverSubtitleFacts(infos, streams)

    @Test
    fun `withServerFacts copies SDH, forced and a non-blank codec onto the paired subtitle rows only`() {
        val infos = listOf(audio(0), text(1000, "eng").copy(codec = "application/x-subrip"), text(2000, "eng"), text(3000, "fra"))
        val facts = mapOf(
            0L to SubtitleFacts(hearingImpaired = true, forced = true, codec = "eac3"),
            1000L to SubtitleFacts(hearingImpaired = true, forced = true, codec = "subrip"),
            2000L to SubtitleFacts(hearingImpaired = false, forced = false, codec = " "),
        )
        val got = withServerFacts(infos, facts)
        assertEquals("the audio row has no subtitle facts to take", infos[0], got[0])
        assertEquals(infos[1].copy(codec = "subrip", isForced = true, isHearingImpaired = true), got[1])
        assertEquals("a blank server codec keeps the player's", infos[2], got[2])
        assertEquals("no facts, no change", infos[3], got[3])
        assertEquals(infos, withServerFacts(infos, emptyMap()))
    }

    @Test
    fun `titles that disagree give no facts, and a title on one side only proves nothing`() {
        val swapped = facts(
            infos = listOf(text(1000, "eng", "Signs"), text(2000, "eng", "SDH")),
            streams = listOf(stream(2, "eng", sdh = true, title = "SDH"), stream(3, "eng", title = "Signs")),
        )
        assertTrue(swapped.isEmpty())
        val oneSided = facts(infos = listOf(text(1000, "eng", "SDH")), streams = listOf(stream(2, "eng", sdh = true)))
        assertEquals(SubtitleFacts(hearingImpaired = true, forced = false, codec = "ass"), oneSided[1000L])
    }

    @Test
    fun `tracks and streams of equal count and languages pair up by position`() {
        val got = facts(
            infos = listOf(audio(0), text(1000, "eng"), text(2000, "fre"), text(3000, null)),
            streams = listOf(stream(2, "eng", sdh = true, codec = "ass"), stream(3, "fra", forced = true, codec = "subrip"), stream(4, null)),
        )
        assertEquals(
            mapOf(
                1000L to SubtitleFacts(hearingImpaired = true, forced = false, codec = "ass"),
                2000L to SubtitleFacts(hearingImpaired = false, forced = true, codec = "subrip"),
                3000L to SubtitleFacts(hearingImpaired = false, forced = false, codec = "ass"),
            ),
            got,
        )
    }

    @Test
    fun `a count mismatch gives no facts`() {
        val streams = listOf(stream(2, "eng", sdh = true), stream(3, "eng"))
        assertTrue(facts(listOf(text(1000, "eng")), streams).isEmpty())
        assertTrue(facts(listOf(text(1000, "eng"), text(2000, "eng"), text(3000, "eng")), streams).isEmpty())
        assertTrue(facts(listOf(text(1000, "eng")), emptyList()).isEmpty())
    }

    @Test
    fun `any language disagreement gives no facts for the whole file`() {
        val infos = listOf(text(1000, "eng"), text(2000, "fre"))
        assertTrue(facts(infos, listOf(stream(2, "eng", sdh = true), stream(3, "ger", sdh = true))).isEmpty())
        assertTrue("one known, one absent", facts(infos, listOf(stream(2, "eng"), stream(3, null))).isEmpty())
        assertTrue("swapped order", facts(infos, listOf(stream(2, "fra"), stream(3, "eng"))).isEmpty())
    }

    @Test
    fun `an undetermined language on both sides agrees`() {
        val got = facts(listOf(text(1000, null)), listOf(stream(2, "und", sdh = true)))
        assertEquals(true, got[1000L]?.hearingImpaired)
    }

    @Test
    fun `a sidecar copy in the plan is not a track of the file`() {
        val got = facts(
            infos = listOf(text(1000, "eng")),
            streams = listOf(stream(2, "eng", sdh = true), stream(7, "eng", delivery = SubtitleDeliveryFfi.HLS)),
        )
        assertEquals(true, got[1000L]?.hearingImpaired)
    }
}
