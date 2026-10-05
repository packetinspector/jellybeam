package tv.jellybeam.player

import tv.jellybeam.i18n.ResourceUiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail

/** [visibleControls] (JELLYBEAM-TV-OSD-SPEC.md §6 order table + §7 density rows) and
 * [formatSpeedLabel].
 */
class PlaybackScreenControlsTest {
    private val strings = ResourceUiStrings.default


    @Test
    fun `direct play chip classifies a 1920 by 960 crop as 1080p`() {
        val video = MediaStreamInfo(
            index = 0,
            streamType = MediaStreamKind.VIDEO,
            codec = "hevc",
            language = null,
            displayTitle = null,
            width = 1920,
            height = 960,
            channels = null,
            bitRate = null,
            bitDepth = null,
            isDefault = true,
            videoRange = null,
            videoRangeType = null,
            profile = "Main 10",
            sampleRate = null,
            avgFrameRate = null,
        )
        val detail = PlaybackOsdDetail(
            container = "mkv",
            mediaStreams = listOf(video),
            chapters = emptyList(),
            sizeBytes = null,
            path = null,
        )

        assertEquals("1080p · HEVC", directPlayCodecSummary(strings, detail))
    }

    // -- visibleControls ---------------------------------------------------

    @Test
    fun `movie, clean, no chapters -- just the three transport buttons plus tracks and info`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.MINIMAL, isEpisode = false, hasPrev = false, hasNext = false, hasChapters = false)
        assertEquals(
            listOf(ControlButton.SKIP_BACK, ControlButton.PLAY_PAUSE, ControlButton.SKIP_FORWARD, ControlButton.TRACKS, ControlButton.LIBRARY_INFO),
            result,
        )
    }

    @Test
    fun `movie, nerdy, no chapters -- adds speed and stats but never the episode buttons`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.FULL, isEpisode = false, hasPrev = true, hasNext = true, hasChapters = false)
        assertEquals(
            listOf(
                ControlButton.SKIP_BACK,
                ControlButton.PLAY_PAUSE,
                ControlButton.SKIP_FORWARD,
                ControlButton.SPEED,
                ControlButton.TRACKS,
                ControlButton.LIBRARY_INFO,
                ControlButton.STATS,
            ),
            result,
        )
    }

    @Test
    fun `episode with both neighbors, nerdy, with chapters -- the full ten-button set in spec order`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.FULL, isEpisode = true, hasPrev = true, hasNext = true, hasChapters = true)
        assertEquals(ControlButton.entries.toList(), result)
    }

    @Test
    fun `episode with no known neighbors omits both episode-skip buttons even though it's an episode`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.FULL, isEpisode = true, hasPrev = false, hasNext = false, hasChapters = false)
        assertFalse(result.contains(ControlButton.PREV_EPISODE))
        assertFalse(result.contains(ControlButton.NEXT_EPISODE))
    }

    @Test
    fun `hasPrev-hasNext are ignored entirely for a non-episode item`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.FULL, isEpisode = false, hasPrev = true, hasNext = true, hasChapters = false)
        assertFalse(result.contains(ControlButton.PREV_EPISODE))
        assertFalse(result.contains(ControlButton.NEXT_EPISODE))
    }

    @Test
    fun `chapters button shows in clean mode too whenever markers exist`() {
        val result = visibleControls(osdDetail = OsdDetailSetting.MINIMAL, isEpisode = false, hasPrev = false, hasNext = false, hasChapters = true)
        assertTrue(result.contains(ControlButton.CHAPTERS))
        // Clean never gets speed/stats regardless of chapters.
        assertFalse(result.contains(ControlButton.SPEED))
        assertFalse(result.contains(ControlButton.STATS))
    }

    @Test
    fun `speed and stats are nerdy-only regardless of every other flag`() {
        val allClean = listOf(true, false).flatMap { prev ->
            listOf(true, false).flatMap { next ->
                listOf(true, false).flatMap { chapters ->
                    listOf(true, false).map { episode ->
                        visibleControls(OsdDetailSetting.MINIMAL, episode, prev, next, chapters)
                    }
                }
            }
        }
        allClean.forEach {
            assertFalse(it.contains(ControlButton.SPEED))
            assertFalse(it.contains(ControlButton.STATS))
        }
    }

    @Test
    fun `transport, tracks, and library info always appear regardless of every flag combination`() {
        for (osdDetail in listOf(OsdDetailSetting.MINIMAL, OsdDetailSetting.FULL)) {
            for (isEpisode in listOf(true, false)) {
                for (hasPrev in listOf(true, false)) {
                    for (hasNext in listOf(true, false)) {
                        for (hasChapters in listOf(true, false)) {
                            val result = visibleControls(osdDetail, isEpisode, hasPrev, hasNext, hasChapters)
                            assertTrue(result.contains(ControlButton.SKIP_BACK))
                            assertTrue(result.contains(ControlButton.PLAY_PAUSE))
                            assertTrue(result.contains(ControlButton.SKIP_FORWARD))
                            assertTrue(result.contains(ControlButton.TRACKS))
                            assertTrue(result.contains(ControlButton.LIBRARY_INFO))
                            // §6: no disabled state -- every button present is actionable, no
                            // duplicates.
                            assertEquals(result.size, result.toSet().size)
                            // PREV/NEXT episode gate strictly on isEpisode && has*.
                            assertEquals(isEpisode && hasPrev, result.contains(ControlButton.PREV_EPISODE))
                            assertEquals(isEpisode && hasNext, result.contains(ControlButton.NEXT_EPISODE))
                            assertEquals(hasChapters, result.contains(ControlButton.CHAPTERS))
                        }
                    }
                }
            }
        }
    }

    // -- formatSpeedLabel ------------------------------------------------------

    @Test
    fun `every speed menu option formats per spec's own literal list`() {
        assertEquals("0.5×", formatSpeedLabel(0.5f))
        assertEquals("0.75×", formatSpeedLabel(0.75f))
        assertEquals("1×", formatSpeedLabel(1f))
        assertEquals("1.25×", formatSpeedLabel(1.25f))
        assertEquals("1.5×", formatSpeedLabel(1.5f))
        assertEquals("2×", formatSpeedLabel(2f))
    }
}
