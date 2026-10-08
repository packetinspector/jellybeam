package tv.jellybeam.player

/** Whether the loaded file can seek (docs/12 §9): unknown until Media3 has a real timeline. */
enum class Seekability { UNKNOWN, SEEKABLE, UNSEEKABLE }

/** What a seek request does for this file (docs/12 §9, unseekable files). */
enum class SeekRoute {
    /** Hand the seek to the player. */
    SEEK,
    /** Drop the seek and tell the viewer; a seekTo on an unseekable file restarts it from 0:00. */
    NOTICE,
    /** Drop the seek quietly: seekability isn't known yet, and guessing could restart the file. */
    DROP,
}

/**
 * Pure unseekable-file rules (docs/12 §9), shared by every seek path so none restarts the file.
 * [Seekability] comes from Media3's timeline window: a placeholder window is still unknown.
 */
object SeekGuard {
    /** How long the "can't seek" notice stays up (docs/12 §9). */
    const val NOTICE_MS = 3_000L

    fun route(seekability: Seekability): SeekRoute = when (seekability) {
        Seekability.SEEKABLE -> SeekRoute.SEEK
        Seekability.UNSEEKABLE -> SeekRoute.NOTICE
        Seekability.UNKNOWN -> SeekRoute.DROP
    }

    /** A skip pill or auto-skip is a seek, so only a known-seekable file gets them (docs/12 §14). */
    fun segmentDecision(decision: SegmentDecision, seekability: Seekability): SegmentDecision =
        if (seekability == Seekability.SEEKABLE) decision else SegmentDecision.NOTHING

    /** Chapters only move the playhead, so the button is dropped for an unseekable file (docs/12 §8). */
    fun chaptersButtonVisible(hasChapters: Boolean, seekability: Seekability): Boolean =
        hasChapters && seekability != Seekability.UNSEEKABLE

    /** Where a reconnect resumes: an unseekable file can only restart, so it asks for 0:00 outright. */
    fun recoveryPositionTicks(seekability: Seekability, positionTicks: Long): Long =
        if (seekability == Seekability.UNSEEKABLE) 0L else positionTicks

    /** The skip buttons dim only once the file is known not to seek. */
    fun skipButtonsDimmed(seekability: Seekability): Boolean = seekability == Seekability.UNSEEKABLE
}
