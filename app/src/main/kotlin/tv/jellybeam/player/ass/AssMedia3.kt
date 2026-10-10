package tv.jellybeam.player.ass

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.AssSample

/**
 * Where Matroska attachments are reported. The extractor never reads them: it records where the
 * block sits and jumps over it, and the player finds and fetches its fonts once a styled track shows
 * (docs/13). [wantsFonts] is false when full styling is off.
 */
interface AssFontSink {
    fun wantsFonts(): Boolean

    /** Loader thread: an Attachments block the extractor jumped over. */
    fun attachments(block: AttachmentsBlock)

    /** Loader thread: the file's Cues index was read; [cues] holds its SSA tracks' entries. */
    fun subtitleCues(cues: SubtitleCues, timecodeScaleNs: Long) = Unit

    /** The sink one item's extractors report to, taken when its media source is created (application thread, inside `setMediaItem`, after `load`'s generation bump; [ItemBoundMediaSourceFactory]). */
    fun forItem(): AssFontSink = this
}

/**
 * The player's sink: [forItem] binds a sink to the item generation current when the item's media source is
 * created (application thread, inside `setMediaItem`, after `load`'s generation bump), and that sink delivers only while the generation is still current, checked together with the write under
 * [lock] (which the generation bump also holds), so an old item's loader thread can't write into a newer
 * item's state. The root itself delivers nothing.
 */
class ItemScopedFontSink(
    private val generation: () -> Long,
    private val lock: Any,
    private val wants: () -> Boolean,
    private val onAttachments: (AttachmentsBlock) -> Unit,
    private val onCues: (SubtitleCues, Long) -> Unit,
) : AssFontSink {
    override fun wantsFonts(): Boolean = wants()

    override fun attachments(block: AttachmentsBlock) = Unit

    override fun forItem(): AssFontSink {
        val bound = generation()
        return object : AssFontSink {
            override fun wantsFonts(): Boolean = wants()

            override fun attachments(block: AttachmentsBlock) {
                synchronized(lock) { if (generation() == bound) onAttachments(block) }
            }

            override fun subtitleCues(cues: SubtitleCues, timecodeScaleNs: Long) {
                synchronized(lock) { if (generation() == bound) onCues(cues, timecodeScaleNs) }
            }
        }
    }
}

/**
 * docs/13: declines SSA, so Media3 passes raw samples to [AssTextRenderer] or [PlainSsaRenderer]
 * instead of parsing every SSA track as the file is read, chosen or not; other formats are untouched.
 */
@OptIn(UnstableApi::class)
class AssAwareSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = !isRawSsa(format) && delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = delegate.create(format)
}

/**
 * [DefaultExtractorsFactory] with its Matroska extractor swapped for [AssMatroskaExtractor]. [fonts] is
 * already bound to one item ([ItemBoundMediaSourceFactory]), so a late loader thread can't bind a newer one.
 */
@OptIn(UnstableApi::class)
class AssExtractorsFactory(
    private val fonts: AssFontSink,
    private val base: DefaultExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcoding = true
    private var parseHagc = true

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        base.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Media3 deprecation; forwarded as-is.")
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        transcoding = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        base.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory {
        base.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        parseHagc = parseHagcMetadata
        base.setParseHagcMetadata(parseHagcMetadata)
        return this
    }

    override fun createExtractors(): Array<Extractor> = swap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(base.createExtractors(uri, responseHeaders))

    private fun swap(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
        val e = extractors[i]
        if (e is MatroskaExtractor && e !is AssMatroskaExtractor) {
            AttachmentSeekingExtractor(
                AssMatroskaExtractor(
                    subtitleParserFactory,
                    matroskaFlags(transcoding, parseHagc),
                    fonts,
                ),
            )
        } else {
            e
        }
    }
}

