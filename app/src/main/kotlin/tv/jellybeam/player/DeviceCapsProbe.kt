package tv.jellybeam.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import uniffi.jellybeam_core.DeviceCaps
import uniffi.jellybeam_core.ProfileLevelCaps
import uniffi.jellybeam_core.VideoCaps
import uniffi.jellybeam_core.VideoCodecId

private const val TAG = "JellybeamTV"

// Mime strings MediaCodecList reports decoders against -- literal values (not
// androidx.media3.common.MimeTypes/android.media.MediaFormat constants) so this
// file reads correctly regardless of SDK level.
private const val MIME_AVC = "video/avc"
private const val MIME_HEVC = "video/hevc"
private const val MIME_AV1 = "video/av01"
private const val MIME_VP8 = "video/x-vnd.on2.vp8"
private const val MIME_VP9 = "video/x-vnd.on2.vp9"
private const val MIME_MPEG2 = "video/mpeg2"
private const val MIME_VC1 = "video/wvc1"
private const val MIME_DOLBY_VISION = "video/dolby-vision"

/** One (profile, level) pair a probed `MediaCodec` decoder reports, as plain Ints so [mapCodecInfo]
 * is unit-testable with fixture values; only enumeration itself needs a real device.
 */
data class ProfileLevel(val profile: Int, val level: Int)

/** Everything [mapCodecInfo] needs for one codec, gathered by [DeviceCapsProbe.probe]; plain data
 * so the mapping logic is testable with fixture ints.
 */
data class ProbedCodecInfo(
    val present: Boolean,
    val profileLevels: List<ProfileLevel> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    /** HEVC/AV1 only: decoder presence + profile/level pairs on the shared `video/dolby-vision`
     * mime.
     */
    val dolbyVisionPresent: Boolean = false,
    val dolbyVisionProfileLevels: List<ProfileLevel> = emptyList(),
    /** HEVC only: whether an HEVC decoder supports more than one concurrent instance (needed to
     * decode Dolby Vision profile 7's dual-layer stream).
     */
    val hevcMultiInstance: Boolean = false,
)

// AVC levels as reported by ffprobe, multiplied by 10 (level 4.1 is 41; 1b is 9).
private val AVC_LEVELS = listOf(
    CodecProfileLevel.AVCLevel1b to 9,
    CodecProfileLevel.AVCLevel1 to 10,
    CodecProfileLevel.AVCLevel11 to 11,
    CodecProfileLevel.AVCLevel12 to 12,
    CodecProfileLevel.AVCLevel13 to 13,
    CodecProfileLevel.AVCLevel2 to 20,
    CodecProfileLevel.AVCLevel21 to 21,
    CodecProfileLevel.AVCLevel22 to 22,
    CodecProfileLevel.AVCLevel3 to 30,
    CodecProfileLevel.AVCLevel31 to 31,
    CodecProfileLevel.AVCLevel32 to 32,
    CodecProfileLevel.AVCLevel4 to 40,
    CodecProfileLevel.AVCLevel41 to 41,
    CodecProfileLevel.AVCLevel42 to 42,
    CodecProfileLevel.AVCLevel5 to 50,
    CodecProfileLevel.AVCLevel51 to 51,
    CodecProfileLevel.AVCLevel52 to 52,
)

// HEVC levels as reported by ffprobe, multiplied by 30 (level 4.1 is 123).
private val HEVC_LEVELS = listOf(
    CodecProfileLevel.HEVCMainTierLevel1 to 30,
    CodecProfileLevel.HEVCMainTierLevel2 to 60,
    CodecProfileLevel.HEVCMainTierLevel21 to 63,
    CodecProfileLevel.HEVCMainTierLevel3 to 90,
    CodecProfileLevel.HEVCMainTierLevel31 to 93,
    CodecProfileLevel.HEVCMainTierLevel4 to 120,
    CodecProfileLevel.HEVCMainTierLevel41 to 123,
    CodecProfileLevel.HEVCMainTierLevel5 to 150,
    CodecProfileLevel.HEVCMainTierLevel51 to 153,
    CodecProfileLevel.HEVCMainTierLevel52 to 156,
    CodecProfileLevel.HEVCMainTierLevel6 to 180,
    CodecProfileLevel.HEVCMainTierLevel61 to 183,
    CodecProfileLevel.HEVCMainTierLevel62 to 186,
)

