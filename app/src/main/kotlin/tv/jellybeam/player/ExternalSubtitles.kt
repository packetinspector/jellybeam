package tv.jellybeam.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import tv.jellybeam.player.ass.arrangePlainSsa

/** A sidecar row's fetch state in the picker; absent once it has loaded. */
enum class SidecarStatus { LOADING, UNAVAILABLE }

/**
 * Sidecar subtitles (docs/18 §3.2): kept out of the player so a slow or broken file can't touch the video.
 */
object ExternalSubtitles {
    /** Above any [TrackMapping.toId] id, so the two ranges never collide and [TrackMapping.resolve] rejects these. */
    private const val ID_BASE = 1_000_000_000L

    /** Media3 mime for a sidecar codec, or null for one we never parse (bitmaps). */
    fun mimeFor(codec: String): String? = when (codec.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ttml" -> MimeTypes.APPLICATION_TTML
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> null
    }

    /** An ASS/SSA sidecar: the styled overlay can load it whole (docs/18 §3.2). */
    fun isSsa(codec: String): Boolean = mimeFor(codec) == MimeTypes.TEXT_SSA

    fun idFor(index: Int): Long = ID_BASE + index

    /** What a finished sidecar fetch does with its result (docs/18 §3.2). */
    enum class FetchOutcome { LOADED, RETRY_UNDER_NEW_SESSION, AWAIT_NEW_SESSION, FAILED }

    /**
     * docs/18 §3.2: a refusal while a transcode fallback swaps sessions is the old session's, not
     * the file's, so a still-chosen sidecar asks the new session, now or once it exists.
     */
    fun fetchOutcome(loaded: Boolean, stillChosen: Boolean, sessionReplaced: Boolean, swapPending: Boolean): FetchOutcome = when {
        loaded -> FetchOutcome.LOADED
        !stillChosen -> FetchOutcome.FAILED
        sessionReplaced -> FetchOutcome.RETRY_UNDER_NEW_SESSION
        swapPending -> FetchOutcome.AWAIT_NEW_SESSION
        else -> FetchOutcome.FAILED
    }

    /** The server index behind a synthetic id, or null for an embedded track's id. */
    fun indexOf(id: Long?): Int? = id?.takeIf { it >= ID_BASE }?.let { (it - ID_BASE).toInt() }

    /**
     * Picker and policy rows for [subtitles], [activeIndex] marked selected. The language is
     * normalized as Media3 normalizes an embedded track's, so a series' remembered choice matches
     * across episodes whichever way their subtitles ship.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun trackInfos(subtitles: List<ExternalSubtitleFfi>, activeIndex: Int?): List<TrackInfo> =
        subtitles.filter { mimeFor(it.codec) != null }.map { sub ->
            TrackInfo(
                id = idFor(sub.index),
                kind = TrackKindFfi.SUBTITLE,
                title = sub.displayTitle,
                lang = sub.language?.let(androidx.media3.common.util.Util::normalizeLanguageCode),
                codec = sub.codec,
                isDefault = sub.isDefault,
                isSelected = sub.index == activeIndex,
                isForced = sub.isForced,
            )
        }

    /**
     * docs/18 §3.2: the player's half of [decision] plus the sidecar to show; left to the file's
     * defaults, Media3's own rule applies (a default sidecar, else a forced one in [audioLanguage]).
     */
    fun route(
        decision: TrackDecisionFfi,
        subtitles: List<ExternalSubtitleFfi>,
        embeddedTextSelected: Boolean,
        audioLanguage: String?,
    ): Pair<TrackDecisionFfi, Int?> {
        indexOf(decision.subtitleTrackId)?.let { index ->
            return decision.copy(subtitleTrackId = null, subtitleAction = SubtitleActionFfi.OFF) to index
        }
        val leftAlone = decision.subtitleTrackId == null && decision.subtitleAction == SubtitleActionFfi.LEAVE
        if (!leftAlone || embeddedTextSelected) return decision to null
        val parseable = subtitles.filter { mimeFor(it.codec) != null }
        val pick = parseable.firstOrNull { it.isDefault }
            ?: parseable.firstOrNull { it.isForced && languagesMatch(it.language, audioLanguage) }
        return decision to pick?.index
    }