/** The flags DefaultExtractorsFactory gives its own MatroskaExtractor, so the swap changes nothing else. */
@OptIn(UnstableApi::class)
fun matroskaFlags(transcoding: Boolean, parseHagc: Boolean): Int =
    (if (transcoding) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
        (if (parseHagc) 0 else MatroskaExtractor.FLAG_DISABLE_HAGC_METADATA)

/**
 * docs/13: the attachments block is one unknown element, styled or not, so no font byte is read before
 * the first frame; its range goes to [fonts] when styled. [AttachmentSeekingExtractor] turns a large
 * skip into a seek. Uses MatroskaExtractor's protected element hooks; no reflection.
 */
@OptIn(UnstableApi::class)
class AssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    flags: Int,
    private val fonts: AssFontSink,
    seekPastBytes: Int = SEEK_PAST_ATTACHMENTS_BYTES,
) : MatroskaExtractor(subtitleParserFactory, flags) {
    // Cues of SSA tracks (docs/13): the tracks, the segment's data start and time scale, then each CuePoint.
    private val ssaTracks = mutableSetOf<Int>()
    private var trackNumber = -1
    private var trackCodec: String? = null
    private var trackEncoded = false
    private var segmentStart = 0L
    private var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
    private var cues: SubtitleCues? = null
    private var cueTime = 0L
    private var cueTrack = -1
    private var cueCluster = -1L
    private var cueRelative = -1L
    private var cueDuration = 0L

    /** True right after the reader typed the attachments block, whose content it skips unread. */
    private var skippingAttachments = false

    /** The one skip-to-seek state, which [AttachmentSeekingExtractor] wraps reads with. */
    internal val skips = SeekingSkips(
        seekPastBytes,
        onArmedSkip = { offset, size -> if (fonts.wantsFonts()) fonts.attachments(AttachmentsBlock(offset, size.toLong())) },
    ) { skippingAttachments }

    override fun getElementType(id: Int): Int {
        val type = super.getElementType(id)
        skippingAttachments = id == ID_ATTACHMENTS
        return when (id) {
            ID_ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_UNKNOWN
            ID_CUE_DURATION -> EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
            else -> type
        }
    }

    override fun isLevel1Element(id: Int): Boolean = id == ID_ATTACHMENTS || super.isLevel1Element(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_SEGMENT -> segmentStart = contentPosition
            ID_TRACK_ENTRY -> {
                trackNumber = -1
                trackCodec = null
                trackEncoded = false
            }
            ID_CONTENT_ENCODING -> trackEncoded = true
            // Only the styled renderer reads earlier-starting lines back (docs/13).
            ID_CUES -> if (fonts.wantsFonts()) cues = SubtitleCues(ssaTracks.toSet())
            ID_CUE_POINT -> cueTime = 0L
            ID_CUE_TRACK_POSITIONS -> {
                cueTrack = -1
                cueCluster = -1L
                cueRelative = -1L
                cueDuration = 0L
            }
        }
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            // An encoded (compressed) track's blocks can't be read back as-is, so it gets no cues.
            ID_TRACK_ENTRY -> if (isSsaCodec(trackCodec) && !trackEncoded && trackNumber > 0) ssaTracks += trackNumber
            ID_CUE_TRACK_POSITIONS -> if (cueCluster >= 0 && cueRelative >= 0) {
                cues?.add(SubtitleCue(cueTrack, cueTime, cueDuration, segmentStart + cueCluster, cueRelative))
            }
            ID_CUES -> cues?.takeIf { it.count > 0 }?.let { fonts.subtitleCues(it, timecodeScaleNs) }
        }
        super.endMasterElement(id)
    }

    override fun integerElement(id: Int, value: Long) {
        when (id) {
            ID_CUE_DURATION -> {
                cueDuration = scaled(value)
                return
            }
            ID_TIMECODE_SCALE -> timecodeScaleNs = value
            ID_TRACK_NUMBER -> trackNumber = value.toInt()
            ID_CUE_TIME -> cueTime = scaled(value)
            ID_CUE_TRACK -> cueTrack = value.toInt()
            ID_CUE_CLUSTER_POSITION -> cueCluster = value
            ID_CUE_RELATIVE_POSITION -> cueRelative = value
        }
        super.integerElement(id, value)
    }

    private fun scaled(timecode: Long): Long = timecode * timecodeScaleNs / 1000

    override fun stringElement(id: Int, value: String) {
        if (id == ID_CODEC_ID) trackCodec = value
        super.stringElement(id, value)
    }

    private companion object {
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_SEGMENT = 0x18538067
        const val ID_TIMECODE_SCALE = 0x2AD7B1
        const val ID_TRACK_ENTRY = 0xAE
        const val ID_TRACK_NUMBER = 0xD7
        const val ID_CODEC_ID = 0x86
        const val ID_CONTENT_ENCODING = 0x6240
        const val ID_CUES = 0x1C53BB6B
        const val ID_CUE_POINT = 0xBB
        const val ID_CUE_TIME = 0xB3
        const val ID_CUE_TRACK_POSITIONS = 0xB7
        const val ID_CUE_TRACK = 0xF7
        const val ID_CUE_CLUSTER_POSITION = 0xF1
        const val ID_CUE_RELATIVE_POSITION = 0xF0
        const val ID_CUE_DURATION = 0xB2
        const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
    }
}

/**
 * Turns the reader's skip of an unread attachments block into a seek, so Media3 reopens the stream
 * past it instead of downloading it (docs/09). The interrupted skip resumes as a no-op at the target.
 */
@OptIn(UnstableApi::class)
class AttachmentSeekingExtractor(private val inner: AssMatroskaExtractor) : Extractor by inner {
    private val skips = inner.skips

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        inner.read(skips.wrap(input), seekPosition)
    } catch (seek: SeekPast) {
        seekPosition.position = seek.target
        Extractor.RESULT_SEEK
    }

    override fun seek(position: Long, timeUs: Long) {
        skips.reset()
        inner.seek(position, timeUs)
    }

    override fun getUnderlyingImplementation(): Extractor = inner
}

