package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import uniffi.jellybeam_core.AssVideoMatrix

/** A raw (untranscoded) SSA/ASS track: the Rust overlay renders these when full styling is on. */
fun isRawSsa(format: Format?): Boolean = format?.sampleMimeType == MimeTypes.TEXT_SSA

/**
 * The script header the renderer needs. Media3's Matroska extractor stores `[SSA dialogue format line,
 * CodecPrivate]`, so the header is the last entry; empty when a muxer wrote none.
 */
fun assHeader(initializationData: List<ByteArray>): ByteArray = initializationData.lastOrNull() ?: ByteArray(0)

/** What the overlay converts a script's `YCbCr Matrix` colours for (docs/09). */
data class AssVideoColour(val matrix: AssVideoMatrix, val fullRange: Boolean, val hdr: Boolean, val height: Int)

/** The selected video's colour space from Media3's [Format.colorInfo]; unsignalled fields stay unknown. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun assVideoColour(video: Format): AssVideoColour {
    val info = video.colorInfo
    val matrix = when (info?.colorSpace) {
        C.COLOR_SPACE_BT601 -> AssVideoMatrix.BT601
        C.COLOR_SPACE_BT709 -> AssVideoMatrix.BT709
        C.COLOR_SPACE_BT2020 -> AssVideoMatrix.BT2020
        else -> AssVideoMatrix.UNKNOWN
    }
    val hdr = info?.colorTransfer == C.COLOR_TRANSFER_ST2084 || info?.colorTransfer == C.COLOR_TRANSFER_HLG
    return AssVideoColour(matrix, info?.colorRange == C.COLOR_RANGE_FULL, hdr, video.height)
}

/** Stable per-item key for one subtitle track; Media3 prefixes period ids as `period:track`. */
fun assTrackKey(format: Format): String = format.id?.substringAfterLast(':') ?: "ass"

// No WOFF/WOFF2: substation can't read them, so fetching them would only spend the budget.
private val FONT_MIMES = setOf(
    "font/ttf", "font/otf", "font/sfnt", "font/collection",
    "application/font-sfnt", "application/x-truetype-font",
    "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype",
)
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

/** Matroska attachments carry fonts under many MIME spellings; the file extension settles the rest. */
fun isFontAttachment(mime: String?, fileName: String?): Boolean =
    mime?.lowercase() in FONT_MIMES ||
        (mime == null || mime == "application/octet-stream") &&
        fileName?.substringAfterLast('.', "")?.lowercase() in FONT_EXTENSIONS

/** A font attachment's byte range within the media file. */
data class AssFontLocation(val name: String, val offset: Long, val size: Int)

/** The HTTP `Range` for [size] bytes at [offset], inclusive per RFC 9110. */
fun rangeHeader(offset: Long, size: Int): String = "bytes=$offset-${offset + size - 1}"

/**
 * How many bytes a 206's `Content-Range` serves when it starts at [offset] and stays within the [size]
 * asked for (shorter only at the end of the file); null for any other range, which is not those bytes.
 */
fun servedRangeLength(contentRange: String?, offset: Long, size: Int): Int? {
    val m = Regex("""^bytes (\d+)-(\d+)/""").find(contentRange?.trim() ?: return null) ?: return null
    val first = m.groupValues[1].toLongOrNull() ?: return null
    val last = m.groupValues[2].toLongOrNull() ?: return null
    val served = last - first + 1
    return served.toInt().takeIf { first == offset && served in 1..size }
}

/** Fonts to fetch, in file order, until [budgetBytes] (`AssOverlay.fontLimits`, which Rust enforces). */
fun fontsToFetch(locations: List<AssFontLocation>, budgetBytes: Long): List<AssFontLocation> {
    var total = 0L
    return locations.takeWhile { total += it.size; total <= budgetBytes }
}

/** An unread attachment block at least this large is jumped over with a new range request. */
const val SEEK_PAST_ATTACHMENTS_BYTES = 1024 * 1024

/** How long a styled track waits for its fonts before showing with whatever has arrived. */
const val ASS_FONT_WAIT_MS = 5_000L

/**
 * How long fonts wait for the item's subtitle choice before it counts as settled anyway, so a
 * choice that never arrives (a failed resolve, a left default, a sidecar pick) still fetches (docs/13).
 */
const val ASS_CHOICE_GRACE_MS = 1_000L

/**
 * An item's font fetch (docs/13): not started or stopped, waiting for the subtitle choice, running,
 * or finished.
 */
enum class AssFontFetch { IDLE, AWAITING_CHOICE, FETCHING, DONE }

/** What the font fetch does next. */
enum class AssFontFetchStep { AWAIT, START, STOP, NONE }

/**
 * docs/13: fonts are fetched only while a styled track shows and the item's subtitle choice is
 * [choiceSettled], so a default track the viewer's settings turn off costs no download; Media3 picks
 * the file's default before that choice lands. A stopped fetch starts again when one shows.
 */
fun assFontFetchStep(fetch: AssFontFetch, showing: Boolean, hasAttachments: Boolean, choiceSettled: Boolean): AssFontFetchStep = when (fetch) {
    AssFontFetch.IDLE ->
        if (!showing || !hasAttachments) AssFontFetchStep.NONE else if (choiceSettled) AssFontFetchStep.START else AssFontFetchStep.AWAIT
    AssFontFetch.AWAITING_CHOICE ->
        if (!showing) AssFontFetchStep.STOP else if (choiceSettled) AssFontFetchStep.START else AssFontFetchStep.NONE
    AssFontFetch.FETCHING -> if (!showing) AssFontFetchStep.STOP else AssFontFetchStep.NONE
    AssFontFetch.DONE -> AssFontFetchStep.NONE
}