    /** Media3's language match: same normalized tag or same primary language ("eng" == "en-US"). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun languagesMatch(a: String?, b: String?): Boolean {
        val x = a?.takeIf { it.isNotBlank() }?.let(androidx.media3.common.util.Util::normalizeLanguageCode) ?: return false
        val y = b?.takeIf { it.isNotBlank() }?.let(androidx.media3.common.util.Util::normalizeLanguageCode) ?: return false
        return x == y || x.substringBefore('-') == y.substringBefore('-')
    }

    /** Parses [text] off the player; null for an unknown codec, a parser error, or a file with no cues. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun parse(codec: String, text: String): SidecarCues? {
        val mime = mimeFor(codec) ?: return null
        val ssa = if (mime == MimeTypes.TEXT_SSA) ssaParts(text) ?: return null else null
        val format = Format.Builder().setSampleMimeType(mime)
            .setInitializationData(ssa?.let { listOf(it.formatLine.toByteArray(), it.header.toByteArray()) } ?: emptyList())
            .build()
        val factory = DefaultSubtitleParserFactory()
        if (!factory.supportsFormat(format)) return null
        val sink = ParsedCueSink()
        // A malformed or hostile file may throw anything, deep nesting and exhaustion included; it
        // costs the track, never playback.
        return try {
            val parser = factory.create(format)
            val all = SubtitleParser.OutputOptions.allCues()
            if (ssa == null) {
                parser.parse(text.toByteArray(Charsets.UTF_8), all, sink::accept)
            } else {
                // A line at a time: the whole-file parser expands every overlap before yielding a cue.
                ssa.dialogues.forEach { parser.parse(it.toByteArray(Charsets.UTF_8), all, sink::accept) }
            }
            SidecarCues.of(sink.entries, if (ssa == null) { cues -> cues } else ::arrangePlainSsa)
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

/** An ASS/SSA file split for Media3's SsaParser as Matroska hands it over: header, events format, lines. */
internal data class SsaParts(val header: String, val formatLine: String, val dialogues: List<String>)

/** docs/18 §3.2: [text]'s parts, or null without an `[Events]` format line, which every line needs. */
internal fun ssaParts(text: String): SsaParts? {
    val header = StringBuilder()
    val dialogues = mutableListOf<String>()
    var formatLine: String? = null
    var inEvents = false
    for (line in text.removePrefix("\uFEFF").lineSequence()) {
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("[") -> inEvents = trimmed.startsWith("[Events]", ignoreCase = true)
            inEvents && trimmed.startsWith("Dialogue:") -> {
                dialogues += trimmed
                continue
            }
            inEvents && trimmed.startsWith("Format:") -> formatLine = trimmed
        }
        header.append(line).append('\n')
    }
    return SsaParts(header.toString(), formatLine ?: return null, dialogues)
}

/**
 * docs/18 §3.2: collects parser output without trusting its shape: some parsers emit every active
 * cue per snapshot, so each entry keeps at most what can show and a file past [maxSeen] cue
 * references is refused before it can exhaust memory or stall.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ParsedCueSink(private val maxSeen: Long = MAX_PARSED_CUES) {
    val entries = mutableListOf<CuesWithTiming>()
    private var seen = 0L

    fun accept(entry: CuesWithTiming) {
        seen += entry.cues.size
        if (seen > maxSeen) throw TooLarge()
        entries += if (entry.cues.size <= SidecarCues.MAX_VISIBLE) entry
        else CuesWithTiming(entry.cues.take(SidecarCues.MAX_VISIBLE), entry.startTimeUs, entry.durationUs)
    }

    class TooLarge : RuntimeException()

    companion object {
        /** Ordinary files hand over a few thousand cue references. */
        const val MAX_PARSED_CUES = 1_000_000L
    }
}
