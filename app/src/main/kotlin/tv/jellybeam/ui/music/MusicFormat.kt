package tv.jellybeam.ui.music

import tv.jellybeam.player.EndsClock
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.ui.cards.ArtSource
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/** What the music bar and the drawer's Now playing entry show; `null` from [musicBarModel] means no bar. */
internal data class MusicBarModel(
    val itemId: String,
    val title: String,
    val artist: String,
    val art: ArtSource,
    val isPlaying: Boolean,
    /** No player yet: Play/Pause shows the loading dots (§3.5). */
    val loading: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val connectionLost: Boolean,
)

/**
 * docs/29 §8.4: the bar exists only while the owner holds playback (Starting or Active). Before the
 * player publishes, the session's start row stands in; [card] is the owner's song metadata.
 */
internal fun musicBarModel(flow: MusicFlow?, now: MusicNow?, card: (String) -> Card?): MusicBarModel? {
    val (session, active) = when (val phase = flow?.phase ?: return null) {
        MusicFlow.Phase.Idle -> return null
        is MusicFlow.Phase.Starting -> phase.session to null
        is MusicFlow.Phase.Active -> phase.session to phase
    }
    val snapshot = now?.takeIf { active != null }
    val item = snapshot?.queue?.getOrNull(snapshot.current)?.item ?: session.items[session.start].item
    val song = card(item)
    return MusicBarModel(
        itemId = item,
        title = song?.name.orEmpty(),
        artist = song?.let(::musicArtistLine).orEmpty(),
        art = song?.let(::trackArtSource) ?: ArtSource.None,
        isPlaying = snapshot?.isPlaying == true,
        loading = snapshot == null,
        positionMs = snapshot?.positionMs ?: 0L,
        durationMs = trackDurationMs(song, playerMs = snapshot?.durationMs ?: 0L),
        connectionLost = active?.net == MusicFlow.Net.Lost,
    )
}

private const val TICKS_PER_MS = 10_000L

/** The bar's second line: the artist, a temporary [Notice], or a lost connection that stays until resume (§3.4). */
internal sealed interface BarLine2 {
    data class Artist(val text: String) : BarLine2
    data class Notice(val text: String) : BarLine2
    data object ConnectionLost : BarLine2
}

internal fun musicBarLine2(model: MusicBarModel, notice: String?): BarLine2 = when {
    model.connectionLost -> BarLine2.ConnectionLost
    notice != null -> BarLine2.Notice(notice)
    else -> BarLine2.Artist(model.artist)
}

/** docs/29 §9 C: a track's own performers joined verbatim, else its album artist; no "feat." parsing. */
internal fun musicArtistLine(card: Card): String = card.artists.joinToString(", ").ifEmpty { card.albumArtist.orEmpty() }

/** docs/29 §10 S4: a track's own Primary, then its album's, then the no-art tile. */
internal fun trackArtSource(card: Card): ArtSource {
    card.primaryTag?.let { return ArtSource.Own(card.id, it, ImageKind.PRIMARY) }
    val albumId = card.albumId
    val albumTag = card.albumPrimaryTag
    return if (albumId != null && albumTag != null) ArtSource.Fallback(albumId, albumTag, ImageKind.PRIMARY) else ArtSource.None
}

/**
 * docs/29 §8.1: every music time reads the server's RunTimeTicks and [formatTrackTime]'s floor, so a row,
 * the bar and Now Playing show one length; the player's own length stands in only when the server sent none.
 */
internal fun trackDurationMs(card: Card?, playerMs: Long = 0L): Long =
    card?.runtimeTicks?.takeIf { it > 0L }?.let { it / TICKS_PER_MS } ?: playerMs.coerceAtLeast(0L)

/** A row's length column: [trackDurationMs] formatted, empty when unknown. */
internal fun trackDurationLabel(card: Card): String = trackDurationMs(card).takeIf { it > 0L }?.let(::formatTrackTime).orEmpty()

/** `m:ss`, or `h:mm:ss` from an hour, floored to the second; a negative time reads as zero. */
internal fun formatTrackTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000L
    val h = total / 3_600L
    val m = total % 3_600L / 60L
    val s = total % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The player publishes on events only, so the shown position runs on from its snapshot while playing; [durationMs] 0 is unknown. */
internal fun musicPositionMs(positionMs: Long, durationMs: Long, isPlaying: Boolean, elapsedMs: Long): Long {
    if (!isPlaying) return positionMs
    val ran = positionMs + elapsedMs.coerceAtLeast(0L)
    return if (durationMs > 0L) ran.coerceAtMost(durationMs) else ran
}