private fun maxRawLevelForProfile(profileLevels: List<ProfileLevel>, profile: Int): Int =
    profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level } ?: 0

/** Highest table entry whose raw level the device meets or exceeds, or `null` if the raw level is
 * 0.
 */
private fun jellyfinLevel(table: List<Pair<Int, Int>>, rawLevel: Int): Int? {
    if (rawLevel <= 0) return null
    return table.asReversed().firstOrNull { rawLevel >= it.first }?.second
}

/** True if some probed profile/level pair matches `profile` at `level` or higher. */
private fun hasProfileAtLevel(profileLevels: List<ProfileLevel>, profile: Int, level: Int): Boolean =
    profileLevels.any { it.profile == profile && it.level >= level }

/**
 * Maps one probed codec's [ProbedCodecInfo] to the [VideoCaps] shape `setDeviceCaps` sends over
 * FFI, or `null` if [ProbedCodecInfo.present] is false. H264 and HEVC get real profile/level
 * ceilings plus, for HEVC, an HDR/Dolby-Vision exclude-set (docs/05 "Device profile"); AV1
 * gets only the same HDR exclude-set, left unconstrained by profile.
 */
fun mapCodecInfo(codec: VideoCodecId, info: ProbedCodecInfo): VideoCaps? {
    if (!info.present) return null
    return when (codec) {
        VideoCodecId.H264 -> mapAvc(info)
        VideoCodecId.HEVC -> mapHevc(info)
        VideoCodecId.AV1 -> mapAv1(info)
        VideoCodecId.VP8, VideoCodecId.VP9, VideoCodecId.MPEG2_VIDEO, VideoCodecId.VC1 ->
            VideoCaps(
                codec = codec,
                profiles = emptyList(),
                maxLevel = null,
                profileLevels = emptyList(),
                maxWidth = info.maxWidth?.toUInt(),
                maxHeight = info.maxHeight?.toUInt(),
                unsupportedVideoRanges = emptyList(),
            )
    }
}

// baseline/main/high always advertised once an AVC decoder exists, plus "high 10" iff supported.
private fun mapAvc(info: ProbedCodecInfo): VideoCaps {
    val supportsHigh10 = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.AVCProfileHigh10,
        CodecProfileLevel.AVCLevel4,
    )
    val profiles = listOfNotNull(
        "high",
        "main",
        "baseline",
        "constrained baseline",
        if (supportsHigh10) "high 10" else null,
    )
    val profileLevels = listOfNotNull(
        jellyfinLevel(AVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.AVCProfileBaseline))?.let { ProfileLevelCaps("baseline", it) },
        jellyfinLevel(AVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.AVCProfileMain))?.let { ProfileLevelCaps("main", it) },
        jellyfinLevel(AVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.AVCProfileHigh))?.let { ProfileLevelCaps("high", it) },
        jellyfinLevel(AVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.AVCProfileHigh10))?.let { ProfileLevelCaps("high 10", it) },
    )
    return VideoCaps(
        codec = VideoCodecId.H264,
        profiles = profiles,
        maxLevel = null,
        profileLevels = profileLevels,
        maxWidth = info.maxWidth?.toUInt(),
        maxHeight = info.maxHeight?.toUInt(),
        unsupportedVideoRanges = emptyList(),
    )
}

