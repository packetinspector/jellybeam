package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.MimeTypes

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
}
