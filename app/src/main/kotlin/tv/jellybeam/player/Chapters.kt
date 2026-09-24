package tv.jellybeam.player

import uniffi.jellybeam_core.ChapterInfoFfi

/** Chapter-marker logic (docs/12-osd-ux-spec.md, build order item 9), free of Compose/ViewModel
 * deps, same split as [SkipSegment].
 */
object Chapters {
    /** The last chapter whose start is at or before [positionTicks] (docs/12 "Seek UX"); `null` if
     * none.
     */
    fun nameAt(chapters: List<ChapterInfoFfi>, positionTicks: Long): String? =
        chapters.filter { it.startPositionTicks <= positionTicks }
            .maxByOrNull { it.startPositionTicks }
            ?.name

    /** Index (into [chapters], not re-sorted) of the current chapter, same rule as [nameAt]; feeds
     * the Chapters menu's current-chapter dot (docs/15-focus-and-selection.md §1.2).
     */
    fun currentChapterIndex(chapters: List<ChapterInfoFfi>, positionTicks: Long): Int? =
        chapters.withIndex()
            .filter { it.value.startPositionTicks <= positionTicks }
            .maxByOrNull { it.value.startPositionTicks }
            ?.index

    /**
     * Scrub-bar tick-mark positions (docs/12 "Scrub row"), as fractions of the track's width.
     * Excludes a chapter starting at position 0 (would draw on top of the track's left edge).
     * `null`/non-positive [durationTicks] -> no ticks.
     */
    fun tickFractions(chapters: List<ChapterInfoFfi>, durationTicks: Long?): List<Float> {
        if (durationTicks == null || durationTicks <= 0L) return emptyList()
        return chapters
            .filter { it.startPositionTicks > 0L }
            .map { (it.startPositionTicks.toDouble() / durationTicks.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    }

    /** 500ms in ticks -- [jumpTargetTicks]'s forward dead zone, so a press at/past a boundary
     * doesn't re-target the chapter already playing.
     */
    private const val JUMP_EPSILON_TICKS = 5_000_000L // 500ms * 10_000 ticks/ms

    /**
     * 5s in ticks -- prev-chapter's grace window (the standard media-player "previous track" rule).
     * Past this far into the current chapter, prev-chapter restarts it; within it, prev-chapter
     * jumps
     * to the chapter before.
     */
    private const val CHAPTER_BACK_GRACE_TICKS = 50_000_000L // 5s * 10_000_000 ticks/sec

    /**
     * Prev/next-chapter transport button target (docs/12 controls row); `null` for an empty
     * [chapters]
     * list or [forward] already at/past the last chapter.
     *
     * [forward]: the first chapter start more than [JUMP_EPSILON_TICKS] after [positionTicks].
     * Backward: past [CHAPTER_BACK_GRACE_TICKS] into the current chapter, restart it; otherwise
     * jump
     * to the chapter before, falling back to `0L` when there's none -- never `null` once non-empty.
     */
    fun jumpTargetTicks(chapters: List<ChapterInfoFfi>, positionTicks: Long, forward: Boolean): Long? {
        if (chapters.isEmpty()) return null
        val starts = chapters.map { it.startPositionTicks }.sorted()
        return if (forward) {
            starts.firstOrNull { it > positionTicks + JUMP_EPSILON_TICKS }
        } else {
            val currentChapterStart = starts.lastOrNull { it <= positionTicks } ?: return 0L
            if (positionTicks - currentChapterStart > CHAPTER_BACK_GRACE_TICKS) {
                currentChapterStart
            } else {
                starts.lastOrNull { it < currentChapterStart } ?: 0L
            }
        }
    }
}