// "main" always, +"main 10" iff supported, plus the nested HDR/DV exclude-set below.
private fun mapHevc(info: ProbedCodecInfo): VideoCaps {
    val supportsMain10 = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.HEVCProfileMain10,
        CodecProfileLevel.HEVCMainTierLevel4,
    )
    val profiles = listOfNotNull("main", if (supportsMain10) "main 10" else null)
    val profileLevels = listOfNotNull(
        jellyfinLevel(HEVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.HEVCProfileMain))?.let { ProfileLevelCaps("main", it) },
        jellyfinLevel(HEVC_LEVELS, maxRawLevelForProfile(info.profileLevels, CodecProfileLevel.HEVCProfileMain10))?.let { ProfileLevelCaps("main 10", it) },
    )

    val supportsDolbyVision = info.dolbyVisionPresent
    val supportsDolbyVisionEl = info.hevcMultiInstance &&
        hasProfileAtLevel(
            info.dolbyVisionProfileLevels,
            CodecProfileLevel.DolbyVisionProfileDvheDtb,
            CodecProfileLevel.DolbyVisionLevelHd24,
        )
    val supportsHdr10 = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCMainTierLevel4,
    )
    val supportsHdr10Plus = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        CodecProfileLevel.HEVCMainTierLevel4,
    )

    val unsupportedRanges = buildSet {
        add("DOVIInvalid")
        if (!supportsDolbyVisionEl) {
            add("DOVIWithEL")
            if (!supportsHdr10Plus) add("DOVIWithELHDR10Plus")
            if (!supportsDolbyVision) {
                add("DOVI")
                if (!supportsHdr10) add("DOVIWithHDR10")
                if (!supportsHdr10Plus) add("DOVIWithHDR10Plus")
            }
        }
        if (!supportsHdr10Plus) {
            add("HDR10Plus")
            if (!supportsHdr10) add("HDR10")
        }
    }

    return VideoCaps(
        codec = VideoCodecId.HEVC,
        profiles = profiles,
        maxLevel = null,
        profileLevels = profileLevels,
        maxWidth = info.maxWidth?.toUInt(),
        maxHeight = info.maxHeight?.toUInt(),
        unsupportedVideoRanges = unsupportedRanges.toList(),
    )
}

// AV1 is presence-gated only, but gets the same HDR/DV exclude-set treatment as HEVC.
private fun mapAv1(info: ProbedCodecInfo): VideoCaps {
    val supportsDolbyVision = hasProfileAtLevel(
        info.dolbyVisionProfileLevels,
        CodecProfileLevel.DolbyVisionProfileDvav110,
        CodecProfileLevel.DolbyVisionLevelHd24,
    )
    val supportsHdr10 = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.AV1ProfileMain10HDR10,
        CodecProfileLevel.AV1Level5,
    )
    val supportsHdr10Plus = hasProfileAtLevel(
        info.profileLevels,
        CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        CodecProfileLevel.AV1Level5,
    )

    val unsupportedRanges = buildSet {
        add("DOVIInvalid")
        if (!supportsDolbyVision) {
            add("DOVI")
            if (!supportsHdr10) add("DOVIWithHDR10")
            if (!supportsHdr10Plus) add("DOVIWithHDR10Plus")
        }
        if (!supportsHdr10Plus) {
            add("HDR10Plus")
            if (!supportsHdr10) add("HDR10")
        }
    }

    return VideoCaps(
        codec = VideoCodecId.AV1,
        profiles = emptyList(),
        maxLevel = null,
        profileLevels = emptyList(),
        maxWidth = info.maxWidth?.toUInt(),
        maxHeight = info.maxHeight?.toUInt(),
        unsupportedVideoRanges = unsupportedRanges.toList(),
    )
}

/**
 * Probes this device's `MediaCodec` video decoders into the [DeviceCaps] shape
 * `JellybeamCoreInterface.setDeviceCaps` expects; run once, off the main thread, before playback.
 */
object DeviceCapsProbe {
    /** Fails open: swallows any exception, returning an empty `video` list so
     * `jellyfin-core::android_tv_profile` falls back to its conservative floor.
     */
    fun probe(): DeviceCaps = try {
        probeUnsafe()
    } catch (e: Exception) {
        Log.w(TAG, "device caps probe failed; jellyfin-core's conservative floor applies", e)
        DeviceCaps(maxStreamingBitrate = null, video = emptyList())
    }

    private fun probeUnsafe(): DeviceCaps {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }

        fun hasMime(mime: String): Boolean =
            decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

