package tv.jellybeam.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import java.util.SortedMap
import java.util.TreeMap
import kotlin.math.abs
import tv.jellybeam.player.causes

/**
 * docs/18 §5.4: one Matroska file's seek points (its primary track's cues, absolute file offsets) and the
 * facts a later start checks before using them in place of the Cues. [firstClusterPos] is the first
 * Cluster's content position; [durationUs] is the duration Media3's own seek map carried; [subtitleCues]
 * are the SSA tracks' Cues entries (docs/13), in [timecodeScaleNs].
 */
class SeekIndex(
    val contentLength: Long,
    val validator: Long,
    val segmentStart: Long,
    val firstClusterPos: Long,
    val durationUs: Long,
    val timecodeScaleNs: Long,
    val timesUs: LongArray,
    val positions: LongArray,
    val subtitleCues: List<SubtitleCue>,
)

/**
 * One load's place in the seek-index cache: [hit] is a stored index this file may use instead of its
 * Cues; [persist] says this load writes the index it reads, false when one for this file is stored.
 */
class SeekIndexSlot(
    val store: SeekIndexStore,
    val key: String,
    val contentLength: Long,
    val validator: Long,
    val hit: SeekIndex?,
    val persist: Boolean,
)

/**
 * docs/18 §5.4: answers exactly as Media3's MatroskaSeekMap (its ChunkIndex) does over the same cues: the
 * point at or before a time and, between points, the next one, which PREVIOUS_SYNC snapping needs.
 */
@OptIn(UnstableApi::class)
internal class CachedSeekMap(val index: SeekIndex, private val durationUs: Long) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val times = index.timesUs
        val i = Util.binarySearchFloor(times, timeUs, true, true)
        val at = SeekPoint(times[i], index.positions[i])
        if (at.timeUs >= timeUs || i == times.lastIndex) return SeekMap.SeekPoints(at)
        return SeekMap.SeekPoints(at, SeekPoint(times[i + 1], index.positions[i + 1]))
    }
}

/**
 * docs/18 §5.4: Media3 MatroskaExtractor's seek track: the last default video track, else the first video,
 * else the last default audio, else the first audio, else the lowest-numbered track; -1 with none.
 * [types] holds the tracks Media3 output, by number; a track absent from [defaults] is default.
 */
internal fun primarySeekTrack(types: SortedMap<Int, Int>, defaults: Map<Int, Boolean>): Int {
    var defaultVideo = -1
    var firstVideo = -1
    var defaultAudio = -1
    var firstAudio = -1
    for ((number, type) in types) {
        val isDefault = defaults[number] ?: true
        when (type) {
            C.TRACK_TYPE_VIDEO -> {
                if (isDefault) defaultVideo = number
                if (firstVideo == -1) firstVideo = number
            }
            C.TRACK_TYPE_AUDIO -> {
                if (isDefault) defaultAudio = number
                if (firstAudio == -1) firstAudio = number
            }
        }
    }
    return listOf(defaultVideo, firstVideo, defaultAudio, firstAudio).firstOrNull { it != -1 } ?: types.keys.firstOrNull() ?: -1
}

/** Whether a stored index still describes the file being read: same segment and first cluster, duration within 1 ms when known. */
internal fun cachedIndexFits(index: SeekIndex, segmentStart: Long, firstClusterPos: Long, liveDurationUs: Long): Boolean =
    index.segmentStart == segmentStart &&
        index.firstClusterPos == firstClusterPos &&
        (liveDurationUs == C.TIME_UNSET || abs(liveDurationUs - index.durationUs) <= DURATION_TOLERANCE_US)

/** Whether [map] answers every point of [index] with that point, as it would after Media3's own Cues pass. */
@OptIn(UnstableApi::class)
internal fun indexAgreesWith(index: SeekIndex, map: SeekMap): Boolean =
    index.timesUs.indices.all { map.getSeekPoints(index.timesUs[it]).first == SeekPoint(index.timesUs[it], index.positions[it]) }

/**
 * docs/18 §5.4: one Matroska load's seek index, fed by [AssMatroskaExtractor]'s element hooks and its
 * output. A cold load collects the primary track's cues and stores them; a hit swaps Media3's Unseekable
 * map for [CachedSeekMap] once the file's layout matches. Loader thread only.
 */
@OptIn(UnstableApi::class)
internal class MatroskaSeekIndexer(private val slot: SeekIndexSlot) {
    private val defaults = HashMap<Int, Boolean>()
    private val types = TreeMap<Int, Int>()
    private var firstClusterPos = -1L
    private var primary = -1
    private var timesUs = LongArray(INITIAL_CUES)
    private var positions = LongArray(INITIAL_CUES)
    private var size = 0
    private var abandoned = false
    private var finished = false
    private var seekable: SeekMap? = null

