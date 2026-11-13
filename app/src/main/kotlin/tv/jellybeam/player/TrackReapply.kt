package tv.jellybeam.player

import java.util.Locale
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/18 §3.1: what identifies an embedded track across a reload, whose ids are not stable. */
internal data class TrackIdentity(val lang: String?, val title: String?, val forced: Boolean)

internal fun TrackInfo.identity() = TrackIdentity(lang = normalizedLanguage(lang), title = title?.trim()?.takeIf { it.isNotEmpty() }, forced = isForced)

/** ISO 639-1, -2/T and -2/B codes for the same language compare equal ("en", "eng"; "de", "ger");
 * unknown codes as-is.
 */
internal fun normalizedLanguage(lang: String?): String? {
    val code = lang?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
    BIBLIOGRAPHIC_TO_TERMINOLOGY[code]?.let { return it }
    return runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: code
}

/** The ISO 639-2 codes whose bibliographic form (common in MKV) differs from Java's terminology form. */
private val BIBLIOGRAPHIC_TO_TERMINOLOGY = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
    "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
    "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
    "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
)

/**
 * docs/18 §3.1: the semantic subtitle choice a reload must restore, recorded when a decision is
 * applied (manual or automatic) rather than read back from Media3, whose announced selection lags.
 */
internal sealed interface TextChoice {
    /** Nothing decided yet (or a decision still in flight): the new load resolves through Rust. */
    data object Auto : TextChoice

    /** The decision left the player's own default; the new load's default stands. */
    data object Leave : TextChoice

    data object Off : TextChoice

    /** A sidecar is showing or loading; the sidecar adoption path restores it, text stays off. */
    data object Sidecar : TextChoice

    /** [streamIndex]: the server stream a stream row named, which identity alone may not single out. */
    data class Embedded(val identity: TrackIdentity, val streamIndex: Int? = null) : TextChoice
}

/** docs/18 §3.1: the audio choice a reload restores; [streamIndex] is the server stream a transcode
 * row named, which identity alone may not single out.
 */
internal data class AudioChoice(val identity: TrackIdentity, val streamIndex: Int? = null)

/** What a reload restores: the subtitle choice, and the audio choice once one was decided. */
internal data class ReloadChoice(val text: TextChoice, val audio: AudioChoice?)

/** The choice to restore after a reload: undecided resolves afresh, a showing or loading sidecar
 * wins over what was [recorded] before it.
 */
internal fun textChoiceBeforeReload(decided: Boolean, sidecarActive: Boolean, recorded: TextChoice): TextChoice = when {
    !decided -> TextChoice.Auto
    sidecarActive -> TextChoice.Sidecar
    else -> recorded
}

/**
 * docs/18 §3.1 (section 6.3 policy): the one track of [kind] in [tracks] that is [wanted]. An exact
 * language, forced and title match wins; otherwise a unique language-and-forced match (titles get
 * rewritten or dropped by a transcode). Ambiguity is no match, never the first candidate: the
 * track list carries no role, so two same-language tracks can't be told apart safely.
 */
internal fun matchTrack(wanted: TrackIdentity, kind: TrackKindFfi, tracks: List<TrackInfo>): TrackInfo? {
    val sameKind = tracks.filter { it.kind == kind }
    val sameLanguage = sameKind.filter { normalizedLanguage(it.lang) == wanted.lang && it.isForced == wanted.forced }
    sameLanguage.filter { it.identity().title == wanted.title }.singleOrNull()?.let { return it }
    return sameLanguage.singleOrNull()
}

/**
 * docs/18 §3.1: the subtitle stream a transcode negotiation should name for [choice]. Off and a
 * sidecar name none (`-1`, so nothing is burned in); an embedded choice names the stream its row
 * named while that stream still carries its identity, else its matched stream, or none when
 * ambiguous or missing; an undecided or default choice leaves the server's default.
 */
