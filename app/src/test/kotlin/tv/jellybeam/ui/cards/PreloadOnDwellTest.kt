package tv.jellybeam.ui.cards

import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure timing/dedup coverage for [PreloadOnDwell], the debounce that decides *when*
 * [tv.jellybeam.data.CoreGateway.preloadPlayback] fires; whether a fired call does anything is Rust's
 * own gate, so these tests only assert on [FakeCoreGateway.preloadPlaybackCalls]. `runTest`'s
 * virtual clock drives [PreloadOnDwell.dwellMillis] without a real 400ms wait.
 *
 * Every instance is built on `backgroundScope`, not `this` -- a fired card's re-arm loop only ever
 * ends via cancellation (docs/18), so a test that leaves one focused would otherwise hand `runTest`
 * a coroutine that never completes on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreloadOnDwellTest {

    @Test
    fun `sustained focus past the dwell threshold fires exactly one preload`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `focus loss before the dwell threshold elapses cancels the pending preload`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS / 2)
        preload.onCardUnfocused("item-1")
        advanceTimeBy(PRELOAD_DWELL_MILLIS)

        assertEquals(emptyList<String>(), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `dwelling on a non-playable item type never fires`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("series-1", "Series")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(emptyList<String>(), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `a duplicate onCardFocused call for the same still-pending item does not restart the timer`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS / 2)
        // Compose can re-invoke onFocusChanged for the same focus state; this must not push the
        // fire time further out.
        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS / 2 + 1)

        assertEquals(listOf("item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `once fired, the same item does not re-fire while it stays focused`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)
        // A later onFocusChanged for the same still-focused card must not fire a second time.
        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `focusing a different item cancels the previous item's pending timer`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS / 2)
        preload.onCardFocused("item-2", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("item-2"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `a fast focus hop where the old card's unfocus arrives after the new card's focus never cancels the new timer`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        // Compose does not guarantee unfocus-then-focus ordering; the new card's focus can arrive
        // before the old card's unfocus.
        preload.onCardFocused("item-1", "Movie")
        preload.onCardFocused("item-2", "Movie")
        preload.onCardUnfocused("item-1")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("item-2"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `re-focusing an item after it fired and lost focus fires again`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)
        preload.onCardUnfocused("item-1")
        preload.onCardFocused("item-2", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS / 2)
        preload.onCardUnfocused("item-2")
        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("item-1", "item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `an Episode card is playable the same as a Movie card`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onCardFocused("episode-1", "Episode")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)

        assertEquals(listOf("episode-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `a known playable Detail action fires immediately and deduplicates focus callbacks`() = runTest {
        val gateway = FakeCoreGateway()
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway)

        preload.onPlayableActionFocused("episode-1")
        runCurrent()
        preload.onPlayableActionFocused("episode-1")
        runCurrent()

        assertEquals(listOf("episode-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `a card held in focus past the TTL re-fires the preload`() = runTest {
        val gateway = FakeCoreGateway()
        val reArmMillis = 1_000L
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway, reArmMillis = reArmMillis)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)
        advanceTimeBy(reArmMillis)
        advanceTimeBy(reArmMillis)

        assertEquals(listOf("item-1", "item-1", "item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `losing focus before the TTL elapses cancels the re-arm timer`() = runTest {
        val gateway = FakeCoreGateway()
        val reArmMillis = 1_000L
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway, reArmMillis = reArmMillis)

        preload.onPlayableActionFocused("item-1")
        runCurrent()
        preload.onCardUnfocused("item-1")
        advanceTimeBy(reArmMillis * 3)

        assertEquals(listOf("item-1"), gateway.preloadPlaybackCalls)
    }

    @Test
    fun `re-focusing after an unfocus dwells fresh instead of resuming the old re-arm timer`() = runTest {
        val gateway = FakeCoreGateway()
        val reArmMillis = 1_000L
        val preload = PreloadOnDwell(scope = backgroundScope, gateway = gateway, reArmMillis = reArmMillis)

        preload.onCardFocused("item-1", "Movie")
        advanceTimeBy(PRELOAD_DWELL_MILLIS + 1)
        preload.onCardUnfocused("item-1")
        preload.onCardFocused("item-1", "Movie")
        // Only a fresh dwell can fire again; the old re-arm timer must not still be running.
        advanceTimeBy(PRELOAD_DWELL_MILLIS - 1)

        assertEquals(listOf("item-1"), gateway.preloadPlaybackCalls)
    }
}
