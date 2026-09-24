package tv.jellybeam.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi

/**
 * Pins the docs/12 §11 transport contract: one fetch in flight and one queued, hold-last, the
 * release tile jumping the queue, and abandon only on the rule's say-so. Fetches are scripted
 * [CompletableDeferred]s so completion order is the test's to choose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrickplayPreviewerTest {
    private val meta = TrickplayMetaFfi(width = 320u, height = 180u, tileWidth = 10u, tileHeight = 10u, intervalMs = 10_000u, thumbnailCount = 1_000u)

    private class ScriptedFetcher : SheetFetcher {
        val started = mutableListOf<String>()
        val pending = mutableMapOf<String, CompletableDeferred<ByteArray?>>()
        override suspend fun fetch(url: String): ByteArray? {
            started += url
            return pending.getOrPut(url) { CompletableDeferred() }.await()
        }

        fun complete(url: String, bytes: ByteArray? = url.toByteArray()) {
            pending.getOrPut(url) { CompletableDeferred() }.complete(bytes)
        }
    }

    /** Decodes to "<sheet-bytes>@x,y" so a test can read which tile of which sheet was shown. */
    private val decoder = TileDecoder<String> { sheet, x, y, _, _ -> "${String(sheet)}@$x,$y" }

    private fun tile(sheet: Int, x: Int = 0, y: Int = 0) = TrickplayTileFfi(sheet.toUInt(), x.toUInt(), y.toUInt())

    private fun url(sheet: Int) = "sheet-$sheet"

    /** Puts [sheet]'s bytes in memory without a fetch so a test can make it "held". */
    private fun TrickplayPreviewer<String>.warmSheetForTest(sheet: Int) = holdSheetBytes(sheet, url(sheet).toByteArray())

    private fun TestScope.previewer(
        fetcher: ScriptedFetcher,
        abandon: SheetAbandonRule = SheetAbandonRule { _, _, _ -> false },
    ): TrickplayPreviewer<String> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TrickplayPreviewer(fetcher, decoder, this, tileBytes = { it.length }, fetchDispatcher = dispatcher, decodeDispatcher = dispatcher).also {
            it.startSession(meta, urlFor = { index -> url(index.toInt()) }, abandonRule = abandon)
        }
    }

    @Test
    fun `show fetches the tile's sheet, decodes it and publishes the tile`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)

        p.show(tile(0, 640, 180))
        advanceUntilIdle()
        assertEquals(listOf(url(0)), fetcher.started)
        assertNull(p.tile.value)

        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals("sheet-0@640,180", p.tile.value)
    }

    @Test
    fun `a second tile on a held sheet decodes without a fetch, and the last tile holds meanwhile`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.show(tile(0))
        fetcher.complete(url(0))
        advanceUntilIdle()

        p.show(tile(0, 320, 0))
        assertEquals("sheet-0@0,0", p.tile.value) // hold-last until the decode lands
        advanceUntilIdle()
        assertEquals("sheet-0@320,0", p.tile.value)
        assertEquals(1, fetcher.started.size)
    }

    @Test
    fun `want keeps one fetch in flight and one queued, replacing the queue each call`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)

        p.want(listOf(0, 1), 5_000L, GlideDirection.FORWARD)
        advanceUntilIdle()
        assertEquals(listOf(url(0)), fetcher.started)

        p.want(listOf(0, 2), 15_000L, GlideDirection.FORWARD) // queue swapped from 1 to 2
        advanceUntilIdle()
        assertEquals(listOf(url(0)), fetcher.started)

        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(2)), fetcher.started)
        assertEquals(2, p.fetchesStarted)
        assertEquals(0, p.fetchesCancelled)
        p.endSession() // sheet 2 is still in flight by design
    }

    @Test
    fun `an in-flight prefetch off the want-list is cancelled only when the abandon rule says so`() = runTest {
        val fetcher = ScriptedFetcher()
        val abandoned = mutableListOf<Int>()
        val p = previewer(fetcher, SheetAbandonRule { sheet, targetMs, _ -> abandoned += sheet; targetMs > 5_000_000L })

        p.want(listOf(0, 1), 0L, GlideDirection.FORWARD)
        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(1)), fetcher.started) // sheet 0 held, sheet 1 in flight

        p.want(listOf(0), 500_000L, GlideDirection.BACK) // reversed inside sheet 0: rule says keep 1
        advanceUntilIdle()
        assertEquals(0, p.fetchesCancelled)
        assertEquals(listOf(url(0), url(1)), fetcher.started)

        p.warmSheetForTest(6)
        p.want(listOf(6), 6_000_000L, GlideDirection.FORWARD) // sheet 6 held, 1 far behind: rule drops it
        advanceUntilIdle()
        assertEquals(1, p.fetchesCancelled)
        assertEquals(listOf(1, 1), abandoned)
        p.endSession()
    }

    @Test
    fun `prioritize cancels an unrelated in-flight fetch and fetches the release sheet first`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.want(listOf(0, 1), 0L, GlideDirection.FORWARD)
        advanceUntilIdle()

        p.prioritize(tile(4, 0, 360))
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(4)), fetcher.started)
        assertEquals(1, p.fetchesCancelled)

        fetcher.complete(url(4))
        advanceUntilIdle()
        assertEquals("sheet-4@0,360", p.tile.value)
        // The queued sheet 1 was dropped by the release.
        assertEquals(2, fetcher.started.size)
    }

    @Test
    fun `a decode that lands for a tile no longer wanted is not shown`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.show(tile(0))
        p.show(tile(1)) // sheet 0 still in flight, queue becomes sheet 1
        advanceUntilIdle()

        fetcher.complete(url(0))
        advanceUntilIdle()
        assertNull(p.tile.value) // sheet 0's tile is cached, not shown

        fetcher.complete(url(1))
        advanceUntilIdle()
        assertEquals("sheet-1@0,0", p.tile.value)
    }

    @Test
    fun `reset blanks the tile and endSession forgets sheets`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.show(tile(0))
        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals("sheet-0@0,0", p.tile.value)

        p.reset()
        assertNull(p.tile.value)

        p.endSession()
        p.show(tile(0))
        advanceUntilIdle()
        assertNull(p.tile.value)
        assertEquals(1, fetcher.started.size) // no session bound: nothing fetched
    }

    @Test
    fun `the sheet under the target preempts a farther in-flight prefetch`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.want(listOf(0, 1), 990_000L, GlideDirection.FORWARD)
        advanceUntilIdle()
        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(1)), fetcher.started) // prefetching sheet 1

        // The glide overshoots into sheet 2 before sheet 1 lands: 2 is now current and missing.
        p.want(listOf(2, 3), 2_050_000L, GlideDirection.FORWARD)
        advanceUntilIdle()
        assertEquals(1, p.fetchesCancelled)
        assertEquals(listOf(url(0), url(1), url(2)), fetcher.started)
        p.endSession()
    }

    @Test
    fun `a held current sheet never cancels an in-flight neighbour`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.want(listOf(0, 1), 0L, GlideDirection.FORWARD)
        fetcher.complete(url(0))
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(1)), fetcher.started)

        p.want(listOf(0, 1), 500_000L, GlideDirection.FORWARD) // still in sheet 0
        advanceUntilIdle()
        assertEquals(0, p.fetchesCancelled)
        p.endSession()
    }

    @Test
    fun `warm fetches a sheet without changing the shown tile, and yields to show`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.warm(0)
        advanceUntilIdle()
        assertEquals(listOf(url(0)), fetcher.started)
        fetcher.complete(url(0))
        advanceUntilIdle()
        assertNull(p.tile.value)

        p.show(tile(0, 320, 0)) // no fetch: the warmed bytes decode directly
        advanceUntilIdle()
        assertEquals("sheet-0@320,0", p.tile.value)
        assertEquals(1, fetcher.started.size)
    }

    @Test
    fun `cancelling a fetch cancels the fetcher's own operation`() = runTest {
        val cancelled = mutableListOf<String>()
        val fetcher = SheetFetcher { url ->
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cancelled += url }
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p = TrickplayPreviewer(fetcher, decoder, this, tileBytes = { it.length }, fetchDispatcher = dispatcher, decodeDispatcher = dispatcher)
        p.startSession(meta, urlFor = { url(it.toInt()) }, abandonRule = { _, _, _ -> true })
        p.want(listOf(0), 0L, GlideDirection.FORWARD)
        advanceUntilIdle()
        p.want(listOf(5), 5_000_000L, GlideDirection.FORWARD)
        advanceUntilIdle()
        assertEquals(listOf(url(0)), cancelled)
        p.endSession()
        advanceUntilIdle()
        assertEquals(listOf(url(0), url(5)), cancelled)
    }

    @Test
    fun `the tile cache is bounded by bytes, not entries`() = runTest {
        val fetcher = ScriptedFetcher()
        // Each decoded tile weighs its string length (~12 bytes); budget is 8 MB, so fill it
        // with a tile that weighs the whole budget and confirm the earlier one is gone.
        val heavy = TileDecoder<String> { sheet, x, y, _, _ -> if (x == 0) "x".repeat(TrickplayTransport.TILE_MEMORY_BYTES) else "${String(sheet)}@$x,$y" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p = TrickplayPreviewer(fetcher, heavy, this, tileBytes = { it.length }, fetchDispatcher = dispatcher, decodeDispatcher = dispatcher)
        p.startSession(meta, urlFor = { url(it.toInt()) }, abandonRule = { _, _, _ -> false })
        p.show(tile(0, 320, 0))
        fetcher.complete(url(0))
        advanceUntilIdle()
        p.show(tile(0, 0, 0)) // the heavy tile evicts the light one
        advanceUntilIdle()
        p.show(tile(0, 320, 0))
        // Re-decoding is the observable: the light tile is no longer cached, so the shown value
        // only changes after the (serial) decode runs again.
        assertEquals(TrickplayTransport.TILE_MEMORY_BYTES, p.tile.value?.length)
        advanceUntilIdle()
        assertEquals("sheet-0@320,0", p.tile.value)
    }

    @Test
    fun `a failed fetch leaves the tile unchanged and the pipeline usable`() = runTest {
        val fetcher = ScriptedFetcher()
        val p = previewer(fetcher)
        p.show(tile(0))
        advanceUntilIdle()
        fetcher.complete(url(0), bytes = null)
        advanceUntilIdle()
        assertNull(p.tile.value)

        p.show(tile(1))
        fetcher.complete(url(1))
        advanceUntilIdle()
        assertEquals("sheet-1@0,0", p.tile.value)
    }
}
