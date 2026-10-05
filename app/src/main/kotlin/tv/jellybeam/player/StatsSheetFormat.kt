package tv.jellybeam.player

import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
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
        strings: UiStrings,
        detail: PlaybackOsdDetail,
        selectedSubtitleTitle: String?,
        subtitleCount: Int,
        serverName: String?,
        live: PlaybackLiveStats?,
        playMethod: PlayMethodFfi = PlayMethodFfi.DIRECT_PLAY,
        transcodeReason: String? = null,
        locale: Locale = AppLocale.format,
    ): StatsSheetContent {
        val video = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.VIDEO }
        val audio = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO && it.isDefault }
            ?: detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO }

        // docs/18 §3: "Transcoding · <reason>" once playMethod flips; reason is the accented run.
        val headline = if (playMethod == PlayMethodFfi.TRANSCODE) {
            listOf(StatsSpan(strings.get(R.string.player_stats_transcoding) + " · "), StatsSpan(transcodeReason ?: "", accent = true))
        } else {
            listOf(StatsSpan(strings.get(R.string.player_stats_direct_play) + " · "), StatsSpan(strings.get(R.string.player_stats_no_transcode), accent = true))
        }

        val rows = buildList {
            videoValue(strings, video, locale)?.let { add(StatsRow(label(strings, R.string.player_stats_label_video), it)) }
            audioValue(strings, audio, locale)?.let { add(StatsRow(label(strings, R.string.player_stats_label_audio), it)) }
            add(StatsRow(label(strings, R.string.player_stats_label_subtitles), subtitlesValue(strings, selectedSubtitleTitle, subtitleCount)))
            containerValue(detail)?.let { add(StatsRow(label(strings, R.string.player_stats_label_container), it)) }
            serverName?.let { add(StatsRow(label(strings, R.string.player_stats_label_source), it)) }
            live?.let { addAll(liveRows(strings, it)) }
        }

        val fileName = detail.path?.substringAfterLast('/')?.substringAfterLast('\\')

        return StatsSheetContent(headline = headline, rows = rows, fileName = fileName)
    }

    /** Row labels are all-caps in the sheet, cased by the language they are written in. */
    private fun label(strings: UiStrings, id: Int): String = strings.get(id).uppercaseUi()

    /** `"HEVC · Main 10 · 1920×1080 · 23.976fps · 12.3Mb/s"`; missing pieces drop, `null` if no
     * video stream. */
    private fun videoValue(strings: UiStrings, video: MediaStreamInfo?, locale: Locale): String? {
        if (video == null) return null
        val parts = listOfNotNull(
            video.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
            video.profile?.takeIf { it.isNotBlank() },
            resolutionDimensions(video.width, video.height),
            fpsLabel(strings, video.avgFrameRate, locale),
            bitrateLabel(strings, video.bitRate),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** `"EAC3 5.1 · 448kb/s · 48kHz · EN"` -- missing pieces drop; `null` if no audio stream. */
    private fun audioValue(strings: UiStrings, audio: MediaStreamInfo?, locale: Locale): String? {
        if (audio == null) return null
        val parts = listOfNotNull(
            codecChannelLabel(strings, audio.codec, audio.channels),
            kbpsLabel(strings, audio.bitRate),
            khzLabel(strings, audio.sampleRate, locale),
            langLabel(audio.language),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Always present -- "Off · N available" or "<title> · N available"; never dropped. */
    private fun subtitlesValue(strings: UiStrings, selectedSubtitleTitle: String?, subtitleCount: Int): String {
        val label = selectedSubtitleTitle?.takeIf { it.isNotBlank() } ?: strings.get(R.string.player_track_off)
        return strings.plural(R.plurals.player_stats_subtitles_value, subtitleCount, label, subtitleCount)
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
    internal fun liveRows(strings: UiStrings, live: PlaybackLiveStats): List<StatsRow> = listOf(
        StatsRow(label(strings, R.string.player_stats_label_buffer), bufferValue(strings, live)),
        StatsRow(label(strings, R.string.player_stats_label_network), networkValue(strings, live)),
        StatsRow(label(strings, R.string.player_stats_label_health), healthValue(strings, live)),
    )

    internal fun bufferValue(strings: UiStrings, live: PlaybackLiveStats): String = strings.get(
        R.string.player_stats_buffer_value,
        live.bufferedAheadMs.coerceAtLeast(0L) / 1_000.0,
        live.allocatedBufferBytes.coerceAtLeast(0L) / (1024.0 * 1024.0),
    )

    internal fun networkValue(strings: UiStrings, live: PlaybackLiveStats): String =
        live.bandwidthBytesPerSecond.takeIf { it > 0L }?.let {
            strings.get(R.string.player_stats_network_value, it * 8.0 / 1_000_000.0)
        } ?: "—"

    internal fun healthValue(strings: UiStrings, live: PlaybackLiveStats): String =
        live.droppedFrames.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt().let { dropped ->
            strings.plural(R.plurals.player_stats_health_value, dropped, strings.get(live.state.labelRes), dropped)
        }

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
    private fun fpsLabel(strings: UiStrings, fps: Float?, locale: Locale): String? {
        val value = fps?.takeIf { it > 0f } ?: return null
        val formatted = String.format(locale, "%.3f", value).trimEnd('0').trimEnd('.', ',')
        return strings.get(R.string.player_stats_fps, formatted)
    }

    /** `"%.1fMb/s"` from a bits/sec rate. `null` for a non-positive/absent rate. */
    private fun bitrateLabel(strings: UiStrings, bitsPerSecond: Int?): String? {
        val value = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return strings.get(R.string.player_stats_video_bitrate, value / 1_000_000.0)
    }

    /** `"AC3 5.1"` -- codec uppercased plus a channel-count label; `null` if there's no codec. */
    private fun codecChannelLabel(strings: UiStrings, codec: String?, channels: Int?): String? {
        val name = codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: return null
        val channelPart = channels?.let { channelLabel(strings, it) }
        return if (channelPart != null) "$name $channelPart" else name
    }

    /** `"5.1"`/`"7.1"`/`"Stereo"`/`"Mono"`, else `"N ch"`; also the OSD chip's mapping. */
    internal fun channelLabel(strings: UiStrings, channels: Int): String = when (channels) {
        1 -> strings.get(R.string.player_channels_mono)
        2 -> strings.get(R.string.player_channels_stereo)
        6 -> "5.1"
        8 -> "7.1"
        else -> strings.get(R.string.player_channels_count, channels)
    }

    /** `"448kb/s"` from a bits/sec rate. `null` for a non-positive/absent rate. */
    private fun kbpsLabel(strings: UiStrings, bitsPerSecond: Int?): String? {
        val value = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return strings.get(R.string.player_stats_audio_bitrate, Math.round(value / 1_000.0))
    }

    /** `"48kHz"`, or `"44.1kHz"` (one decimal, only when non-integral), from a sample rate in Hz.
     * `null` for a non-positive/absent rate. */
    private fun khzLabel(strings: UiStrings, sampleRateHz: Int?, locale: Locale): String? {
        val hz = sampleRateHz?.takeIf { it > 0 } ?: return null
        val khz = hz / 1_000.0
        val rounded = Math.round(khz)
        val formatted = if (khz == rounded.toDouble()) {
            rounded.toString()
        } else {
            String.format(locale, "%.1f", khz)
        }
        return strings.get(R.string.player_stats_sample_rate, formatted)
    }

    /** Language code, uppercased and capped to 3 chars -- `"EN"`, `"SPA"`; `null` if absent. */
    private fun langLabel(language: String?): String? =
        language?.takeIf { it.isNotBlank() }?.take(3)?.uppercase(Locale.US)
}