/** docs/29 §9 F: when the playing track ends, in epoch millis. */
internal fun trackEndsAtMs(nowMs: Long, positionMs: Long, durationMs: Long): Long = EndsClock.endsAtMillis(nowMs, durationMs - positionMs, 1f)

/** docs/29 §8.1: "—" when the track has no IndexNumber. */
internal fun trackNumberLabel(indexNumber: Int?): String = indexNumber?.toString() ?: "—"

/** docs/29 §8.3: the first letter or digit of SortName, else of Name; empty when neither has one. */
internal fun artistInitial(sortName: String?, name: String): String =
    firstLetter(sortName.orEmpty()) ?: firstLetter(name) ?: ""

private fun firstLetter(text: String): String? {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp))
        i += Character.charCount(cp)
    }
    return null
}

internal enum class SegmentKind { Past, Current, Future }

/** One drawn segment: [index] is its position in play order; [x] and [width] in px from the strip's segment area start. */
internal data class QueueStripSegment(val index: Int, val x: Float, val width: Float, val kind: SegmentKind)

/** [hiddenBefore]/[hiddenAfter] tracks are left out at the ends and shown as `… +n`. */
internal data class QueueStripLayout(val segments: List<QueueStripSegment>, val hiddenBefore: Int, val hiddenAfter: Int)

/** The most tracks a windowed strip draws (docs/29 §9 F, user-approved). */
internal const val QUEUE_STRIP_WINDOW = 40

/**
 * docs/29 §9 F: segments in play order, widths by duration with [minPx] kept, filling [widthPx] with [gapPx]
 * between them. When the minimum can't hold for every track, a window of up to [maxWindow] around [current]
 * is drawn and each end that hides tracks takes a label [labelPx] wide plus a gap. An unknown duration counts
 * as the mean of the known ones.
 */
internal fun queueStripLayout(
    durationsMs: List<Long?>,
    current: Int,
    widthPx: Float,
    gapPx: Float,
    minPx: Float,
    maxWindow: Int = QUEUE_STRIP_WINDOW,
    labelPx: (hidden: Int) -> Float,
): QueueStripLayout {
    val n = durationsMs.size
    if (n == 0) return QueueStripLayout(emptyList(), 0, 0)
    val known = durationsMs.filterNotNull().filter { it > 0L }
    val fill = if (known.isEmpty()) 1.0 else known.average()
    val durations = durationsMs.map { d -> d?.takeIf { it > 0L }?.toDouble() ?: fill }

    fun fits(count: Int, area: Float) = count * minPx + (count - 1) * gapPx <= area + 0.001f
    var start = 0
    var end = n
    var area = widthPx
    if (!fits(n, widthPx)) {
        var k = minOf(maxWindow, n)
        while (true) {
            start = (current - k / 2).coerceIn(0, n - k)
            end = start + k
            area = widthPx -
                (if (start > 0) labelPx(start) + gapPx else 0f) -
                (if (end < n) labelPx(n - end) + gapPx else 0f)
            if (k == 1 || fits(k, area)) break
            k--
        }
    }

    val shown = (start until end).toList()
    val widths = proportionalWidths(shown.map { durations[it] }, area - gapPx * (shown.size - 1), minPx)
    var x = 0f
    val segments = shown.mapIndexed { i, index ->
        val kind = when {
            index < current -> SegmentKind.Past
            index == current -> SegmentKind.Current
            else -> SegmentKind.Future
        }
        QueueStripSegment(index, x, widths[i], kind).also { x += widths[i] + gapPx }
    }
    return QueueStripLayout(segments, hiddenBefore = start, hiddenAfter = n - end)
}

/** [total] shared by [weights]; a share under [min] is pinned to it and the rest re-shared. */
private fun proportionalWidths(weights: List<Double>, total: Float, min: Float): List<Float> {
    val pinned = BooleanArray(weights.size)
    while (true) {
        val free = total - min * pinned.count { it }
        val sum = weights.indices.filterNot { pinned[it] }.sumOf { weights[it] }
        val widths = weights.indices.map { i -> if (pinned[i]) min else (free * weights[i] / sum).toFloat() }
        val under = weights.indices.filter { !pinned[it] && widths[it] < min }
        if (under.isEmpty() || under.size + pinned.count { it } == weights.size) return widths.map { if (it < min) min else it }
        under.forEach { pinned[it] = true }
    }
}
