package tv.jellybeam.player

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM unit tests for [SoftwareDecoder] (docs/18-playback-quality.md §3): covers its
 * two-source verdict (a probed-set hit, the name-heuristic fallback, and a hardware-name miss),
 * not the real `MediaCodecList` enumeration ([DeviceCapsProbeTest] covers that). The probed set
 * is process-wide (`@Volatile`), so [resetProbedSet] clears it before and after every test.
 */
class SoftwareDecoderTest {
    @Before
    fun resetProbedSet() {
        SoftwareDecoder.rememberProbedSoftwareDecoders(emptySet())
    }

    @After
    fun clearProbedSet() {
        SoftwareDecoder.rememberProbedSoftwareDecoders(emptySet())
    }

    // -- probed-set hit (DeviceCapsProbe's own published names) ------------

    @Test
    fun `a name published by rememberProbedSoftwareDecoders is software, even with no heuristic match`() {
        SoftwareDecoder.rememberProbedSoftwareDecoders(setOf("c2.qti.avc.decoder.low-latency"))
        assertTrue(SoftwareDecoder.isSoftwareOnly("c2.qti.avc.decoder.low-latency"))
    }

    @Test
    fun `a probed set that does not contain the name falls through to the heuristic`() {
        SoftwareDecoder.rememberProbedSoftwareDecoders(setOf("some.other.decoder"))
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.google.h264.decoder"))
        assertFalse(SoftwareDecoder.isSoftwareOnly("OMX.qcom.video.decoder.avc"))
    }

    // -- name-heuristic hit (no probed set at all) --------------------------

    @Test
    fun `OMX-google prefix is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.google.h264.decoder"))
    }

    @Test
    fun `c2-android prefix is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("c2.android.avc.decoder"))
    }

    @Test
    fun `OMX-ffmpeg prefix is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.ffmpeg.video.decoder"))
    }

    @Test
    fun `c2-ffmpeg prefix is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("c2.ffmpeg.video.decoder"))
    }

    @Test
    fun `a dot-sw-dot substring is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.oem.sw.avc.decoder"))
    }

    @Test
    fun `a dot-SW-dot substring is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.oem.SW.avc.decoder"))
    }

    @Test
    fun `a dot-soft-dot substring is software`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.oem.soft.avc.decoder"))
    }

    // -- hardware miss (neither source claims the name) ---------------------

    @Test
    fun `an Amlogic-style hardware name is not software`() {
        assertFalse(SoftwareDecoder.isSoftwareOnly("OMX.amlogic.avc.decoder.awesome"))
    }

    @Test
    fun `a Qualcomm-style hardware name is not software`() {
        assertFalse(SoftwareDecoder.isSoftwareOnly("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun `an empty probed set still falls back to the name heuristic`() {
        assertTrue(SoftwareDecoder.isSoftwareOnly("OMX.google.h264.decoder"))
        assertFalse(SoftwareDecoder.isSoftwareOnly("OMX.qcom.video.decoder.avc"))
    }
}