/** Thrown out of a skip that should become a seek to [target]; control flow, so no stack trace. */
internal class SeekPast(val target: Long) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The skip-to-seek state shared across reads; [armed] says whether the next skip is the unread
 * attachments block, whose (offset, size) goes to [onArmedSkip] but not again on the re-entry after
 * its seek, and one at least [minBytes] long becomes a seek.
 */
@OptIn(UnstableApi::class)
internal class SeekingSkips(
    private val minBytes: Int,
    private val onArmedSkip: (Long, Int) -> Unit = { _, _ -> },
    private val armed: () -> Boolean,
) {
    private var resumeAt = -1L
    private var resumeBytes = 0
    private var wrapped: Pair<ExtractorInput, ExtractorInput>? = null

    fun reset() {
        resumeAt = -1L
    }

    /** Media3 passes the same input for every read of one load, so its wrapper is reused. */
    fun wrap(input: ExtractorInput): ExtractorInput =
        wrapped?.takeIf { it.first === input }?.second ?: skipping(input).also { wrapped = input to it }

    private fun skipping(input: ExtractorInput): ExtractorInput = object : ExtractorInput by input {
        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (resumeAt == input.position && resumeBytes == length) {
                resumeAt = -1L
                return true
            }
            val unread = armed()
            if (unread) onArmedSkip(input.position, length)
            if (seeksPastAttachments(unread, length, minBytes)) {
                resumeAt = input.position + length
                resumeBytes = length
                throw SeekPast(resumeAt)
            }
            return input.skipFully(length, allowEndOfInput)
        }
    }
}

/**
 * Text renderer for raw SSA while full styling is on: hands the header, samples and playback clock
 * to the Rust overlay, which draws on its own thread. Placed before [PlainSsaRenderer] and Media3's
 * TextRenderer, which take raw SSA otherwise.
 */
@OptIn(UnstableApi::class)
class AssTextRenderer(
    private val overlay: () -> AssOverlay?,
    /** Settings > Subtitles "Full styling for styled subtitles", as the playing item loaded it. */
    private val fullStyling: () -> Boolean,
    /** Playback thread: playback (re)started at a media time, so lines that began earlier are unread (docs/13). */
    private val linesNeeded: (trackKey: String, mediaUs: Long) -> Unit = { _, _ -> },
) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val samples = LookaheadSamples { holder, buffer -> readSource(holder, buffer, 0) }

    /** One render pass's samples, sent to Rust in one call. */
    private val batch = ArrayList<AssSample>()
    private var trackKey: String? = null
    private var sentUs = Long.MIN_VALUE
    private var sentPlaying = false
    private var latestUs = 0L

    override fun getName(): String = "AssTextRenderer"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        when {
            isRawSsa(format) && fullStyling() && overlay() != null -> C.FORMAT_HANDLED
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
        formats.firstOrNull()?.let(::register)
    }

    private fun register(format: Format) {
        val key = assTrackKey(format)
        trackKey = key
        overlay()?.let {
            it.addTrack(key, assHeader(format.initializationData))
            it.select(key)
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
        samples.reset()
        sentUs = Long.MIN_VALUE
        // Enabling runs this after onStreamChanged too, so a start, seek or track switch all land here.
        val mediaUs = positionUs - streamOffsetUs
        trackKey?.let { if (mediaUs > 0) linesNeeded(it, mediaUs) }
    }

    override fun onDisabled() {
        // Only its own track: a sidecar picked meanwhile may already be selected (docs/18 §3.2).
        trackKey?.let { key -> overlay()?.unselect(key) }
        trackKey = null
        sentUs = Long.MIN_VALUE
        samples.reset()
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val sink = overlay() ?: return
        val mediaUs = positionUs - streamOffsetUs
        val onFormat = { format: Format ->
            send(sink)
            register(format)
        }
        samples.drain(mediaUs, streamOffsetUs, onFormat, MAX_SAMPLES_PER_RENDER) { timeUs, bytes -> batch += AssSample(timeUs, bytes) }
        send(sink)
        latestUs = mediaUs
        val playing = state == STATE_STARTED
        if (shouldSendPosition(sentUs, sentPlaying, mediaUs, playing)) {
            sink.setPosition(mediaUs, playing)
            sentUs = mediaUs
            sentPlaying = playing
        }
    }

    private fun send(sink: AssOverlay) {
        if (batch.isEmpty()) return
        try {
            trackKey?.let { sink.addSamples(it, batch) }
        } finally {
            batch.clear()
        }
    }

    /** Pausing freezes the Rust clock at the last rendered position instead of extrapolating on. */
    override fun onStopped() {
        overlay()?.setPosition(latestUs, false)
        sentUs = latestUs
        sentPlaying = false
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = samples.ended
}