/** The fetch state [step] moves [fetch] to; a stop from the wait ends it with no request made. */
fun assFontFetchNext(fetch: AssFontFetch, step: AssFontFetchStep): AssFontFetch = when (step) {
    AssFontFetchStep.AWAIT -> AssFontFetch.AWAITING_CHOICE
    AssFontFetchStep.START -> AssFontFetch.FETCHING
    AssFontFetchStep.STOP -> AssFontFetch.IDLE
    AssFontFetchStep.NONE -> fetch
}

/**
 * What one font-fetch evaluation does: move to [next], end the request in flight ([endRequest]: bump its number
 * and cancel its call), and start a fetch thread ([startThread]).
 */
data class AssFontTransition(val next: AssFontFetch, val endRequest: Boolean, val startThread: Boolean)

/**
 * [assFontFetchStep] and [assFontFetchNext] as one decision the holder only executes. A start with no
 * [hasUrl] (no item loaded) changes nothing, like no step at all.
 */
fun assFontFetchTransition(
    fetch: AssFontFetch,
    showing: Boolean,
    hasAttachments: Boolean,
    choiceSettled: Boolean,
    hasUrl: Boolean,
): AssFontTransition {
    val step = assFontFetchStep(fetch, showing, hasAttachments, choiceSettled)
    if (step == AssFontFetchStep.NONE || step == AssFontFetchStep.START && !hasUrl) return AssFontTransition(fetch, endRequest = false, startThread = false)
    return AssFontTransition(assFontFetchNext(fetch, step), endRequest = step == AssFontFetchStep.STOP, startThread = step == AssFontFetchStep.START)
}

/** Which timers a move from [from] to [to] sets: see [assFontTimers]. */
data class AssFontTimers(val armWait: Boolean, val cancelWait: Boolean, val armGrace: Boolean)

/**
 * The font wait ([ASS_FONT_WAIT_MS]) arms once on leaving IDLE, not again on AWAITING_CHOICE to
 * FETCHING, so one blank never exceeds it, and not at all once it has run out for this item; it ends
 * with the fetch. The choice grace ([ASS_CHOICE_GRACE_MS]) arms the first time the item waits.
 */
fun assFontTimers(from: AssFontFetch, to: AssFontFetch, waitExpired: Boolean, graceArmed: Boolean): AssFontTimers = AssFontTimers(
    armWait = from == AssFontFetch.IDLE && (to == AssFontFetch.AWAITING_CHOICE || to == AssFontFetch.FETCHING) && !waitExpired,
    cancelWait = to == AssFontFetch.IDLE || to == AssFontFetch.DONE,
    armGrace = to == AssFontFetch.AWAITING_CHOICE && !graceArmed,
)

/**
 * A styled track holds its frames while its fonts are awaited or arriving, until the wait runs out,
 * so it never draws in stand-ins first (docs/09).
 */
fun assFramesHeld(fetch: AssFontFetch, waitExpired: Boolean): Boolean =
    (fetch == AssFontFetch.AWAITING_CHOICE || fetch == AssFontFetch.FETCHING) && !waitExpired

/**
 * Whether the embedded text track playing is raw SSA once [params] apply: text disabled shows none, a
 * text override shows its track, otherwise [current] stays. Media3 reports the change only later, and
 * not at all when the choice matches its default, so the font fetch can't wait for that report.
 */
fun embeddedRawSsaAfter(params: TrackSelectionParameters, current: Boolean): Boolean {
    if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) return false
    val override = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT } ?: return current
    return override.trackIndices.isNotEmpty() && isRawSsa(override.mediaTrackGroup.getFormat(override.trackIndices[0]))
}

/**
 * Whether to skip an attachments block by reopening the stream past it rather than reading it:
 * fansub MKVs put tens of MB of fonts before the first video cluster (docs/09).
 */
fun seeksPastAttachments(unread: Boolean, bytes: Int, minBytes: Int = SEEK_PAST_ATTACHMENTS_BYTES): Boolean =
    unread && bytes >= minBytes

/**
 * Whether [AssTextRenderer] should send a position: on play/pause changes, and otherwise once the
 * media clock has moved [POSITION_STEP_US], since Rust extrapolates between updates while playing.
 */
fun shouldSendPosition(lastUs: Long, lastPlaying: Boolean, nowUs: Long, playing: Boolean): Boolean =
    playing != lastPlaying || lastUs == Long.MIN_VALUE || kotlin.math.abs(nowUs - lastUs) >= POSITION_STEP_US

const val POSITION_STEP_US = 50_000L

/**
 * Samples are handed to Rust only this far ahead of playback; later ones wait in Media3's own
 * load-controlled buffer, so the queue into the render thread stays bounded.
 */
fun withinLookahead(sampleUs: Long, positionUs: Long): Boolean = sampleUs <= positionUs + SAMPLE_LOOKAHEAD_US

const val SAMPLE_LOOKAHEAD_US = 3_000_000L

/**
 * docs/13: samples one render pass hands Rust at most; a heavy script holds tens of thousands in its
 * first seconds, and the playback thread must get back to the first frame between passes.
 */
const val MAX_SAMPLES_PER_RENDER = 4096

/**
 * docs/12 §16: percent the renderer raises bottom dialogue while the OSD is up, so it clears the control
 * bar like plain subtitles do; positioned signs never move. The vertical-position preset is a
 * plain-subtitle preference and is not applied to styled tracks.
 */
fun assLineLiftPercent(osdVisible: Boolean): Double = if (osdVisible) OSD_LINE_LIFT_PERCENT else 0.0

const val OSD_LINE_LIFT_PERCENT = 18.0
