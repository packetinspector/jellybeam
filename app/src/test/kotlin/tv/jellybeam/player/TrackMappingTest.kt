package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.TrackKindFfi

/**
 * [TrackMapping] works on plain media3-common data classes, so fixtures build
 * [Format]/[TrackGroup]/[Tracks] directly with no Android runtime. Tracks sharing one [TrackGroup]
 * must agree on language and role flags (`TrackGroup.verifyCorrectness()`), so same-group fixtures
 * are distinguished by `label` instead, with separate groups for distinct languages.
 */
class TrackMappingTest {

    private fun audioFormat(
        label: String? = null,
        language: String? = null,
        codecs: String? = "aac",
        sampleMimeType: String? = "audio/mp4a-latm",
        selectionFlags: Int = 0,
    ) = Format.Builder()
        .setSampleMimeType(sampleMimeType)
        .setLanguage(language)
        .setLabel(label)
        .setCodecs(codecs)
        .setSelectionFlags(selectionFlags)
        .build()

    private fun textFormat(
        label: String? = null,
        language: String? = null,
        selectionFlags: Int = 0,
    ) = Format.Builder()
        .setSampleMimeType("text/vtt")
        .setLanguage(language)
        .setLabel(label)
        .setSelectionFlags(selectionFlags)
        .build()

    private fun videoFormat() = Format.Builder().setSampleMimeType("video/avc").build()

    /** [trackSelected] defaults every track in the group to unselected -- pass explicit flags to
     * mark one selected.
     */
    private fun group(vararg formats: Format, trackSelected: BooleanArray? = null): Tracks.Group {
        val trackGroup = TrackGroup(*formats)
        val support = IntArray(formats.size) { C.FORMAT_HANDLED }
        val selected = trackSelected ?: BooleanArray(formats.size)
        return Tracks.Group(trackGroup, /* adaptiveSupported= */ false, support, selected)
    }

    // -- toTrackInfos --------------------------------------------------

    @Test
    fun `maps audio and text groups only, excluding video`() {
        val tracks = Tracks(
            listOf(
                group(videoFormat()),
                group(audioFormat(language = "eng")),
                group(textFormat(language = "eng")),
            ),
        )

        val infos = TrackMapping.toTrackInfos(tracks)

        assertEquals(2, infos.size)
        assertEquals(TrackKindFfi.AUDIO, infos[0].kind)
        assertEquals(TrackKindFfi.SUBTITLE, infos[1].kind)
    }

    @Test
    fun `id scheme is groupIndex times 1000 plus trackIndex`() {
        val tracks = Tracks(
            listOf(
                group(audioFormat(label = "Stereo"), audioFormat(label = "Surround 5.1")),
                group(textFormat(language = "eng")),
            ),
        )

        val infos = TrackMapping.toTrackInfos(tracks)

        assertEquals(listOf(0L, 1L, 1000L), infos.map { it.id })
    }

    @Test
    fun `maps title and codec fields`() {
        val tracks = Tracks(
            listOf(group(audioFormat(label = "English", codecs = "ac-3"))),
        )

        val info = TrackMapping.toTrackInfos(tracks).single()

        assertEquals("English", info.title)
        assertEquals("ac-3", info.codec)
    }

    /**
     * Media3 normalizes language tags at `Format.Builder.build()` time
     * (e.g. `"eng"` -> `"en"`), so [TrackMapping] must pass through the
     * built [Format]'s own field, not the caller's raw input. Server
     * compatibility note: `Settings.language.*` and `lang_matches` expect
     * Jellyfin's three-letter ISO-639-2 codes, so a normalized two-letter
     * code won't match a global preference string.
     */
    @Test
    fun `lang is whatever Format normalizes it to, not the raw input`() {
        val format = audioFormat(language = "eng")
        val tracks = Tracks(listOf(group(format)))

        val info = TrackMapping.toTrackInfos(tracks).single()

        assertEquals(format.language, info.lang)
    }

    @Test
    fun `codec falls back to sampleMimeType when codecs is absent`() {
        val tracks = Tracks(
            listOf(group(audioFormat(codecs = null, sampleMimeType = "audio/mp4a-latm"))),
        )

        val info = TrackMapping.toTrackInfos(tracks).single()

        assertEquals("audio/mp4a-latm", info.codec)
    }

    @Test
    fun `isDefault reflects SELECTION_FLAG_DEFAULT`() {
        val tracks = Tracks(
            listOf(
                group(
                    audioFormat(label = "Not default", selectionFlags = 0),
                    audioFormat(label = "Default", selectionFlags = C.SELECTION_FLAG_DEFAULT),
                ),
            ),
        )

        val infos = TrackMapping.toTrackInfos(tracks)

        assertEquals(false, infos[0].isDefault)
        assertEquals(true, infos[1].isDefault)
    }

    @Test
    fun `isForced reflects SELECTION_FLAG_FORCED`() {
        // Media3 1.9.0 has no ROLE_FLAG_FORCED_SUBTITLE, so SELECTION_FLAG_FORCED
        // is the only forced signal; both formats share roleFlags = 0 to stay a
        // valid TrackGroup pairing.
        val tracks = Tracks(
            listOf(
                group(
                    textFormat(label = "Not forced", selectionFlags = 0),
                    textFormat(label = "Forced", selectionFlags = C.SELECTION_FLAG_FORCED),
                ),
            ),
        )

        val infos = TrackMapping.toTrackInfos(tracks)

        assertEquals(false, infos[0].isForced)
        assertEquals(true, infos[1].isForced)
    }

    @Test
    fun `isSelected reflects the group's own selected flags`() {
        val tracks = Tracks(
            listOf(
                group(
                    audioFormat(label = "Stereo"),
                    audioFormat(label = "Surround 5.1"),
                    trackSelected = booleanArrayOf(false, true),
                ),
            ),
        )

        val infos = TrackMapping.toTrackInfos(tracks)

        assertEquals(false, infos[0].isSelected)
        assertEquals(true, infos[1].isSelected)
    }

    @Test
    fun `no audio or text groups maps to an empty list`() {
        val tracks = Tracks(listOf(group(videoFormat())))
        assertEquals(emptyList<Any>(), TrackMapping.toTrackInfos(tracks))
    }

    // -- resolve ---------------------------------------------------------

    @Test
    fun `resolve reverses toId against the same Tracks snapshot`() {
        val tracks = Tracks(
            listOf(
                group(audioFormat(label = "Stereo"), audioFormat(label = "Surround 5.1")),
                group(textFormat(language = "eng")),
            ),
        )

        assertEquals(TrackMapping.ResolvedTrack(0, 1), TrackMapping.resolve(tracks, 1L))
        assertEquals(TrackMapping.ResolvedTrack(1, 0), TrackMapping.resolve(tracks, 1000L))
    }

    @Test
    fun `resolve returns null for a negative id`() {
        val tracks = Tracks(listOf(group(audioFormat())))
        assertNull(TrackMapping.resolve(tracks, -1L))
    }

    @Test
    fun `resolve returns null for a group index past the end`() {
        val tracks = Tracks(listOf(group(audioFormat())))
        assertNull(TrackMapping.resolve(tracks, 1000L)) // group index 1 doesn't exist
    }

    @Test
    fun `resolve returns null for a track index past the group's own length`() {
        val tracks = Tracks(listOf(group(audioFormat())))
        assertNull(TrackMapping.resolve(tracks, 1L)) // group 0 has only track index 0
    }
}
