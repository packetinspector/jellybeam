package tv.jellybeam.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import tv.jellybeam.player.SidecarCues

/**
 * Raw SSA samples read from a renderer's stream no further than [SAMPLE_LOOKAHEAD_US] ahead of
 * playback; the first one past it is held, so the rest wait in Media3's load-controlled buffer.
 */
@OptIn(UnstableApi::class)
internal class LookaheadSamples(private val readSource: (FormatHolder, DecoderInputBuffer) -> Int) {
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private var held: Pair<Long, ByteArray>? = null

    var ended = false
        private set

    fun reset() {
        ended = false
        held = null
    }

    /**
     * Hands [onSample] each sample due by [mediaUs] plus the lookahead, at most [maxSamples], in
     * stream order, and [onFormat] each new format; the rest wait for the next call.
     */
    fun drain(
        mediaUs: Long,
        offsetUs: Long,
        onFormat: (Format) -> Unit,
        maxSamples: Int = Int.MAX_VALUE,
        onSample: (timeUs: Long, bytes: ByteArray) -> Unit,
    ) {
        var handed = 0
        held?.let { (timeUs, bytes) ->
            if (!withinLookahead(timeUs, mediaUs)) return
            held = null
            onSample(timeUs, bytes)
            handed++
        }
        while (!ended && handed < maxSamples) {
            buffer.clear()
            when (readSource(formatHolder, buffer)) {
                C.RESULT_FORMAT_READ -> formatHolder.format?.let(onFormat)
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) {
                        ended = true
                        return
                    }
                    val data = buffer.data ?: continue
                    data.flip()
                    val bytes = ByteArray(data.remaining()).also { data.get(it) }
                    val timeUs = buffer.timeUs - offsetUs
                    if (!withinLookahead(timeUs, mediaUs)) {
                        held = timeUs to bytes
                        return
                    }
                    onSample(timeUs, bytes)
                    handed++
                }
                else -> return
            }
        }
    }
}

/**
 * docs/13: the plain SSA lines showing. Overlapping lines all show, stacked ([arrangePlainSsa]), but at
 * most [cap] at once, and a line arriving while [cap] held lines still run is dropped unparsed.
 */
internal class PlainSsaLines(private val cap: Int = SidecarCues.MAX_VISIBLE) {
    private class Line(val startUs: Long, val endUs: Long, val cues: List<Cue>)

    /** In arrival order, which is start order for a muxed track. */
    private val lines = ArrayList<Line>()
    private var shownLines: List<Line> = emptyList()
    private var shown: List<Cue> = emptyList()

    /** Whether a line starting at [startUs] could show beside the lines already held. */
    fun hasRoom(startUs: Long): Boolean = lines.count { it.endUs > startUs } < cap

    fun add(startUs: Long, endUs: Long, cues: List<Cue>) {
        if (endUs > startUs && cues.isNotEmpty()) lines += Line(startUs, endUs, cues)
    }

    /** The cues showing at [positionUs], the same instance until they change; ended lines are dropped. */
    fun at(positionUs: Long): List<Cue> {
        lines.removeAll { it.endUs <= positionUs }
        // Called every render, so nothing is allocated unless what shows changed.
        var count = 0
        var same = true
        for (line in lines) {
            if (line.startUs > positionUs) continue
            if (count == cap) break
            if (shownLines.getOrNull(count) !== line) same = false
            count++
        }
        if (same && count == shownLines.size) return shown
        shownLines = lines.filter { it.startUs <= positionUs }.take(cap)
        shown = arrangePlainSsa(shownLines.flatMap { it.cues }.take(cap))
        return shown
    }

    fun clear() {
        lines.clear()
        shownLines = emptyList()
        shown = emptyList()
    }
}

/** Where Media3's SsaParser puts a line without `\pos`: one spot per vertical alignment. */
internal enum class SsaSpot { TOP, MIDDLE, BOTTOM }

/** [cue]'s spot when SsaParser placed it by alignment alone (5%, 50% or 95% of the height), else null. */
internal fun ssaSpot(cue: Cue): SsaSpot? = when {
    cue.lineType != Cue.LINE_TYPE_FRACTION -> null
    cue.lineAnchor == Cue.ANCHOR_TYPE_START && cue.line == SSA_TOP_LINE -> SsaSpot.TOP
    cue.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE && cue.line == SSA_MIDDLE_LINE -> SsaSpot.MIDDLE
    cue.lineAnchor == Cue.ANCHOR_TYPE_END && cue.line == SSA_BOTTOM_LINE -> SsaSpot.BOTTOM
    else -> null
}

private const val SSA_TOP_LINE = 0.05f
private const val SSA_MIDDLE_LINE = 0.5f
private const val SSA_BOTTOM_LINE = 0.95f