        fun profileLevelsFor(mime: String): List<ProfileLevel> = buildList {
            for (info in decoders) {
                try {
                    val caps = info.getCapabilitiesForType(mime) ?: continue
                    for (pl in caps.profileLevels) add(ProfileLevel(pl.profile, pl.level))
                } catch (_: IllegalArgumentException) {
                    // This decoder doesn't actually support `mime` -- skip it.
                }
            }
        }

        fun maxResolutionFor(mime: String): Pair<Int, Int>? {
            var maxWidth = 0
            var maxHeight = 0
            for (info in decoders) {
                try {
                    val videoCaps = info.getCapabilitiesForType(mime)?.videoCapabilities ?: continue
                    val width = videoCaps.supportedWidths?.upper ?: continue
                    val height = videoCaps.supportedHeights?.upper ?: continue
                    maxWidth = maxOf(maxWidth, width)
                    maxHeight = maxOf(maxHeight, height)
                } catch (_: IllegalArgumentException) {
                    // This decoder doesn't actually support `mime` -- skip it.
                }
            }
            return if (maxWidth > 0 && maxHeight > 0) maxWidth to maxHeight else null
        }

        fun supportsMultiInstance(mime: String): Boolean = decoders.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                try {
                    info.getCapabilitiesForType(mime).maxSupportedInstances > 1
                } catch (_: IllegalArgumentException) {
                    false
                }
        }

        fun codecInfo(mime: String): ProbedCodecInfo {
            if (!hasMime(mime)) return ProbedCodecInfo(present = false)
            val resolution = maxResolutionFor(mime)
            return ProbedCodecInfo(
                present = true,
                profileLevels = profileLevelsFor(mime),
                maxWidth = resolution?.first,
                maxHeight = resolution?.second,
            )
        }

        // `video/dolby-vision` is a shared mime for both HEVC- and AV1-based Dolby Vision streams
        // --
        // probed once (API 24+) and fanned into both codecs' ProbedCodecInfo below.
        val dolbyVisionPresent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hasMime(MIME_DOLBY_VISION)
        val dolbyVisionProfileLevels = if (dolbyVisionPresent) profileLevelsFor(MIME_DOLBY_VISION) else emptyList()
        val hevcMultiInstance = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && supportsMultiInstance(MIME_HEVC)

        val videoCaps = listOf(
            VideoCodecId.H264 to codecInfo(MIME_AVC),
            VideoCodecId.HEVC to codecInfo(MIME_HEVC).copy(
                dolbyVisionPresent = dolbyVisionPresent,
                dolbyVisionProfileLevels = dolbyVisionProfileLevels,
                hevcMultiInstance = hevcMultiInstance,
            ),
            VideoCodecId.AV1 to codecInfo(MIME_AV1).copy(dolbyVisionProfileLevels = dolbyVisionProfileLevels),
            VideoCodecId.VP8 to codecInfo(MIME_VP8),
            VideoCodecId.VP9 to codecInfo(MIME_VP9),
            VideoCodecId.MPEG2_VIDEO to codecInfo(MIME_MPEG2),
            VideoCodecId.VC1 to codecInfo(MIME_VC1),
        ).mapNotNull { (codec, info) -> mapCodecInfo(codec, info) }

        val hevcProfiles = videoCaps.firstOrNull { it.codec == VideoCodecId.HEVC }?.profiles.orEmpty()
        Log.i(TAG, "device caps probe: codecs=${videoCaps.map { it.codec }} hevcProfiles=$hevcProfiles")

        // docs/18-playback-quality.md §3: reuses this enumeration to publish software-only decoder
        // names for SoftwareDecoder's hot path. Deliberately last: if anything above throws,
        // probe()'s fail-open catch means SoftwareDecoder keeps whatever set it already had.
        val softwareDecoderNames = decoders
            .filter { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    SoftwareDecoder.matchesNameHeuristic(info.name)
                }
            }
            .map { it.name }
            .toSet()
        SoftwareDecoder.rememberProbedSoftwareDecoders(softwareDecoderNames)

        return DeviceCaps(maxStreamingBitrate = null, video = videoCaps)
    }
}
