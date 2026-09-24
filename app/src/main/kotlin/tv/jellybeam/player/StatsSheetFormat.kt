package tv.jellybeam.player

import java.util.Locale
import tv.jellybeam.ui.detail.DetailFormatting
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlayMethodFfi

/**
 * One styled run of text (docs/12 §17). A flat list of these, concatenated with no separator
 * inserted by the renderer, represents plain text with one or two accented sub-runs.
 */
data class StatsSpan(val text: String, val accent: Boolean = false)

/** One label/value row in the stats sheet's 2-col grid (docs/12 §17). */
data class StatsRow(val label: String, val value: String)

/**
 * The stats sheet's whole content (docs/12 §17), same split as [InfoOverlayFormat]. [rows] drops
 * any field with nothing to show; [fileName] is shown verbatim, release tags and all.
 */
data class StatsSheetContent(
    val headline: List<StatsSpan>,
    val rows: List<StatsRow>,
    val fileName: String?,
)

/** Builds [StatsSheetContent] from [PlaybackOsdDetail] plus the sheet's live/selection state. */
object StatsSheetFormat {
    /**
     * [serverName] is `null` when nobody's signed in or the host doesn't parse, omitting SOURCE.
     * [live] is `null` while the sheet is closed (docs/12 §17: HEALTH polls at 1Hz only while
     * open), omitting HEALTH. [playMethod]/[transcodeReason] (docs/18-playback-quality.md §1/§3)
     * default to Direct Play; [PlaybackScreen] passes live values once a fallback transcodes.
     */
    fun build(
        detail: PlaybackOsdDetail,
        selectedSubtitleTitle: String?,
        subtitleCount: Int,
        serverName: String?,
        live: PlaybackLiveStats?,
        playMethod: PlayMethodFfi = PlayMethodFfi.DIRECT_PLAY,
        transcodeReason: String? = null,
    ): StatsSheetContent {
        val video = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.VIDEO }
        val audio = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO && it.isDefault }
            ?: detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO }

        // docs/18 §3: "Transcoding · <reason>" once playMethod flips; reason is the accented run.
        val headline = if (playMethod == PlayMethodFfi.TRANSCODE) {
            listOf(StatsSpan("Transcoding · "), StatsSpan(transcodeReason ?: "", accent = true))
        } else {
            listOf(StatsSpan("Direct Play · "), StatsSpan("no transcode", accent = true))
        }

        val rows = buildList {
            videoValue(video)?.let { add(StatsRow("VIDEO", it)) }
            audioValue(audio)?.let { add(StatsRow("AUDIO", it)) }
            add(StatsRow("SUBTITLES", subtitlesValue(selectedSubtitleTitle, subtitleCount)))
            containerValue(detail)?.let { add(StatsRow("CONTAINER", it)) }
            serverName?.let { add(StatsRow("SOURCE", it)) }
            live?.let { addAll(liveRows(it)) }
        }

        val fileName = detail.path?.substringAfterLast('/')?.substringAfterLast('\\')

        return StatsSheetContent(headline = headline, rows = rows, fileName = fileName)
    }

    /** `"HEVC · Main 10 · 1920×1080 · 23.976fps · 12.3Mb/s"`; missing pieces drop, `null` if no
     * video stream. */
    private fun videoValue(video: MediaStreamInfo?): String? {
        if (video == null) return null
        val parts = listOfNotNull(
            video.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
            video.profile?.takeIf { it.isNotBlank() },
            resolutionDimensions(video.width, video.height),
            fpsLabel(video.avgFrameRate),
            bitrateLabel(video.bitRate),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** `"EAC3 5.1 · 448kb/s · 48kHz · EN"` -- missing pieces drop; `null` if no audio stream. */
    private fun audioValue(audio: MediaStreamInfo?): String? {
        if (audio == null) return null
        val parts = listOfNotNull(
            codecChannelLabel(audio.codec, audio.channels),
            kbpsLabel(audio.bitRate),
            khzLabel(audio.sampleRate),
            langLabel(audio.language),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Always present -- "Off · N available" or "<title> · N available"; never dropped. */
    private fun subtitlesValue(selectedSubtitleTitle: String?, subtitleCount: Int): String {
        val label = selectedSubtitleTitle?.takeIf { it.isNotBlank() } ?: "Off"
        return "$label · $subtitleCount available"
    }

    /** `"MKV · 1.4 GB"` -- either half may drop; `null` when both absent. Reuses
     * [DetailFormatting]'s container/size formatters, one source of truth. */
    private fun containerValue(detail: PlaybackOsdDetail): String? {
        val parts = listOfNotNull(
            DetailFormatting.containerLabel(detail.container),
            DetailFormatting.formatFileSize(detail.sizeBytes),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Split so buffer allocation and network estimate don't collapse into one HEALTH summary. */
    internal fun liveRows(live: PlaybackLiveStats): List<StatsRow> = listOf(
        StatsRow("BUFFER", bufferValue(live)),
        StatsRow("NETWORK", networkValue(live)),
        StatsRow("HEALTH", healthValue(live)),
    )

    internal fun bufferValue(live: PlaybackLiveStats): String = String.format(
        Locale.US,
        "%.1fs ahead · %.1f MiB",
        live.bufferedAheadMs.coerceAtLeast(0L) / 1_000.0,
        live.allocatedBufferBytes.coerceAtLeast(0L) / (1024.0 * 1024.0),
    )

    internal fun networkValue(live: PlaybackLiveStats): String =
        live.bandwidthBytesPerSecond.takeIf { it > 0L }?.let {
            String.format(Locale.US, "%.2f Mbit/s", it * 8.0 / 1_000_000.0)
        } ?: "—"

    internal fun healthValue(live: PlaybackLiveStats): String =
        "${live.state.displayName} · ${live.droppedFrames.coerceAtLeast(0L)} dropped"

    /** `"{height}p"` from [height], falling back to a width-based ladder estimate when absent;
     * `null` when neither resolves. */
    private fun resolutionDimensions(width: Int?, height: Int?): String? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 }
        if (w != null && h != null) return "${w}×${h}"
        val resolvedHeight = h ?: heightFromWidthFallback(w) ?: return null
        return "${resolvedHeight}p"
    }

    /** A coarse width -> height ladder (16:9-ish), consulted only when the server omits height. */
    private fun heightFromWidthFallback(width: Int?): Int? {
        val w = width?.takeIf { it > 0 } ?: return null
        return when {
            w >= 3800 -> 2160
            w >= 2550 -> 1440
            w >= 1800 -> 1080
            w >= 1200 -> 720
            w >= 700 -> 480
            else -> 360
        }
    }

    /** `"%.3f"` with trailing zeros/dot trimmed, then `"fps"` appended -- `"23.976fps"`, `"24fps"`.
     * `null` for a non-positive/absent rate. */
    private fun fpsLabel(fps: Float?): String? {
        val value = fps?.takeIf { it > 0f } ?: return null
        val formatted = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
        return "${formatted}fps"
    }

    /** `"%.1fMb/s"` from a bits/sec rate. `null` for a non-positive/absent rate. */
    private fun bitrateLabel(bitsPerSecond: Int?): String? {
        val value = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return String.format(Locale.US, "%.1fMb/s", value / 1_000_000.0)
    }

    /** `"AC3 5.1"` -- codec uppercased plus a channel-count label; `null` if there's no codec. */
    private fun codecChannelLabel(codec: String?, channels: Int?): String? {
        val name = codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: return null
        val channelPart = channels?.let(::channelLabel)
        return if (channelPart != null) "$name $channelPart" else name
    }

    /** `"5.1"`/`"7.1"`/`"Stereo"`/`"Mono"`, else `"N ch"`; duplicated in [InfoOverlayFormat] --
     * keep both in sync. */
    private fun channelLabel(channels: Int): String = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$channels ch"
    }

    /** `"448kb/s"` from a bits/sec rate. `null` for a non-positive/absent rate. */
    private fun kbpsLabel(bitsPerSecond: Int?): String? {
        val value = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return "${Math.round(value / 1_000.0)}kb/s"
    }

    /** `"48kHz"`, or `"44.1kHz"` (one decimal, only when non-integral), from a sample rate in Hz.
     * `null` for a non-positive/absent rate. */
    private fun khzLabel(sampleRateHz: Int?): String? {
        val hz = sampleRateHz?.takeIf { it > 0 } ?: return null
        val khz = hz / 1_000.0
        val rounded = Math.round(khz)
        val formatted = if (khz == rounded.toDouble()) {
            rounded.toString()
        } else {
            String.format(Locale.US, "%.1f", khz)
        }
        return "${formatted}kHz"
    }

    /** Language code, uppercased and capped to 3 chars -- `"EN"`, `"SPA"`; `null` if absent. */
    private fun langLabel(language: String?): String? =
        language?.takeIf { it.isNotBlank() }?.take(3)?.uppercase(Locale.US)
}
