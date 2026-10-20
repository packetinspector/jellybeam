package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [chooseDecoderList] is the decision behind [LevelTolerantMediaCodecVideoRenderer]'s
 * bogus-declared-level tolerance; media3 plumbing itself isn't unit-testable here. Pins the
 * required fallback order: hw-with-level > hw-ignoring-level > software.
 */
class DecoderSelectionTest {

    @Test
    fun `FFmpeg audio preferences affect only their selected codec families`() {
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_TRUEHD,
                AudioDecoderPreferences(preferFfmpegTrueHd = true),
            ),
        )
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_DTS_EXPRESS,
                AudioDecoderPreferences(preferFfmpegDts = true),
            ),
        )
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_DTS_HD,
                AudioDecoderPreferences(preferFfmpegDtsHd = true),
            ),
        )
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_DTS_X,
                AudioDecoderPreferences(preferFfmpegDtsHd = true),
            ),
        )
        assertFalse(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_AC3,
                AudioDecoderPreferences(
                    preferFfmpegTrueHd = true,
                    preferFfmpegDts = true,
                    preferFfmpegDtsHd = true,
                ),
            ),
        )
    }

    @Test
    fun `generic DTS follows either DTS switch because DTS-HD containers can collapse to that MIME`() {
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_DTS,
                AudioDecoderPreferences(preferFfmpegDts = true),
            ),
        )
        assertTrue(
            shouldPreferFfmpegAudio(
                MimeTypes.AUDIO_DTS,
                AudioDecoderPreferences(preferFfmpegDtsHd = true),
            ),
        )
        assertFalse(shouldPreferFfmpegAudio(MimeTypes.AUDIO_DTS, AudioDecoderPreferences()))
    }

    @Test
    fun `hw-with-level wins outright, level-stripped result never consulted`() {
        assertEquals(
            DecoderListChoice.NORMAL,
            chooseDecoderList(normalFirstIsHardware = true, strippedFirstIsHardware = false),
        )
        assertEquals(
            DecoderListChoice.NORMAL,
            chooseDecoderList(normalFirstIsHardware = true, strippedFirstIsHardware = true),
        )
    }

    @Test
    fun `hw-ignoring-level wins when the normal pass led with software but the stripped retry finds hardware`() {
        assertEquals(
            DecoderListChoice.LEVEL_STRIPPED,
            chooseDecoderList(normalFirstIsHardware = false, strippedFirstIsHardware = true),
        )
    }

    @Test
    fun `software is the last resort when neither pass finds a hardware decoder`() {
        assertEquals(
            DecoderListChoice.NORMAL,
            chooseDecoderList(normalFirstIsHardware = false, strippedFirstIsHardware = false),
        )
    }

    @Test
    fun `tolerateMislabeledLevels defaults to true, matching every pre-existing call site's behavior`() {
        assertEquals(
            DecoderListChoice.LEVEL_STRIPPED,
            chooseDecoderList(normalFirstIsHardware = false, strippedFirstIsHardware = true),
        )
    }

    // -- Advanced-settings toggle OFF (`tolerateMislabeledLevels = false`) --

    @Test
    fun `intolerant mode always returns NORMAL, even when the stripped retry would have found hardware`() {
        assertEquals(
            DecoderListChoice.NORMAL,
            chooseDecoderList(
                normalFirstIsHardware = false,
                strippedFirstIsHardware = true,
                tolerateMislabeledLevels = false,
            ),
        )
    }

    @Test
    fun `intolerant mode returns NORMAL regardless of either hardware flag`() {
        for (normalFirstIsHardware in listOf(true, false)) {
            for (strippedFirstIsHardware in listOf(true, false)) {
                assertEquals(
                    "normalFirstIsHardware=$normalFirstIsHardware strippedFirstIsHardware=$strippedFirstIsHardware",
                    DecoderListChoice.NORMAL,
                    chooseDecoderList(normalFirstIsHardware, strippedFirstIsHardware, tolerateMislabeledLevels = false),
                )
            }
        }
    }

    private class RecordingRenderer(trackType: Int) : BaseRenderer(trackType) {
        val rendered = mutableListOf<Pair<Long, Long>>()
        var speed: Pair<Float, Float>? = null
        var mayRenderStart = false

        override fun getName() = "Recording"

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            rendered += positionUs to elapsedRealtimeUs
        }

        override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
            speed = currentPlaybackSpeed to targetPlaybackSpeed
        }

        override fun enableMayRenderStartOfStream() {
            mayRenderStart = true
        }

        override fun isReady() = true

        override fun isEnded() = false

        override fun supportsFormat(format: androidx.media3.common.Format) = C.FORMAT_HANDLED
    }

    @Test
    fun `the text renderer is rendered at the subtitle clock, other calls pass through`() {
        var delayMs = 2_000L
        val inner = RecordingRenderer(C.TRACK_TYPE_TEXT)
        val delayed = DelayedTextRenderer(inner) { delayMs }

        delayed.render(10_000_000L, 77L)
        delayMs = -500L
        delayed.render(10_000_000L, 78L)
        delayed.setPlaybackSpeed(1f, 2f)
        delayed.enableMayRenderStartOfStream()

        assertEquals(listOf(8_000_000L to 77L, 10_500_000L to 78L), inner.rendered)
        assertEquals(1f to 2f, inner.speed)
        assertTrue("default methods are forwarded, not skipped", inner.mayRenderStart)
    }

    @Test
    fun `only text renderers are wrapped`() {
        val text = RecordingRenderer(C.TRACK_TYPE_TEXT)
        val audio = RecordingRenderer(C.TRACK_TYPE_AUDIO)

        val out = withSubtitleDelay(listOf(audio, text)) { 1_000L }

        assertSame(audio, out[0])
        assertTrue(out[1] is DelayedTextRenderer)
        out[1].render(5_000_000L, 0L)
        assertEquals(listOf(4_000_000L to 0L), text.rendered)
    }
}