/**
 * docs/12 §16: [cues] (in start order) grouped into what draws as one stacked cue, in draw order.
 * Lines sharing a spot and horizontal place stack as libass stacks them: the first stays at the
 * screen edge, so later bottom lines go above it and later top or middle ones below. A positioned
 * line is a group of its own.
 */
internal fun plainSsaStacks(cues: List<Cue>): List<Pair<SsaSpot?, List<Cue>>> {
    val groups = LinkedHashMap<Any, Pair<SsaSpot?, MutableList<Cue>>>()
    cues.forEachIndexed { i, cue ->
        val spot = ssaSpot(cue)
        val key: Any = if (spot == null) i else listOf(spot, cue.positionAnchor, cue.position, cue.textAlignment)
        groups.getOrPut(key) { spot to mutableListOf() }.second += cue
    }
    return groups.values.map { (spot, group) -> spot to if (spot == SsaSpot.BOTTOM) group.asReversed() else group }
}

/**
 * docs/12 §16: plain SSA as the subtitle view should draw it. SsaParser pins unpositioned lines to
 * fixed spots, so lines sharing one overprint and bottom ones ignore the view's bottom padding (the
 * OSD lift, the position preset); each stack is one cue, and the bottom one sits where plain
 * subtitles do. Positioned signs keep their place.
 */
internal fun arrangePlainSsa(cues: List<Cue>): List<Cue> {
    if (cues.none { ssaSpot(it) != null }) return cues
    return plainSsaStacks(cues).map { (spot, group) ->
        val cue = if (group.size == 1) {
            group[0]
        } else {
            val text = android.text.SpannableStringBuilder()
            group.forEachIndexed { i, line ->
                if (i > 0) text.append('\n')
                text.append(line.text ?: "")
            }
            group[0].buildUpon().setText(text).build()
        }
        if (spot == SsaSpot.BOTTOM) cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET).build() else cue
    }
}

/** Parses one Media3 SSA sample at media time [timeUs] into [lines]; a malformed line costs that line only. */
@OptIn(UnstableApi::class)
internal fun PlainSsaLines.addSample(parser: SubtitleParser, timeUs: Long, sample: ByteArray) {
    if (!hasRoom(timeUs)) return
    try {
        parser.parse(sample, SubtitleParser.OutputOptions.allCues()) { entry ->
            if (entry.startTimeUs == C.TIME_UNSET || entry.durationUs == C.TIME_UNSET) return@parse
            val startUs = timeUs + entry.startTimeUs
            add(startUs, startUs + entry.durationUs, entry.cues)
        }
    } catch (_: Exception) {
    } catch (_: StackOverflowError) {
        // A hostile line's markup can exhaust the parser's regex recursion.
    }
}

/**
 * docs/13: raw SSA with full styling off, parsed a line at a time with Media3's own SsaParser only as
 * playback nears it, so no SSA track is parsed while the file is read. Placed before Media3's
 * TextRenderer, which takes raw SSA only through its deprecated legacy decoding.
 */
@OptIn(UnstableApi::class)
class PlainSsaRenderer(
    private val output: TextOutput,
    /** Runs on the player's output looper, where [output] expects its calls. */
    private val post: (Runnable) -> Unit,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val samples = LookaheadSamples { holder, buffer -> readSource(holder, buffer, 0) }
    private val lines = PlainSsaLines()
    private var parser: SubtitleParser? = null
    private var shown: List<Cue> = emptyList()

    override fun getName(): String = "PlainSsaRenderer"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        when {
            isRawSsa(format) -> C.FORMAT_HANDLED
            MimeTypes.isText(format.sampleMimeType) -> C.FORMAT_UNSUPPORTED_SUBTYPE
            else -> C.FORMAT_UNSUPPORTED_TYPE
        },
    )

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        formats.firstOrNull()?.let(::useFormat)
    }

    private fun useFormat(format: Format) {
        // A header Media3 can't read leaves the track blank rather than failing playback.
        parser = runCatching { DefaultSubtitleParserFactory().create(format) }.getOrNull()
        lines.clear()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
        samples.reset()
        lines.clear()
        show(emptyList(), positionUs - streamOffsetUs)
    }

    override fun onDisabled() {
        parser = null
        lines.clear()
        show(emptyList(), 0)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val mediaUs = positionUs - streamOffsetUs
        samples.drain(mediaUs, streamOffsetUs, ::useFormat, MAX_SAMPLES_PER_RENDER) { timeUs, sample ->
            parser?.let { lines.addSample(it, timeUs, sample) }
        }
        show(lines.at(mediaUs), mediaUs)
    }

    private fun show(cues: List<Cue>, mediaUs: Long) {
        if (cues === shown) return
        shown = cues
        val group = CueGroup(cues, mediaUs)
        // Both calls, as Media3's TextRenderer makes them, so either listener style sees the change.
        @Suppress("DEPRECATION")
        post { output.onCues(group.cues); output.onCues(group) }
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = samples.ended
}
