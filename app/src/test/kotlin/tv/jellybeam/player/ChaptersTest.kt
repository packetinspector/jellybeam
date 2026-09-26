package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.ChapterInfoFfi

/** [Chapters] is pure Kotlin (docs/12 ranked build order item 9) -- exercised directly, no
 * Compose/ViewModel needed.
 */
class ChaptersTest {

    private fun chapter(name: String, startTicks: Long) = ChapterInfoFfi(name = name, startPositionTicks = startTicks, imageTag = null)

    private val chapters = listOf(
        chapter("Cold Open", 0L),
        chapter("Main Titles", 100_000_000L), // 10s
        chapter("Act One", 200_000_000L), // 20s
    )

    @Test
    fun `nameAt finds the covering chapter`() {
        assertEquals("Cold Open", Chapters.nameAt(chapters, 0L))
        assertEquals("Cold Open", Chapters.nameAt(chapters, 50_000_000L))
        assertEquals("Main Titles", Chapters.nameAt(chapters, 150_000_000L))
        assertEquals("Act One", Chapters.nameAt(chapters, 200_000_000L))
        assertEquals("Act One", Chapters.nameAt(chapters, 999_000_000L))
    }

    @Test
    fun `nameAt is null with no chapters at all`() {
        assertNull(Chapters.nameAt(emptyList(), 0L))
    }

    @Test
    fun `currentChapterIndex finds the covering chapter's index, same rule as nameAt`() {
        assertEquals(0, Chapters.currentChapterIndex(chapters, 0L))
        assertEquals(0, Chapters.currentChapterIndex(chapters, 50_000_000L))
        assertEquals(1, Chapters.currentChapterIndex(chapters, 150_000_000L))
        assertEquals(2, Chapters.currentChapterIndex(chapters, 200_000_000L))
        assertEquals(2, Chapters.currentChapterIndex(chapters, 999_000_000L))
    }

    @Test
    fun `currentChapterIndex is null with no chapters at all`() {
        assertNull(Chapters.currentChapterIndex(emptyList(), 0L))
    }

    @Test
    fun `tickFractions skips the chapter at position zero and computes the rest as fractions of duration`() {
        val durationTicks = 400_000_000L // 40s
        val fractions = Chapters.tickFractions(chapters, durationTicks)
        assertEquals(listOf(0.25f, 0.5f), fractions)
    }

    @Test
    fun `tickFractions is empty with an unknown or non-positive duration`() {
        assertEquals(emptyList<Float>(), Chapters.tickFractions(chapters, null))
        assertEquals(emptyList<Float>(), Chapters.tickFractions(chapters, 0L))
        assertEquals(emptyList<Float>(), Chapters.tickFractions(chapters, -1L))
    }

    @Test
    fun `tickFractions and jumpTargetTicks agree on where every chapter sits`() {
        // [ProgressBar] draws the fill and each tick off the same `duration`
        // local in one Canvas call, so a tick's fraction times duration must
        // reconstruct that chapter's start, and jumpTargetTicks landing there
        // must reproduce the same fraction.
        val durationTicks = 400_000_000L // 40s
        val fractions = Chapters.tickFractions(chapters, durationTicks)
        val nonZeroStarts = chapters.map { it.startPositionTicks }.filter { it > 0L }
        assertEquals(nonZeroStarts.size, fractions.size)
        nonZeroStarts.zip(fractions).forEach { (startTicks, fraction) ->
            val reconstructed = (fraction.toDouble() * durationTicks).toLong()
            assertEquals(startTicks, reconstructed)
            val nextStart = nonZeroStarts.firstOrNull { it > startTicks }
            assertEquals(nextStart, Chapters.jumpTargetTicks(chapters, startTicks, forward = true))
        }
    }

    @Test
    fun `jumpTargetTicks is null for an empty chapter list`() {
        assertNull(Chapters.jumpTargetTicks(emptyList(), 50_000_000L, forward = true))
        assertNull(Chapters.jumpTargetTicks(emptyList(), 50_000_000L, forward = false))
    }

    @Test
    fun `jumpTargetTicks forward finds the next chapter start, null past the last one`() {
        assertEquals(100_000_000L, Chapters.jumpTargetTicks(chapters, 0L, forward = true))
        assertEquals(200_000_000L, Chapters.jumpTargetTicks(chapters, 100_000_000L, forward = true))
        assertNull("already at/past the last chapter", Chapters.jumpTargetTicks(chapters, 200_000_000L, forward = true))
        assertNull(Chapters.jumpTargetTicks(chapters, 999_000_000L, forward = true))
    }

    @Test
    fun `jumpTargetTicks backward within the grace window jumps to the previous chapter, falling back to zero`() {
        assertEquals(100_000_000L, Chapters.jumpTargetTicks(chapters, 200_000_000L, forward = false))
        assertEquals(0L, Chapters.jumpTargetTicks(chapters, 100_000_000L, forward = false))
        assertEquals("no earlier chapter -- restart the item", 0L, Chapters.jumpTargetTicks(chapters, 50_000_000L, forward = false))
    }

    @Test
    fun `jumpTargetTicks has a 500ms forward dead zone around the current position`() {
        val atMainTitles = 100_000_000L
        assertEquals(200_000_000L, Chapters.jumpTargetTicks(chapters, atMainTitles + 1_000_000L, forward = true)) // 100ms past
    }

    @Test
    fun `jumpTargetTicks backward grace rule -- within 5s of the current chapter's start goes to the PREVIOUS chapter`() {
        val twoSecondsIntoMainTitles = 120_000_000L
        assertEquals(0L, Chapters.jumpTargetTicks(chapters, twoSecondsIntoMainTitles, forward = false))

        // Exactly at the 5s boundary still counts as "within".
        val exactlyFiveSecondsIn = 150_000_000L // Main Titles start (100M) + 50_000_000 (5s)
        assertEquals(0L, Chapters.jumpTargetTicks(chapters, exactlyFiveSecondsIn, forward = false))
    }

    @Test
    fun `jumpTargetTicks backward grace rule -- more than 5s past the current chapter's start restarts it`() {
        val sixSecondsIntoMainTitles = 160_000_000L
        assertEquals(100_000_000L, Chapters.jumpTargetTicks(chapters, sixSecondsIntoMainTitles, forward = false))

        // Same rule for the last chapter: no next-chapter or 0L fallback applies.
        val farIntoActOne = 999_000_000L
        assertEquals(200_000_000L, Chapters.jumpTargetTicks(chapters, farIntoActOne, forward = false))
    }

    @Test
    fun `menu title drops blank and number-only names`() {
        assertNull(Chapters.menuTitle(null))
        assertNull(Chapters.menuTitle("  "))
        assertNull(Chapters.menuTitle("Chapter 12"))
        assertNull(Chapters.menuTitle("chapter 01"))
        assertNull(Chapters.menuTitle("Chapter #3 "))
    }

    @Test
    fun `menu title keeps a real title verbatim`() {
        assertEquals("The Heist", Chapters.menuTitle("The Heist"))
        assertEquals("Chapter 3: The Heist", Chapters.menuTitle("Chapter 3: The Heist"))
    }
}