internal fun subtitleStreamIndexFor(choice: TextChoice, embedded: List<EmbeddedSubtitleFfi>): Int? = when (choice) {
    TextChoice.Auto, TextChoice.Leave -> null
    TextChoice.Off, TextChoice.Sidecar -> NO_SUBTITLE_STREAM
    is TextChoice.Embedded -> {
        val streams = embedded.map {
            TrackInfo(id = it.index.toLong(), kind = TrackKindFfi.SUBTITLE, title = it.title, lang = it.language, codec = it.codec, isDefault = false, isSelected = false, isForced = it.forced)
        }
        val named = streams.firstOrNull { it.id == choice.streamIndex?.toLong() && it.identity() == choice.identity }
        (named ?: matchTrack(choice.identity, TrackKindFfi.SUBTITLE, streams))?.id?.toInt() ?: NO_SUBTITLE_STREAM
    }
}

/** Jellyfin's "no subtitle stream" index. */
internal const val NO_SUBTITLE_STREAM = -1


/** docs/18 §3.1: a transcode's audio rows are every source stream, since its HLS carries only the
 * [shown] one; the server's label is the title, shown verbatim.
 */
internal fun transcodeAudioRows(streams: List<AudioStreamFfi>, shown: Int?): List<TrackInfo> = streams.map { it.row(shown) }

private fun AudioStreamFfi.row(shown: Int?) = TrackInfo(
    id = streamRowId(index),
    kind = TrackKindFfi.AUDIO,
    title = displayTitle,
    lang = language,
    codec = codec,
    isDefault = isDefault,
    isSelected = index == shown,
    isForced = false,
)

/**
 * docs/18 §3.1: the audio stream a transcode negotiation names for [choice]: the stream a row named
 * while it still carries the picked identity, else the unique match; none (the server's default)
 * when undecided, ambiguous or missing, since there is no "no audio" to fall back to.
 */
internal fun audioStreamIndexFor(choice: AudioChoice?, streams: List<AudioStreamFfi>): Int? {
    choice ?: return null
    val named = streams.firstOrNull { it.index == choice.streamIndex && it.identity() == choice.identity }
    if (named != null) return named.index
    val match = matchTrack(choice.identity, TrackKindFfi.AUDIO, streams.map { it.row(null).copy(title = it.title) }) ?: return null
    return streams.first { streamRowId(it.index) == match.id }.index
}

/** docs/18 §3.1: what a player audio [track] records; a transcode's one track is its [delivered]
 * stream, recorded as that stream so a renegotiation names it again.
 */
internal fun audioChoiceOf(track: TrackInfo, delivered: AudioStreamFfi?): AudioChoice =
    delivered?.let(::streamAudioChoice) ?: AudioChoice(track.identity())

/** docs/18 §3.1: what picking source [stream] records, keyed the way [audioStreamIndexFor] matches it. */
internal fun streamAudioChoice(stream: AudioStreamFfi): AudioChoice = AudioChoice(stream.identity(), stream.index)

/** docs/18 §3.1: a stream's identity uses its own title, as the player reports it, not the server's display label. */
private fun AudioStreamFfi.identity() = row(null).copy(title = title).identity()

/** docs/18 §3.1, docs/28 §1: what picking a transcode's audio stream does. */
internal enum class AudioStreamPick { UNCHANGED, REFUSED, RENEGOTIATE }

/** docs/18 §3.1, docs/28 §1: the [delivered] stream changes nothing unless a negotiation is in flight
 * (its landing re-checks the choice); any other pick renegotiates, which a group never does.
 */
internal fun audioStreamPick(picked: Int, delivered: Int?, negotiating: Boolean, inGroup: Boolean): AudioStreamPick = when {
    picked == delivered && !negotiating -> AudioStreamPick.UNCHANGED
    inGroup -> AudioStreamPick.REFUSED
    else -> AudioStreamPick.RENEGOTIATE
}