    /** Whether this load stores what it reads, so the SSA Cues are collected even with styling off. */
    val persists: Boolean get() = slot.persist

    fun trackEntry(number: Int, isDefault: Boolean) {
        defaults[number] = isDefault
    }

    /** A track Media3 output, with the type it derived from the codec. */
    fun track(number: Int, type: Int) {
        types[number] = type
    }

    fun cluster(contentPosition: Long) {
        if (firstClusterPos < 0) firstClusterPos = contentPosition
    }

    /** A Cues element starts: collection restarts, so a pass cut short by a seek leaves nothing behind. */
    fun cuesStarted() {
        size = 0
        abandoned = false
        primary = primarySeekTrack(types, defaults)
    }

    /** A complete CueTrackPositions; [position] is the cluster's absolute offset. */
    fun cue(track: Int, timeUs: Long, position: Long) {
        if (!slot.persist || finished || abandoned || track != primary) return
        if (size > 0) {
            val gap = timeUs - timesUs[size - 1]
            // Media3 sorts out-of-order cues; this index keeps file order, so such a file isn't stored.
            if (gap < 0) abandoned = true
            if (gap < MIN_CUE_GAP_US) return
        }
        if (size == MAX_SEEK_INDEX_CUES) {
            abandoned = true
            return
        }
        if (size == timesUs.size) {
            timesUs = timesUs.copyOf(size * 2)
            positions = positions.copyOf(size * 2)
        }
        timesUs[size] = timeUs
        positions[size] = position
        size++
    }

    /** After Media3 handled the Cues end: stores the index once, only from Cues read after the first cluster, which Media3 had to seek for. */
    fun cuesEnded(segmentStart: Long, subtitles: SubtitleCues?, timecodeScaleNs: Long) {
        val live = seekable ?: return
        if (!slot.persist || finished) return
        finished = true
        val ssa = subtitles?.entries().orEmpty()
        if (abandoned || firstClusterPos < 0 || ssa.size > MAX_STORED_SUBTITLE_CUES) return
        val n = chunkIndexLength(live.durationUs)
        if (n < 2 || (0 until n).any { positions[it] >= slot.contentLength }) return
        val index = SeekIndex(
            slot.contentLength, slot.validator, segmentStart, firstClusterPos, live.durationUs, timecodeScaleNs,
            timesUs.copyOf(n), positions.copyOf(n), ssa,
        )
        slot.store.save(slot.key) { index.takeIf { indexAgreesWith(it, live) } }
    }

    /** Media3's ChunkIndex drops trailing cues at or past the duration, keeping at least one. */
    private fun chunkIndexLength(durationUs: Long): Int {
        var last = size - 1
        while (last > 0 && timesUs[last] >= durationUs) last--
        return last + 1
    }

    /** docs/18 §5.4: [error] while reading with a stored index drops it and comes back marked, so the player retries cold; null off a hit. */
    fun miss(error: Exception): SeekIndexMiss? {
        if (slot.hit == null) return null
        slot.store.delete(slot.key)
        return SeekIndexMiss(error)
    }

    /**
     * The map Media3 emitted, or on a hit whose file still fits, the cached one; a seekable map is never replaced.
     * A hit whose layout moved throws, so the load fails as a [SeekIndexMiss] and retries cold.
     */
    fun seekMap(map: SeekMap, segmentStart: Long): SeekMap {
        if (map.isSeekable) {
            seekable = map
            return map
        }
        val hit = slot.hit ?: return map
        if (!cachedIndexFits(hit, segmentStart, firstClusterPos, map.durationUs)) {
            throw ParserException.createForMalformedContainer("seek index layout moved", null)
        }
        return CachedSeekMap(hit, if (map.durationUs == C.TIME_UNSET) hit.durationUs else map.durationUs)
    }
}

/**
 * docs/18 §5.4: a load that read with a stored seek index failed to parse; the index is already dropped.
 * A [ParserException], so the load policy ends this load rather than retry it with the same extractor.
 */
@OptIn(UnstableApi::class)
class SeekIndexMiss(cause: Throwable) : ParserException("seek index miss", cause, true, C.DATA_TYPE_MEDIA)

/** Whether [error] came from a load whose stored seek index failed. */
internal fun isSeekIndexMiss(error: Throwable?): Boolean = causes(error).any { it is SeekIndexMiss }

/** docs/18 §5.4: an index past this many kept cues is abandoned, so a hostile Cues can't grow memory or disk. */
const val MAX_SEEK_INDEX_CUES = 1 shl 16

/** docs/18 §5.4: a cue within this of the last kept one is dropped. */
const val MIN_CUE_GAP_US = 500_000L

private const val INITIAL_CUES = 256
private const val DURATION_TOLERANCE_US = 1_000L
