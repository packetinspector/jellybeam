package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SidecarCuesTest {
    private fun cue(text: String) = Cue.Builder().setText(text).build()

    private fun entry(text: String, startMs: Long, durationMs: Long?) = CuesWithTiming(
        listOf(cue(text)),
        startMs * 1000,
        durationMs?.let { it * 1000 } ?: C.TIME_UNSET,
    )

    private fun SidecarCues.textAt(ms: Long) = cuesAt(ms * 1000).map { it.text.toString() }

    @Test
    fun `cues show inside their span and nowhere else`() {
        val cues = SidecarCues.of(listOf(entry("a", 1_000, 1_000), entry("b", 3_000, 500)))!!
        assertEquals(emptyList<String>(), cues.textAt(0))
        assertEquals(listOf("a"), cues.textAt(1_000))
        assertEquals(listOf("a"), cues.textAt(1_999))
        assertEquals(emptyList<String>(), cues.textAt(2_000))
        assertEquals(listOf("b"), cues.textAt(3_200))
        assertEquals(emptyList<String>(), cues.textAt(9_000))
    }

    @Test
    fun `overlapping cues stack in start order`() {
        val cues = SidecarCues.of(listOf(entry("late", 1_500, 1_000), entry("early", 1_000, 1_000)))!!
        assertEquals(listOf("early"), cues.textAt(1_200))
        assertEquals(listOf("early", "late"), cues.textAt(1_700))
        assertEquals(listOf("late"), cues.textAt(2_200))
    }

    @Test
    fun `an entry without a duration lasts until the next one starts`() {
        val cues = SidecarCues.of(listOf(entry("a", 0, null), entry("b", 2_000, null)))!!
        assertEquals(listOf("a"), cues.textAt(1_999))
        assertEquals(listOf("b"), cues.textAt(50_000))
    }

    @Test
    fun `an empty entry ends the open-ended one before it`() {
        val cues = SidecarCues.of(listOf(entry("a", 0, null), CuesWithTiming(emptyList(), 1_000_000, C.TIME_UNSET), entry("b", 5_000, 500)))!!
        assertEquals(listOf("a"), cues.textAt(999))
        assertEquals(emptyList<String>(), cues.textAt(2_000))
        assertEquals(listOf("b"), cues.textAt(5_100))
    }

    @Test
    fun `next change is the following boundary, then never`() {
        val cues = SidecarCues.of(listOf(entry("a", 1_000, 1_000)))!!
        assertEquals(1_000_000L, cues.nextChangeUs(0))
        assertEquals(2_000_000L, cues.nextChangeUs(1_500_000))
        assertEquals(Long.MAX_VALUE, cues.nextChangeUs(2_000_000))
    }

    @Test
    fun `one segment hands back one list instance`() {
        val cues = SidecarCues.of(listOf(entry("a", 1_000, 1_000)))!!
        assertSame(cues.cuesAt(1_100_000), cues.cuesAt(1_900_000))
    }

    @Test
    fun `a file with nothing to show is no sidecar`() {
        assertNull(SidecarCues.of(emptyList()))
        assertNull(SidecarCues.of(listOf(CuesWithTiming(emptyList(), 0, 1_000_000))))
        assertNull(SidecarCues.of(listOf(entry("zero", 1_000, 0))))
    }

    @Test
    fun `the feed sleeps until the change, within its bounds`() {
        assertEquals(16L, SidecarCues.pollDelayMs(0))
        assertEquals(40L, SidecarCues.pollDelayMs(40_000))
        assertEquals(SidecarCues.MAX_POLL_MS, SidecarCues.pollDelayMs(Long.MAX_VALUE - 5))
        assertEquals(16L, SidecarCues.pollDelayMs(Long.MIN_VALUE + 5))
    }

    @Test
    fun `a fast rate shortens the wall-clock sleep so a short cue is not skipped`() {
        assertEquals(50L, SidecarCues.pollDelayMs(100_000, rate = 2f))
        assertEquals(100L, SidecarCues.pollDelayMs(50_000, rate = 0.5f))
    }

    @Test
    fun `heavy overlap keeps every segment bounded`() {
        // 20,000 captions with distinct starts and one shared end: the worst case for copying.
        val entries = (0 until 20_000).map { i -> CuesWithTiming(listOf(cue("c$i")), i * 1_000L, (20_000L - i) * 1_000L + 1_000L) }
        val cues = SidecarCues.of(entries)!!
        assertEquals(SidecarCues.MAX_VISIBLE, cues.cuesAt(19_999_000L).size)
        assertEquals(listOf("c0", "c1"), cues.cuesAt(1_500L).map { it.text.toString() })
        // Once the cap is full, later starts reuse the same list rather than allocating new ones.
        assertSame(cues.cuesAt(10_000_000L), cues.cuesAt(15_000_000L))
    }

    @Test
    fun `parser output is trimmed to what can show and refused past its budget`() {
        val sink = ParsedCueSink(maxSeen = 30)
        sink.accept(CuesWithTiming((0 until 20).map { cue("s$it") }, 0, 1_000))
        assertEquals(SidecarCues.MAX_VISIBLE, sink.entries.single().cues.size)
        assertThrows(ParsedCueSink.TooLarge::class.java) {
            sink.accept(CuesWithTiming((0 until 20).map { cue("t$it") }, 1_000, 1_000))
        }
    }

}
