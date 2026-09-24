package tv.jellybeam.player

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.VideoCodecId

/**
 * Exercises the pure [mapCodecInfo] mapping layer with fixture ints, not real `MediaCodecList`
 * enumeration (needs a device/emulator; [DeviceCapsProbe.probe] is exercised only for its
 * fail-open contract below). Expected values are the profile's own codec tables
 * (docs/05 "Device profile"), used here as the oracle.
 */
class DeviceCapsProbeTest {

    @Test
    fun `absent codec maps to null`() {
        assertNull(mapCodecInfo(VideoCodecId.H264, ProbedCodecInfo(present = false)))
    }

    @Test
    fun `AVC main only -- no high10 -- yields baseline profile allowlist and main level`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel41),
            ),
            maxWidth = 1920,
            maxHeight = 1080,
        )

        val caps = mapCodecInfo(VideoCodecId.H264, info)

        assertEquals(VideoCodecId.H264, caps?.codec)
        assertEquals(listOf("high", "main", "baseline", "constrained baseline"), caps?.profiles)
        assertNull(caps?.maxLevel)
        assertEquals(mapOf("main" to 41), caps?.profileLevels?.associate { it.profile to it.maxLevel })
        assertEquals(1920u, caps?.maxWidth)
        assertEquals(1080u, caps?.maxHeight)
        assertEquals(emptyList<String>(), caps?.unsupportedVideoRanges)
    }

    @Test
    fun `AVC with High10 at level 4+ adds the high10 profile string`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel31),
                ProfileLevel(CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCLevel4),
            ),
        )

        val caps = mapCodecInfo(VideoCodecId.H264, info)

        assertEquals(listOf("high", "main", "baseline", "constrained baseline", "high 10"), caps?.profiles)
        assertEquals(mapOf("main" to 31, "high 10" to 40), caps?.profileLevels?.associate { it.profile to it.maxLevel })
    }

    @Test
    fun `AVC High10 below level 4 does not count as supported`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCLevel31),
            ),
        )

        val caps = mapCodecInfo(VideoCodecId.H264, info)

        assertEquals(listOf("high", "main", "baseline", "constrained baseline"), caps?.profiles)
        assertNull(caps?.maxLevel)
        assertEquals(mapOf("high 10" to 31), caps?.profileLevels?.associate { it.profile to it.maxLevel })
    }

    @Test
    fun `AVC level 1b's raw int outranks level 1's, so the reversed-table scan resolves it to 10`() {
        // AVCLevel1b's raw platform int (2) is numerically larger than AVCLevel1's (1); the
        // reversed-table scan (`asReversed().find { rawLevel >= item.first }`, ported verbatim
        // from the fork) resolves rawLevel 2 to 10 rather than 9. Inert in practice, kept as-is
        // to match the fork's table/algorithm.
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(ProfileLevel(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel1b)),
        )

        assertEquals(10, mapCodecInfo(VideoCodecId.H264, info)?.profileLevels?.single()?.maxLevel)
    }

    @Test
    fun `HEVC main only yields a single-entry profile list and main level, no HDR support means broad exclude-set`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel41),
            ),
            maxWidth = 3840,
            maxHeight = 2160,
        )

        val caps = mapCodecInfo(VideoCodecId.HEVC, info)

        assertEquals(listOf("main"), caps?.profiles)
        assertEquals(mapOf("main" to 123), caps?.profileLevels?.associate { it.profile to it.maxLevel })
        assertEquals(3840u, caps?.maxWidth)
        assertEquals(2160u, caps?.maxHeight)
        // No DV/HDR10/HDR10+ decoder: every DV/HDR range is excluded.
        assertEquals(
            setOf("DOVIInvalid", "DOVIWithEL", "DOVIWithELHDR10Plus", "DOVI", "DOVIWithHDR10", "DOVIWithHDR10Plus", "HDR10Plus", "HDR10"),
            caps?.unsupportedVideoRanges?.toSet(),
        )
    }

    @Test
    fun `HEVC main10 fixture reports main and main 10 profiles with the main10 level table`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel4),
                ProfileLevel(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel51),
            ),
        )

        val caps = mapCodecInfo(VideoCodecId.HEVC, info)

        assertEquals(listOf("main", "main 10"), caps?.profiles)
        assertEquals(mapOf("main" to 120, "main 10" to 153), caps?.profileLevels?.associate { it.profile to it.maxLevel })
    }

    @Test
    fun `HEVC with HDR10 and HDR10Plus decoder support narrows the exclude-set`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(
                ProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel4),
                ProfileLevel(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel4),
                ProfileLevel(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCMainTierLevel4),
                ProfileLevel(CodecProfileLevel.HEVCProfileMain10HDR10Plus, CodecProfileLevel.HEVCMainTierLevel4),
            ),
        )

        val caps = mapCodecInfo(VideoCodecId.HEVC, info)

        // DOVI branch still excluded (no DV decoder); HDR10/HDR10+ and their DOVIWith* combos are
        // not.
        assertEquals(
            setOf("DOVIInvalid", "DOVIWithEL", "DOVI"),
            caps?.unsupportedVideoRanges?.toSet(),
        )
    }

    @Test
    fun `HEVC with a Dolby Vision decoder drops the base DOVI exclusions`() {
        val info = ProbedCodecInfo(
            present = true,
            profileLevels = listOf(ProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel4)),
            dolbyVisionPresent = true,
        )

        val ranges = mapCodecInfo(VideoCodecId.HEVC, info)?.unsupportedVideoRanges.orEmpty().toSet()

        // dolbyVisionPresent=true drops the DOVI/DOVIWithHDR10/DOVIWithHDR10Plus branch even
        // though HDR10/HDR10+ decoder support is still absent.
        assertEquals(false, ranges.contains("DOVI"))
        assertEquals(false, ranges.contains("DOVIWithHDR10"))
        assertEquals(false, ranges.contains("DOVIWithHDR10Plus"))
        // Plain HDR10/HDR10+ exclusion and the EL branch (no multi-instance decoder) remain.
        assertEquals(true, ranges.contains("HDR10"))
        assertEquals(true, ranges.contains("DOVIWithEL"))
    }

    @Test
    fun `AV1 is presence-gated only -- no profile allowlist even with a decoder`() {
        val info = ProbedCodecInfo(present = true, maxWidth = 3840, maxHeight = 2160)

        val caps = mapCodecInfo(VideoCodecId.AV1, info)

        assertEquals(VideoCodecId.AV1, caps?.codec)
        assertEquals(emptyList<String>(), caps?.profiles)
        assertNull(caps?.maxLevel)
        assertEquals(3840u, caps?.maxWidth)
    }

    @Test
    fun `VP8, VP9, MPEG2 and VC1 are presence and resolution only, no profiles or ranges`() {
        for (codec in listOf(VideoCodecId.VP8, VideoCodecId.VP9, VideoCodecId.MPEG2_VIDEO, VideoCodecId.VC1)) {
            val caps = mapCodecInfo(codec, ProbedCodecInfo(present = true, maxWidth = 1920, maxHeight = 1080))
            assertEquals(codec, caps?.codec)
            assertEquals(emptyList<String>(), caps?.profiles)
            assertNull(caps?.maxLevel)
            assertEquals(emptyList<String>(), caps?.unsupportedVideoRanges)
            assertEquals(1920u, caps?.maxWidth)
        }
    }

    @Test
    fun `no resolution data means null maxWidth and maxHeight, not zero`() {
        val caps = mapCodecInfo(VideoCodecId.H264, ProbedCodecInfo(present = true))
        assertNull(caps?.maxWidth)
        assertNull(caps?.maxHeight)
    }

    @Test
    fun `probe fails open to an empty DeviceCaps if enumeration throws`() {
        // No real MediaCodecList here; android.media stubs return defaults or throw, but
        // probe() must never propagate and always returns a usable (if empty) DeviceCaps.
        val caps = DeviceCapsProbe.probe()

        assertNull(caps.maxStreamingBitrate)
        assertEquals(emptyList<Any>(), caps.video)
    }
}
