package tv.jellybeam.player

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import java.net.UnknownHostException
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.asFailure
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.data.leaveEverythingAloneDecision
import tv.jellybeam.ui.cards.testCard
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.ChapterInfoFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EpisodeNeighbors
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrickplayMetaFfi

/** Hard ceiling for every `runTest` below, so a hang (see [tearDown]'s doc) fails fast. */
private val TEST_TIMEOUT = 10.seconds

private fun samplePlan(
    itemId: String = "item-1",
    itemName: String = "Sample Movie",
    startPositionTicks: Long = 0L,
    runtimeTicks: Long? = 72_000_000_000L, // 2h
    itemType: String = "Movie",
    seriesId: String? = null,
    mediaSourceId: String = "src-1",
    // docs/18-playback-quality.md §2 default: Direct Play, no transcode/verdict.
    playMethod: PlayMethodFfi = PlayMethodFfi.DIRECT_PLAY,
    transcodeReason: String? = null,
    serverVerdict: String? = null,
    transcodeFallbackAllowed: Boolean = false,
) = PlaybackPlan(
    itemId = itemId,
    itemName = itemName,
    url = "http://server/Videos/$itemId/stream?static=true&api_key=tok",
    mediaSourceId = mediaSourceId,
    playSessionId = "session-1",
    startPositionTicks = startPositionTicks,
    runtimeTicks = runtimeTicks,
    container = "mkv",
    itemType = itemType,
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    seriesId = seriesId,
    playMethod = playMethod,
    transcodeReason = transcodeReason,
    serverVerdict = serverVerdict,
    transcodeFallbackAllowed = transcodeFallbackAllowed,
)

/** A small, realistic manifest matching [tv.jellybeam.data.fakeTrickplayLocate]'s expectations. */
private fun sampleTrickplayMeta(
    width: UInt = 320u,
    height: UInt = 180u,
    tileWidth: UInt = 10u,
    tileHeight: UInt = 10u,
    intervalMs: UInt = 10_000u,
    thumbnailCount: UInt = 1_000u,
) = TrickplayMetaFfi(
    width = width,
    height = height,
    tileWidth = tileWidth,
    tileHeight = tileHeight,
    intervalMs = intervalMs,
    thumbnailCount = thumbnailCount,
)

/** A minimal [ItemDetail]; every field but [container]/[chapters] is a harmless empty/null. */
private fun sampleItemDetail(
    container: String? = "mkv",
    chapters: List<ChapterInfoFfi> = emptyList(),
) = ItemDetail(
    id = "item-1",
    name = "Item",
    itemType = "Movie",
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    premiereDate = null,
    playCount = 0,
    lastPlayedDate = null,
    genres = emptyList(),
    officialRating = null,
    communityRating = null,
    criticRating = null,
    productionYear = null,
    endYear = null,
    status = null,
    studios = emptyList(),
    overview = null,
    runTimeTicks = null,
    container = container,
    people = emptyList(),
    mediaStreams = emptyList(),
    chapters = chapters,
    dateCreated = null,
    sizeBytes = null,
    recursiveItemCount = null,
    childCount = null,
    directors = emptyList(),
    writers = emptyList(),
)

private fun sampleChapter(name: String, startPositionTicks: Long) =
    ChapterInfoFfi(name = name, startPositionTicks = startPositionTicks, imageTag = null)

private fun sampleSegment(kind: MediaSegmentKind, startTicks: Long, endTicks: Long) =
    MediaSegment(segmentType = kind, startTicks = startTicks, endTicks = endTicks)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Unconfined so `reportScope.launch { ... }` runs synchronously ([MainDispatcherRule]'s doc).
     */
    private fun testReportScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())

    /** Every session-starting test must run inside this (or guarantee stop/abandon in a
     * `finally`): a live progress ticker otherwise spins forever under runTest's cleanup.
     */
    private inline fun withSession(viewModel: PlaybackViewModel, body: () -> Unit) {
        try {
            body()
        } finally {
            viewModel.stopPlaybackOnce()
        }
    }

    /** Every ViewModel built here is also cleared in [tearDown]. */
    private val viewModelsToClear = mutableListOf<PlaybackViewModel>()

    private fun buildViewModel(
        gateway: CoreGateway,
        player: PlaybackPlayer,
        itemId: String,
        reportScope: CoroutineScope = testReportScope(),
        startFromBeginning: Boolean = false,
        clock: Clock = Clock.SYSTEM,
        onStopReported: () -> Unit = {},
    ): PlaybackViewModel =
        PlaybackViewModel(
            gateway,
            player,
            itemId,
            reportScope = reportScope,
            onStopReported = onStopReported,
            startFromBeginning = startFromBeginning,
            clock = clock,
        ).also { viewModelsToClear += it }

    @After
    fun tearDown() {
        val store = ViewModelStore()
        viewModelsToClear.forEachIndexed { index, viewModel -> store.put("playback-$index", viewModel) }
        store.clear()
    }

    @Test
    fun `a successful prepare loads the plan and reaches READY`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 50_000_000L) // 5s in
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(plan, player.loadedPlan)
            assertEquals(PlaybackUiState.Phase.READY, viewModel.state.value.phase)
            assertEquals(plan.itemName, viewModel.state.value.itemName)
            assertEquals(plan.startPositionTicks, viewModel.positionTicks.value)
            assertEquals(plan.runtimeTicks, viewModel.state.value.durationTicks)
        }
    }

    // -- docs/11 item 11's "Start from beginning" override -----------------

    @Test
    fun `every ordinary session passes startFromBeginning = false through to preparePlayback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(listOf(false), gateway.preparePlaybackStartFromBeginningCalls)
        }
    }

    @Test
    fun `startFromBeginning = true on construction is forwarded to preparePlayback's very first call`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 0L) // a real prepare_playback would also zero this out server-side
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId, startFromBeginning = true)
        withSession(viewModel) {
            assertEquals(listOf(true), gateway.preparePlaybackStartFromBeginningCalls)
            assertEquals(0L, viewModel.positionTicks.value)
        }
    }

    @Test
    fun `playNext's autoplay session never inherits the constructor's startFromBeginning override`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId, startFromBeginning = true)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()

            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals(listOf(true, false), gateway.preparePlaybackStartFromBeginningCalls)
        }
    }

    @Test
    fun `itemType is copied onto the state and bufferedPositionTicks mirrors the player every tick`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(itemType = "Episode")
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { bufferedPositionTicksValue = 50_000_000L }

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals("Episode", viewModel.state.value.itemType)
            assertEquals(0L, viewModel.bufferedPositionTicks.value) // not yet ticked

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(50_000_000L, viewModel.bufferedPositionTicks.value)

            player.bufferedPositionTicksValue = 90_000_000L
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(90_000_000L, viewModel.bufferedPositionTicks.value)
        }
    }

    @Test
    fun `a WouldTranscode refusal never starts a session and emits a finish event`() = runTest(timeout = TEST_TIMEOUT) {
        val gateway = FakeCoreGateway(
            preparePlaybackResult = CoreException.WouldTranscode("codec not supported").asFailure(),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            // replay = 1 keeps the construction-time event for a late collector.
            val event = viewModel.events.replayCache.firstOrNull()

            assertNull("prepare_playback failure must never load anything into the player", player.loadedPlan)
            assertTrue(event is PlaybackEvent.FinishWithMessage)
            assertEquals(
                "Direct Play isn't possible for this file on this TV: codec not supported. " +
                    "Set Quality to Auto in Settings › Playback to let the server transcode it.",
                (event as PlaybackEvent.FinishWithMessage).message,
            )

            // No session ever started -- the exit path must be a harmless no-op.
            viewModel.stopPlaybackOnce()
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
            assertEquals(0, player.stopAndClearCallCount)
        }
    }

    @Test
    fun `an expired token emits reauthorization instead of an unactionable finish message`() = runTest(timeout = TEST_TIMEOUT) {
        val gateway = FakeCoreGateway(
            preparePlaybackResult = CoreException.Unauthorized().asFailure(),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            val event = viewModel.events.replayCache.firstOrNull()

            assertTrue(event is PlaybackEvent.ReauthorizationRequired)
            assertNull(player.loadedPlan)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
        }
    }

    /** docs/18 §2: `StalePlaybackSession` means Rust already abandoned the losing negotiation,
     * so the quiet-`Finish` branch fires, not the owner-cancelled one.
     */
    @Test
    fun `a StalePlaybackSession from prepare_playback emits a quiet finish, not an error message`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeCoreGateway(
                preparePlaybackResult = CoreException.StalePlaybackSession().asFailure(),
            )
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, "item-1")
            withSession(viewModel) {
                val event = viewModel.events.replayCache.firstOrNull()

                assertEquals(
                    "expected a quiet Finish, never a FinishWithMessage toast, for a race the viewer can't see",
                    PlaybackEvent.Finish,
                    event,
                )
                assertNull("prepare_playback failure must never load anything into the player", player.loadedPlan)

                viewModel.stopPlaybackOnce()
                assertTrue(gateway.stopPlaybackCalls.isEmpty())
                assertEquals(0, gateway.abandonPlaybackCallCount)
            }
        }

    @Test
    fun `stopPlaybackOnce reports the final position exactly once, even called twice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 123_000_000L }

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.stopPlaybackOnce()
            viewModel.stopPlaybackOnce()

            assertEquals(listOf(123_000_000L), gateway.stopPlaybackCalls)
            assertEquals(1, player.stopAndClearCallCount)
            assertEquals(0, gateway.abandonPlaybackCallCount)
        }
    }

    // -- docs/17-mini-player.md §6: the PiP-dismissal stop-report edge -----

    @Test
    fun `stopPlaybackOnce invokes onStopReported after the gateway stop`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 123_000_000L }
        var reportedCount = 0
        var stopPlaybackCallsWhenReported = -1

        val viewModel = buildViewModel(
            gateway,
            player,
            plan.itemId,
            onStopReported = {
                reportedCount++
                stopPlaybackCallsWhenReported = gateway.stopPlaybackCalls.size
            },
        )
        withSession(viewModel) {
            viewModel.stopPlaybackOnce()

            assertEquals(1, reportedCount)
            assertEquals(
                "onStopReported must run after gateway.stopPlayback has already landed",
                1,
                stopPlaybackCallsWhenReported,
            )
        }
    }

    @Test
    fun `natural end reports the final position and emits a silent finish exactly once`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 123_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 123_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)

        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_ENDED)
            player.firePlaybackStateChanged(Player.STATE_ENDED)

            assertEquals(listOf(123_000_000L), gateway.stopPlaybackCalls)
            assertEquals(1, player.stopAndClearCallCount)
            assertEquals(PlaybackEvent.Finish, viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `a player error abandons exactly once and never also reports a stop`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            // A late/duplicate exit path must not double-report.
            viewModel.stopPlaybackOnce()

            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
            assertEquals(1, player.stopAndClearCallCount)
        }
    }

    @Test
    fun `onCleared removes the player listener and guarantees the stop report ran`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 99_000_000L }
        // Built directly: this test triggers onCleared() itself, so tearDown()'s clear is
        // redundant.
        val viewModel = PlaybackViewModel(gateway, player, plan.itemId, testReportScope())
        withSession(viewModel) {
            assertTrue("listener must be registered once the session starts", player.hasActiveListener)

            // ViewModelStore.clear() triggers protected onCleared() without an Activity.
            val store = ViewModelStore()
            store.put("playback", viewModel)
            store.clear()

            assertFalse("onCleared must always remove the player listener", player.hasActiveListener)
            assertEquals(listOf(99_000_000L), gateway.stopPlaybackCalls)
        }
    }

    @Test
    fun `toggling play-pause and seeking delegate straight to the player`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.togglePlayPause()
            viewModel.seek(SeekMath.SKIP_MS)
            viewModel.seek(-SeekMath.SKIP_MS)

            assertEquals(1, player.togglePlayPauseCallCount)
            assertEquals(listOf(SeekMath.SKIP_MS, -SeekMath.SKIP_MS), player.seekCalls)
        }
    }

    @Test
    fun `a pause-resume intent edge reports paused exactly on the real transitions`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // Pause is playWhenReady=false; isPlaying=false alone can be a seek/rebuffer.
            player.playbackState = Player.STATE_READY

            player.firePlayWhenReadyChanged(false)
            player.firePlayWhenReadyChanged(false) // still paused -- must not double-report
            player.firePlayWhenReadyChanged(true)

            assertEquals(listOf(true, false), gateway.reportPausedCalls)
        }
    }

    // -- Credits-aware next-up (GOAL items 2/6) ------------------------

    /** Advances by one [REPORT_INTERVAL_MS] tick; `runCurrent()` fires the boundary-scheduled
     * ticker delay that `advanceTimeBy` alone skips.
     */
    private fun TestScope.advanceOneTick() {
        advanceTimeBy(1_000L)
        runCurrent()
    }

    /** A 20s Episode plan; `next_episode_show_threshold(20.0)` clamps to its 3.0s floor. */
    private fun episodePlan(itemId: String, itemName: String = "Episode One") = samplePlan(
        itemId = itemId,
        itemName = itemName,
        runtimeTicks = 200_000_000L, // 20s
        itemType = "Episode",
        seriesId = "series-1",
    )

    @Test
    fun `the next-up card appears once remaining time crosses the threshold, not before`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(listOf("ep-1"), gateway.nextEpisodeAfterCalls)

            // 10s in -- 10s remaining, still above the 3.0s threshold.
            player.positionTicks = 100_000_000L
            advanceOneTick()
            assertNull("card must not appear before the threshold", viewModel.state.value.nextUp)

            player.positionTicks = 170_000_000L
            advanceOneTick()
            val nextUp = viewModel.state.value.nextUp
            assertNotNull("card must appear once <= threshold seconds remain", nextUp)
            assertEquals(nextCard, nextUp!!.card)
            assertEquals(3.0, nextUp.countdownTotalSecs, 0.0001) // min(remaining=3.0, delay=10.0)
            assertEquals(170_000_000L, nextUp.countdownStartPositionTicks)
        }
    }

    @Test
    fun `the countdown card preloads the next episode once, not on every tick it stays shown`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertNotNull(viewModel.state.value.nextUp)
            assertEquals(listOf("ep-2"), gateway.preloadPlaybackCalls)

            // Still showing the same card a tick later -- must not preload again.
            advanceOneTick()
            assertEquals(listOf("ep-2"), gateway.preloadPlaybackCalls)
        }
    }

    @Test
    fun `countdown completion triggers playNext, ending session one and starting session two`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertNotNull(viewModel.state.value.nextUp)

            // 20s in: the 3.0s countdown has fully elapsed, triggering playNext().
            player.positionTicks = 200_000_000L
            advanceOneTick()

            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals(listOf(200_000_000L), gateway.stopPlaybackCalls)
            assertEquals(0, gateway.abandonPlaybackCallCount)
            assertEquals(plan2, player.loadedPlan)
            assertEquals("Episode Two", viewModel.state.value.itemName)
            assertNull("a fresh session starts with no card yet", viewModel.state.value.nextUp)
        }
    }

    @Test
    fun `nextUpCountdownElapsed hands over on the live position without waiting for the ticker`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertNotNull(viewModel.state.value.nextUp)

            // The live position moved past the 3.0s total between ticks: a stale call (position
            // still short) is a no-op, the real one hands over at once.
            player.positionTicks = 190_000_000L
            viewModel.nextUpCountdownElapsed()
            runCurrent()
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)

            player.positionTicks = 200_000_000L
            viewModel.nextUpCountdownElapsed()
            runCurrent()
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals(listOf(200_000_000L), gateway.stopPlaybackCalls)
            assertEquals("Episode Two", viewModel.state.value.itemName)
        }
    }

    @Test
    fun `playNext ends the current session exactly once even if called twice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.nextUp)

            viewModel.playNext()
            viewModel.playNext() // a duplicate call (e.g. a laggy double Enter) must not double-report

            assertEquals(listOf(170_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
        }
    }

    @Test
    fun `playNext waits for the old stop before preparing and rejects reentry across the suspend gap`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val fake = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan1), "ep-2" to Result.success(plan2)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun stopPlayback(playSessionId: String, positionTicks: Long) {
                fake.stopPlayback(playSessionId, positionTicks)
                stopEntered.complete(Unit)
                releaseStop.await()
            }
        }
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            advanceOneTick()
            viewModel.playNext()
            stopEntered.await()
            viewModel.playNext()

            assertEquals(listOf("ep-1"), fake.preparePlaybackCalls)
            assertEquals(listOf(170_000_000L), fake.stopPlaybackCalls)

            releaseStop.complete(Unit)
            runCurrent()
            assertEquals(listOf("ep-1", "ep-2"), fake.preparePlaybackCalls)
        }
    }

    @Test
    fun `replaceItem reports stop for the first item then prepares the second`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1")
        val plan2 = samplePlan(itemId = "item-2", itemName = "Replacement Movie")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "item-1" to Result.success(plan1),
                "item-2" to Result.success(plan2),
            ),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 30_000_000L }
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            viewModel.replaceItem("item-2")

            assertEquals(listOf("item-1", "item-2"), gateway.preparePlaybackCalls)
            assertEquals(listOf(30_000_000L), gateway.stopPlaybackCalls)
            assertEquals("Replacement Movie", viewModel.state.value.itemName)
            assertEquals(PlaybackUiState.Phase.READY, viewModel.state.value.phase)
        }
    }

    @Test
    fun `replaceItem passes startFromBeginning through`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1")
        val plan2 = samplePlan(itemId = "item-2")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "item-1" to Result.success(plan1),
                "item-2" to Result.success(plan2),
            ),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            viewModel.replaceItem("item-2", startFromBeginning = true)

            assertEquals(listOf("item-1", "item-2"), gateway.preparePlaybackCalls)
            assertEquals(listOf(false, true), gateway.preparePlaybackStartFromBeginningCalls)
        }
    }

    @Test
    fun `back-dismiss suppresses the card for the rest of the session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertNotNull(viewModel.state.value.nextUp)

            viewModel.dismissNextUp()
            assertNull(viewModel.state.value.nextUp)

            player.positionTicks = 200_000_000L
            advanceOneTick()

            assertNull(viewModel.state.value.nextUp)
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
        }
    }

    @Test
    fun `no next episode (last of the series) never shows a card`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        // No entry for "ep-1" in nextEpisodeByItemId -- nextEpisodeAfter returns null.
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(listOf("ep-1"), gateway.nextEpisodeAfterCalls)

            player.positionTicks = 200_000_000L // fully past the threshold, and past the runtime itself
            advanceOneTick()

            assertNull(viewModel.state.value.nextUp)
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)
        }
    }

    @Test
    fun `a Movie plan never fetches a next episode at all`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(itemId = "movie-1", itemType = "Movie", runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("movie-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 200_000_000L
            advanceOneTick()

            assertTrue(gateway.nextEpisodeAfterCalls.isEmpty())
            assertNull(viewModel.state.value.nextUp)
        }
    }

    /** Pins that an outro-derived threshold ([CoreGateway.outroStartSecsFromSegments]) overrides
     * the fixed default.
     */
    @Test
    fun `outroStartSecs derived from the fetched media segments overrides the fixed-default next-up trigger`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1").copy(runtimeTicks = 4_000_000_000L) // 400s
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val outroSegment = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 3_500_000_000L, endTicks = 4_000_000_000L) // outro at 350s
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            mediaSegmentsByItemId = mapOf("ep-1" to listOf(outroSegment)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // The segments fetch and outro derivation already resolved (Unconfined).
            assertEquals(listOf("ep-1"), gateway.getMediaSegmentsCalls)

            // 340s in: 60s remaining is above the outro-derived 50s trigger, isolating it from the
            // fixed-default 30s ceiling.
            player.positionTicks = 3_400_000_000L
            advanceOneTick()
            assertNull("must not appear yet at the outro-derived threshold's far side", viewModel.state.value.nextUp)

            player.positionTicks = 3_500_000_000L
            advanceOneTick()
            val nextUp = viewModel.state.value.nextUp
            assertNotNull("must appear once the outro-derived threshold is crossed", nextUp)
            assertEquals(nextCard, nextUp!!.card)
        }
    }

    // -- "Still watching?" (docs/feature-dev/spec-still-watching-and-lan-discovery.md Feature A) --

    @Test
    fun `ASK_STILL_WATCHING renders the still-watching card, not the countdown card`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()

            val stillWatching = viewModel.state.value.stillWatching
            assertNotNull("still-watching card must appear on ASK_STILL_WATCHING", stillWatching)
            assertEquals(nextCard, stillWatching!!.card)
            assertNull("the two cards are mutually exclusive", viewModel.state.value.nextUp)
            assertEquals(1, gateway.noteEpisodeFinishedCalls.size)
        }
    }

    @Test
    fun `COUNTDOWN renders the ordinary next-up card, matching autoplay's own autoAdvance`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.COUNTDOWN)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            advanceOneTick()

            assertNotNull(viewModel.state.value.nextUp)
            assertNull("the two cards are mutually exclusive", viewModel.state.value.stillWatching)
        }
    }

    @Test
    fun `stillWatchingContinue transitions to the next episode without a countdown`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.stillWatching)

            viewModel.stillWatchingContinue()
            runCurrent()

            assertNull(viewModel.state.value.stillWatching)
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals(plan2, player.loadedPlan)
            assertEquals(listOf(170_000_000L), gateway.stopPlaybackCalls)
            // Continue always resets the guard's counters, regardless of resetOnInput.
            assertEquals(2, gateway.resetStillWatchingCalls.size)
        }
    }

    @Test
    fun `stillWatchingStop reports the finished episode, emits FinishToDetail, and never starts the next episode`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.stillWatching)

            viewModel.stillWatchingStop()

            assertNull(viewModel.state.value.stillWatching)
            assertEquals(listOf(170_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls) // never prepares ep-2
            assertEquals(PlaybackEvent.FinishToDetail("ep-2"), viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `the still-watching card times out to Stop after its configured timeout`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
            settings = settings,
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.stillWatching)

            advanceTimeBy(5_000L)
            runCurrent()

            assertNull("timeout must resolve to Stop", viewModel.state.value.stillWatching)
            assertEquals(PlaybackEvent.FinishToDetail("ep-2"), viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `noteUserInput while the card is showing restarts its timeout`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
            settings = settings,
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.stillWatching)

            advanceTimeBy(4_000L) // timeout (5s) minus 1s
            runCurrent()
            viewModel.noteUserInput()
            runCurrent()

            advanceTimeBy(2_000L)
            runCurrent()

            assertNotNull("input must have restarted the timeout", viewModel.state.value.stillWatching)
        }
    }

    // -- Visible timeout: deadlineMs/timeoutTotalSecs --

    @Test
    fun `the ASK_STILL_WATCHING card carries a deadline derived from the injected clock and configured timeout`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
                settings = settings,
            )
            val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
            val clock = FakeClock(nowMs = 1_000_000L)
            val viewModel = buildViewModel(gateway, player, plan.itemId, clock = clock)
            withSession(viewModel) {
                advanceOneTick()

                val stillWatching = viewModel.state.value.stillWatching
                assertNotNull(stillWatching)
                assertEquals(5.0, stillWatching!!.timeoutTotalSecs, 0.0)
                assertEquals(clock.nowMs + 5_000L, stillWatching.deadlineMs)
            }
        }

    @Test
    fun `noteUserInput replaces the deadline with a fresh now-plus-timeout from the clock`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
            settings = settings,
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val clock = FakeClock(nowMs = 1_000_000L)
        val viewModel = buildViewModel(gateway, player, plan.itemId, clock = clock)
        withSession(viewModel) {
            advanceOneTick()
            val initialDeadline = viewModel.state.value.stillWatching?.deadlineMs
            assertEquals(clock.nowMs + 5_000L, initialDeadline)

            // The deadline recomputes from the current clock, not merely extends from the old one.
            clock.nowMs += 3_000L
            viewModel.noteUserInput()
            runCurrent()

            val restartedDeadline = viewModel.state.value.stillWatching?.deadlineMs
            assertEquals(clock.nowMs + 5_000L, restartedDeadline)
            assertTrue("deadline must actually move, not just extend from the old one", restartedDeadline!! > initialDeadline!!)
        }
    }

    @Test
    fun `STATE_ENDED while the still-watching card is showing does not finish or transition`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()
            assertNotNull(viewModel.state.value.stillWatching)

            player.firePlaybackStateChanged(Player.STATE_ENDED)

            assertNotNull("the card must stay up under STATE_ENDED", viewModel.state.value.stillWatching)
            assertTrue("no Finish/Stop report may fire while the card is showing", gateway.stopPlaybackCalls.isEmpty())
            assertNull(viewModel.events.replayCache.firstOrNull())
        }
    }

    // -- Outro AutoSkip x still-watching: a pending decision gates evaluateAutoSkip's seek. --

    @Test
    fun `outro AutoSkip defers to a pending still-watching ASK decision -- no seek while the card is up, no transition`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1") // 20s
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val outro = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 150_000_000L, endTicks = 200_000_000L) // 15s-20s
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(outro)),
                settings = defaultTestSettings().copy(skipOutro = SegmentAction.AUTO_SKIP),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
            )
            // 15s in: the next-up trigger (5s remaining) and the outro segment fire on this tick.
            val player = FakePlaybackPlayer().apply { positionTicks = 150_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                advanceOneTick()

                assertNotNull("the still-watching card must appear", viewModel.state.value.stillWatching)
                assertTrue("must not seek to the segment end while the decision governs it", player.seekCalls.isEmpty())
                assertNull("no AutoSkipped/Finish event", viewModel.events.replayCache.firstOrNull())
                assertTrue(gateway.stopPlaybackCalls.isEmpty())
            }
        }

    @Test
    fun `outro AutoSkip shows the card the autoplay delay before the credits and hands over as they start, never seeking`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = episodePlan("ep-1") // 20s
            val plan2 = episodePlan("ep-2", itemName = "Episode Two")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val outro = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 150_000_000L, endTicks = 200_000_000L) // 15s-20s
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "ep-1" to Result.success(plan1),
                    "ep-2" to Result.success(plan2),
                ),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(outro)),
                settings = defaultTestSettings().copy(skipOutro = SegmentAction.AUTO_SKIP), // 10s delay
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.COUNTDOWN)),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                // 4s in: 16s remaining is outside the 5s credits + 10s delay window.
                player.positionTicks = 40_000_000L
                advanceOneTick()
                assertNull("must not appear before the delay-plus-credits window", viewModel.state.value.nextUp)

                // 5s in: exactly 10s before the credits.
                player.positionTicks = 50_000_000L
                advanceOneTick()
                runCurrent()
                val nextUp = viewModel.state.value.nextUp
                assertNotNull("the card must appear the autoplay delay before the credits", nextUp)
                assertEquals("the countdown runs out where the credits start", 10.0, nextUp!!.countdownTotalSecs, 1e-9)

                // 15s in: the countdown has elapsed as the credits begin -- the next episode starts
                // and the auto-skip never seeks a session that is already handing over.
                player.positionTicks = 150_000_000L
                advanceOneTick()
                runCurrent()

                assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
                assertEquals(plan2, player.loadedPlan)
                assertTrue("no auto-skip seek", player.seekCalls.isEmpty())
            }
        }

    @Test
    fun `outro AutoSkip with the playhead already inside the credits advances on a COUNTDOWN decision with no card flash`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = episodePlan("ep-1")
            val plan2 = episodePlan("ep-2", itemName = "Episode Two")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val outro = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 150_000_000L, endTicks = 200_000_000L)
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "ep-1" to Result.success(plan1),
                    "ep-2" to Result.success(plan2),
                ),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(outro)),
                settings = defaultTestSettings().copy(skipOutro = SegmentAction.AUTO_SKIP),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.COUNTDOWN)),
            )
            // A seek landed 1s into the credits before any card had shown.
            val player = FakePlaybackPlayer().apply { positionTicks = 160_000_000L }
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                advanceOneTick()
                runCurrent()

                assertNull("the countdown card must never appear -- straight to the next episode instead", viewModel.state.value.nextUp)
                assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
                assertEquals(plan2, player.loadedPlan)
            }
        }

    @Test
    fun `an outro segment configured Ask still shows the ordinary countdown card on a COUNTDOWN decision -- unchanged`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val outro = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 150_000_000L, endTicks = 200_000_000L)
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(outro)),
                settings = defaultTestSettings().copy(skipOutro = SegmentAction.ASK), // not AutoSkip
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.COUNTDOWN)),
            )
            val player = FakePlaybackPlayer().apply { positionTicks = 150_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                advanceOneTick()

                assertNotNull("the ordinary countdown card must still appear", viewModel.state.value.nextUp)
            }
        }

    @Test
    fun `STATE_ENDED racing ahead of a pending still-watching decision waits for it instead of finishing -- ASK`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val outro = sampleSegment(MediaSegmentKind.OUTRO, startTicks = 150_000_000L, endTicks = 200_000_000L)
            val fake = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(outro)),
                settings = defaultTestSettings().copy(skipOutro = SegmentAction.AUTO_SKIP),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
            )
            val decisionGate = CompletableDeferred<Unit>()
            val gateway = object : CoreGateway by fake {
                override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision {
                    decisionGate.await()
                    return fake.noteEpisodeFinished(nowMs)
                }
            }
            val player = FakePlaybackPlayer().apply { positionTicks = 150_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                advanceOneTick() // crosses the threshold; noteEpisodeFinished suspends on decisionGate
                assertNull("the decision hasn't resolved yet", viewModel.state.value.stillWatching)

                player.firePlaybackStateChanged(Player.STATE_ENDED)
                runCurrent()

                assertNull("must not finish while the decision is still pending", viewModel.events.replayCache.firstOrNull())
                assertTrue(fake.stopPlaybackCalls.isEmpty())

                decisionGate.complete(Unit)
                runCurrent()

                assertNotNull("the card must show once the decision resolves, even though EOF was already reached", viewModel.state.value.stillWatching)
                assertNull("still no Finish", viewModel.events.replayCache.firstOrNull())
            }
        }

    @Test
    fun `STATE_ENDED racing ahead of a pending still-watching decision waits for it instead of finishing -- COUNTDOWN transitions with no Finish`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = episodePlan("ep-1")
            val plan2 = episodePlan("ep-2", itemName = "Episode Two")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val fake = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "ep-1" to Result.success(plan1),
                    "ep-2" to Result.success(plan2),
                ),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.COUNTDOWN)),
            )
            val decisionGate = CompletableDeferred<Unit>()
            val gateway = object : CoreGateway by fake {
                override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision {
                    decisionGate.await()
                    return fake.noteEpisodeFinished(nowMs)
                }
            }
            val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                advanceOneTick() // crosses the threshold; noteEpisodeFinished suspends on decisionGate

                player.firePlaybackStateChanged(Player.STATE_ENDED)
                runCurrent()
                assertNull("must not finish while the decision is still pending", viewModel.events.replayCache.firstOrNull())

                decisionGate.complete(Unit)
                runCurrent()

                assertNull("no countdown card -- EOF was already reached, straight to the next episode", viewModel.state.value.nextUp)
                assertEquals(listOf("ep-1", "ep-2"), fake.preparePlaybackCalls)
                assertNull("still no Finish", viewModel.events.replayCache.firstOrNull())
            }
        }

    @Test
    fun `construction calls resetStillWatching exactly once`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1, gateway.resetStillWatchingCalls.size)
        }
    }

    @Test
    fun `noteEpisodeFinished is never called when autoplay is disabled`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val settings = defaultTestSettings().copy(autoplayEnabled = false)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            settings = settings,
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()

            assertTrue(gateway.noteEpisodeFinishedCalls.isEmpty())
            assertNotNull("the ordinary static card still appears with autoplay off", viewModel.state.value.nextUp)
            assertFalse(viewModel.state.value.nextUp!!.autoAdvance)
        }
    }

    // -- Settings consumption (docs/09-settings-plan.md GOAL item 4) --------

    @Test
    fun `skip magnitudes are read from settings once per session start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            settings = defaultTestSettings().copy(skipBackSecs = 15u, skipForwardSecs = 30u),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(15_000L, viewModel.state.value.skipBackMs)
            assertEquals(30_000L, viewModel.state.value.skipForwardMs)
        }
    }

    @Test
    fun `default skip magnitudes match the pre-settings 10s behavior`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)) // defaultTestSettings(): 10s/10s
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(SeekMath.SKIP_MS, viewModel.state.value.skipBackMs)
            assertEquals(SeekMath.SKIP_MS, viewModel.state.value.skipForwardMs)
        }
    }

    @Test
    fun `tolerate-mislabeled-levels toggle is read from settings and passed through to the player's load call`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                settings = defaultTestSettings().copy(tolerateMislabeledLevels = false),
            )
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                assertEquals(false, player.lastTolerateMislabeledLevels)
            }
        }

    @Test
    fun `tolerate-mislabeled-levels toggle defaults to true, matching the pre-settings behavior`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)) // defaultTestSettings(): true
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                assertEquals(true, player.lastTolerateMislabeledLevels)
            }
        }

    @Test
    fun `audio decoder preferences are read once and passed to player load without changing the plan`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                settings = defaultTestSettings().copy(
                    preferFfmpegTrueHd = true,
                    preferFfmpegDts = false,
                    preferFfmpegDtsHd = true,
                ),
            )
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                assertEquals(plan, player.loadedPlan)
                assertEquals(
                    AudioDecoderPreferences(
                        preferFfmpegTrueHd = true,
                        preferFfmpegDts = false,
                        preferFfmpegDtsHd = true,
                    ),
                    player.lastAudioDecoderPreferences,
                )
            }
        }

    @Test
    fun `subtitle style prefs are read from settings once per session start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            settings = defaultTestSettings().copy(
                subtitleScale = 1.5f,
                subtitlePosition = SubtitlePositionPreset.HIGHER,
                subtitleBold = true,
                subtitleBackgroundOpacity = 0.5f,
            ),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1.5f, viewModel.state.value.subtitleScale)
            assertEquals(SubtitlePositionPreset.HIGHER, viewModel.state.value.subtitlePosition)
            assertTrue(viewModel.state.value.subtitleBold)
            assertEquals(0.5f, viewModel.state.value.subtitleBackgroundOpacity)
        }
    }

    @Test
    fun `default subtitle style prefs match the Rust Settings default`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)) // defaultTestSettings(): scale 1.0, Default, not bold, 0.0 opacity
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1.0f, viewModel.state.value.subtitleScale)
            assertEquals(SubtitlePositionPreset.DEFAULT, viewModel.state.value.subtitlePosition)
            assertFalse(viewModel.state.value.subtitleBold)
            assertEquals(0.0f, viewModel.state.value.subtitleBackgroundOpacity)
        }
    }

    @Test
    fun `skip-segment actions are read from settings once per session start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            settings = defaultTestSettings().copy(
                skipIntro = SegmentAction.OFF,
                skipOutro = SegmentAction.AUTO_SKIP,
                skipRecap = SegmentAction.OFF,
                skipPreview = SegmentAction.ASK,
                skipCommercial = SegmentAction.ASK,
            ),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val actions = viewModel.state.value.skipSegmentActions
            assertEquals(SegmentAction.OFF, actions.intro)
            assertEquals(SegmentAction.AUTO_SKIP, actions.outro)
            assertEquals(SegmentAction.OFF, actions.recap)
            assertEquals(SegmentAction.ASK, actions.preview)
            assertEquals(SegmentAction.ASK, actions.commercial)
        }
    }

    @Test
    fun `default skip-segment actions match the Rust Settings default -- Ask everywhere except commercial`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)) // defaultTestSettings(): Ask/Ask/Ask/Ask/AutoSkip
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val actions = viewModel.state.value.skipSegmentActions
            assertEquals(SegmentAction.ASK, actions.intro)
            assertEquals(SegmentAction.ASK, actions.outro)
            assertEquals(SegmentAction.ASK, actions.recap)
            assertEquals(SegmentAction.ASK, actions.preview)
            assertEquals(SegmentAction.AUTO_SKIP, actions.commercial)
        }
    }

    // -- docs/09-settings-plan.md skip-segment settings: AutoSkip driver ----

    @Test
    fun `an AutoSkip segment is skipped automatically once the playhead enters it, emitting an Undo event`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 50_000_000L, 100_000_000L) // 5s-10s
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(commercial)),
            settings = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 60_000_000L } // 6s in, inside the segment
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()

            // 6s (pre-skip position) -> 10s (segment end): a 4000ms delta.
            assertEquals("exactly one seek, straight to the segment's end", listOf(4_000L), player.seekCalls)
            assertEquals(100_000_000L, viewModel.positionTicks.value)

            val event = viewModel.events.replayCache.firstOrNull()
            assertTrue(event is PlaybackEvent.AutoSkipped)
            val autoSkipped = event as PlaybackEvent.AutoSkipped
            assertEquals(60_000_000L, autoSkipped.preSkipPositionTicks)
            assertEquals(MediaSegmentKind.COMMERCIAL, autoSkipped.segmentType)
        }
    }

    @Test
    fun `the same auto-skipped segment occurrence is never re-skipped, even right after an Undo seeks back into it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 50_000_000L, 100_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(commercial)),
            settings = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP),
        )
        // The fake player never moves its tracked position on seek (FakePlaybackPlayer.seekBy),
        // mirroring seek/position lag; lastAutoSkipSegmentKey must survive it without re-firing.
        val player = FakePlaybackPlayer().apply { positionTicks = 60_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // first tick: auto-skips once
            assertEquals(1, player.seekCalls.size)

            advanceOneTick() // second tick: still "inside" the segment per the fake -- must not re-skip
            assertEquals("no re-trigger while the same occurrence is still active", 1, player.seekCalls.size)

            // The viewer hits Undo, seeking back into the segment.
            val event = viewModel.events.replayCache.first() as PlaybackEvent.AutoSkipped
            viewModel.undoSkip(event.preSkipPositionTicks)
            assertEquals(2, player.seekCalls.size) // the undo's own seek

            advanceOneTick() // a tick right after Undo must not immediately re-skip
            assertEquals("Undo must not be immediately re-skipped", 2, player.seekCalls.size)
        }
    }

    @Test
    fun `a segment configured Off is never auto-skipped`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val intro = sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(intro)),
            settings = defaultTestSettings().copy(skipIntro = SegmentAction.OFF),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 10_000_000L } // inside the Off-configured segment
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()

            assertTrue("an Off segment is never seeked past", player.seekCalls.isEmpty())
            assertNull(viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `a segment configured Ask is left to the manual pill path, not auto-skipped`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val intro = sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(intro)),
            settings = defaultTestSettings().copy(skipIntro = SegmentAction.ASK),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 10_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick()

            assertTrue("an Ask segment is never auto-seeked", player.seekCalls.isEmpty())
            assertNull(viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `autoplayDelaySecs from settings feeds the countdown total instead of a hardcoded 10s`() = runTest(timeout = TEST_TIMEOUT) {
        // 400s episode: next_episode_show_threshold(400.0) clamps to 30.0s, distinguishing a 15s
        // delay from a 10s default.
        val plan = episodePlan("ep-1").copy(runtimeTicks = 4_000_000_000L)
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            settings = defaultTestSettings().copy(autoplayDelaySecs = 15u),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 3_700_000_000L // 370s in
            advanceOneTick()

            val nextUp = viewModel.state.value.nextUp
            assertNotNull("card must appear once <= threshold seconds remain", nextUp)
            assertEquals(15.0, nextUp!!.countdownTotalSecs, 0.0001) // min(remaining=30.0, delay=15.0)
        }
    }

    @Test
    fun `autoplay disabled shows the card with no auto-advance, but Enter still plays it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            settings = defaultTestSettings().copy(autoplayEnabled = false),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            // Card still appears at the same threshold as autoplay-enabled.
            player.positionTicks = 170_000_000L
            advanceOneTick()
            val nextUp = viewModel.state.value.nextUp
            assertNotNull("card must still appear with autoplay disabled", nextUp)
            assertFalse("autoAdvance must reflect the disabled setting", nextUp!!.autoAdvance)

            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)
            assertNotNull("the card must not be cleared by the non-advance either", viewModel.state.value.nextUp)

            // PlaybackScreen's key handling calls playNext() on Enter regardless of autoAdvance.
            viewModel.playNext()
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals("Episode Two", viewModel.state.value.itemName)
        }
    }

    // -- Trickplay scrub preview (mission GOAL item 4/5) --------------------

    @Test
    fun `seek with no trickplay manifest fetched returns null and never calls trickplayLocate`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 72_000_000_000L)
        // getTrickplayResult defaults to null -- the fetch resolves (Unconfined) to "no manifest".
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val result = viewModel.seek(10_000L)
            assertNull("a trickplay-less plan must never produce a preview", result)
            assertEquals(listOf(10_000L), player.seekCalls)
        }
    }

    @Test
    fun `seek with a resolved trickplay manifest resolves the target position and tile`() = runTest(timeout = TEST_TIMEOUT) {
        val meta = sampleTrickplayMeta()
        val plan = samplePlan(runtimeTicks = 72_000_000_000L) // 2h runtime
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), getTrickplayResult = meta)
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // fetchTrickplay awaits enrichmentGate (docs/12 §11); the fetch resolves
            // (Unconfined) right after the simulated first frame unblocks it.
            player.fireRenderedFirstFrame()
            assertEquals(listOf(FakeCoreGateway.GetTrickplayCall(plan.itemId, plan.mediaSourceId)), gateway.getTrickplayCalls)

            val result = viewModel.seek(30_000L)

            assertNotNull(result)
            assertEquals(30_000L, result!!.targetPositionMs)
            // 10s interval / 10x10 grid: 30_000ms is thumbnail index 3 -- row 0, col 3 of sheet 0.
            assertEquals(0u, result.tile.imageIndex)
            assertEquals(3u * meta.width, result.tile.x)
            assertEquals(0u, result.tile.y)
        }
    }

    @Test
    fun `seek clamps the trickplay preview target to the item duration, same as the underlying seek`() = runTest(timeout = TEST_TIMEOUT) {
        val meta = sampleTrickplayMeta()
        val plan = samplePlan(runtimeTicks = PlaybackTicks.msToTicks(20_000L)) // 20s item
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), getTrickplayResult = meta)
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks fetchTrickplay's enrichmentGate await (docs/12 §11)
            val result = viewModel.seek(100_000L) // way past the 20s duration
            assertNotNull(result)
            assertEquals(20_000L, result!!.targetPositionMs)
        }
    }

    /** A seek before [PlaybackViewModel.fetchTrickplay] resolves fails open via `trickplayMeta ==
     * null`; a later seek in the same session resolves a real preview.
     */
    @Test
    fun `a seek before the trickplay fetch resolves fails open with no preview`() = runTest(timeout = TEST_TIMEOUT) {
        val meta = sampleTrickplayMeta()
        val plan = samplePlan(runtimeTicks = 72_000_000_000L)
        val fake = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val trickplayResult = CompletableDeferred<TrickplayMetaFfi?>()
        // Delegation (`by fake`) forwards every other member, including the trickplay lookups.
        val gateway = object : CoreGateway by fake {
            override suspend fun getTrickplay(itemId: String, mediaSourceId: String): TrickplayMetaFfi? =
                trickplayResult.await()
        }
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // Unblocks fetchTrickplay's enrichmentGate await (docs/12 §11) so it reaches
            // gateway.getTrickplay, which then races the seek below via trickplayResult.
            player.fireRenderedFirstFrame()
            assertNull("a seek racing ahead of an in-flight trickplay fetch must fail open", viewModel.seek(30_000L))
            assertNull(viewModel.trickplayMeta)

            trickplayResult.complete(meta)
            runCurrent()
            val result = viewModel.seek(30_000L)
            assertNotNull("once the fetch resolves, the same session's later seeks must resolve a real preview", result)
        }
    }

    // -- Enrichment gate (docs/12 §11, docs/18): OSD detail/trickplay/server name wait for the
    // first rendered frame or a 1500ms fallback; segments and episode neighbors stay immediate. --

    @Test
    fun `OSD detail, trickplay and server name are not requested before the first frame`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            getTrickplayResult = sampleTrickplayMeta(),
            serverDisplayNameValue = "shelf.test",
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertTrue(gateway.getPlaybackOsdDetailCalls.isEmpty())
            assertTrue(gateway.getTrickplayCalls.isEmpty())
            assertEquals(0, gateway.serverDisplayNameCallCount)
        }
    }

    @Test
    fun `OSD detail, trickplay and server name are requested right after the first rendered frame`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val meta = sampleTrickplayMeta()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            getTrickplayResult = meta,
            serverDisplayNameValue = "shelf.test",
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
            assertEquals(listOf(FakeCoreGateway.GetTrickplayCall(plan.itemId, plan.mediaSourceId)), gateway.getTrickplayCalls)
            assertEquals(1, gateway.serverDisplayNameCallCount)
        }
    }

    @Test
    fun `OSD detail, trickplay and server name are requested 1500ms after load when no frame ever renders`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val meta = sampleTrickplayMeta()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
                getTrickplayResult = meta,
                serverDisplayNameValue = "shelf.test",
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                advanceTimeBy(1_499L)
                runCurrent()
                assertTrue("must still be gated just under the fallback", gateway.getPlaybackOsdDetailCalls.isEmpty())

                advanceTimeBy(1L) // crosses the 1500ms fallback
                runCurrent()
                assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
                assertEquals(listOf(FakeCoreGateway.GetTrickplayCall(plan.itemId, plan.mediaSourceId)), gateway.getTrickplayCalls)
                assertEquals(1, gateway.serverDisplayNameCallCount)
            }
        }

    @Test
    fun `media segments and episode neighbors are fetched immediately, not gated on the first frame`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val segments = listOf(sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L))
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            mediaSegmentsByItemId = mapOf("ep-1" to segments),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(segments, viewModel.state.value.mediaSegments)
            assertEquals(listOf("ep-1"), gateway.previousEpisodeBeforeCalls)
            assertEquals(listOf("ep-1"), gateway.nextEpisodeAfterCalls)
            // Contrast: the gated fetches haven't fired yet.
            assertTrue(gateway.getPlaybackOsdDetailCalls.isEmpty())
        }
    }

    // -- Hold-to-seek (docs/feature-dev/PRD-hold-to-seek.md) ----------------

    /** PRD §6.2: [glideEndClampMs] sits 1s short of duration, so a tap clamps to 19_000, not
     * 20_000.
     */
    @Test
    fun `tapSeek never lands past the glide end clamp, even when the raw target would reach duration`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = PlaybackTicks.msToTicks(20_000L))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.tapSeek(15_000L) // position 0 -> 15_000, well inside the clamp
            val target = viewModel.tapSeek(10_000L) // raw target 25_000, past duration and the clamp

            assertEquals(19_000L, target)
            assertEquals(listOf(15_000L, 4_000L), player.seekCalls) // 19_000 - 15_000
        }
    }

    /** Each rapid tap computes from where the previous tap landed, not a stale pre-seek position.
     */
    @Test
    fun `rapid taps compute from the position the previous tap landed at, not a stale one`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = PlaybackTicks.msToTicks(100_000L), runtimeTicks = 72_000_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val first = viewModel.tapSeek(10_000L)
            val second = viewModel.tapSeek(10_000L)

            assertEquals(110_000L, first)
            assertEquals(120_000L, second)
            assertEquals(listOf(10_000L, 10_000L), player.seekCalls)
        }
    }

    @Test
    fun `commitGlide at the end clamp pauses before seeking and forwards the exact delta`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 100_000_000L, runtimeTicks = 72_000_000_000L) // 10s in, 2h runtime
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(100_000_000L, viewModel.positionTicks.value) // sanity: 10s in

            viewModel.commitGlide(targetMs = 50_000L, endClamped = true)

            assertEquals(1, player.pauseCallCount)
            assertFalse(player.playWhenReady)
            assertEquals(listOf(40_000L), player.seekCalls) // 50_000 - 10_000
        }
    }

    @Test
    fun `commitGlide without an end clamp never pauses`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 72_000_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.commitGlide(targetMs = 50_000L, endClamped = false)
            assertEquals(0, player.pauseCallCount)
            assertEquals(listOf(50_000L), player.seekCalls)
        }
    }

    /** PRD §6.2: a deliberate end-clamp glide must not fire autoplay/up-next, even inside the
     * trigger window.
     */
    @Test
    fun `commitGlide at the end clamp blocks next-up and emits no Finish event even inside the trigger window`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            )
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                // 18s in, inside the trigger window.
                viewModel.commitGlide(targetMs = 18_000L, endClamped = true)
                player.positionTicks = PlaybackTicks.msToTicks(18_000L)
                advanceOneTick()

                assertNull("the end-clamp hold blocks the up-next card for this tick", viewModel.state.value.nextUp)
                assertNull("no Finish/AutoSkipped event either", viewModel.events.replayCache.firstOrNull())
            }
        }

    @Test
    fun `the end-clamp hold clears once playWhenReady returns true, letting next-up evaluate again`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.commitGlide(targetMs = 18_000L, endClamped = true)
            player.positionTicks = PlaybackTicks.msToTicks(18_000L)
            advanceOneTick()
            assertNull(viewModel.state.value.nextUp)

            // Resuming is Media3 announcing playWhenReady=true.
            player.firePlayWhenReadyChanged(true)
            advanceOneTick()

            assertNotNull("the hold is cleared, so the trigger-window card can appear again", viewModel.state.value.nextUp)
        }
    }

    /** Only resuming (playWhenReady=true) clears the end-clamp hold; a seek while still paused
     * must not.
     */
    @Test
    fun `a seek or non-end commit while still paused keeps the end-clamp hold`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
            player.positionTicks = PlaybackTicks.msToTicks(19_000L)
            advanceOneTick()
            assertNull(viewModel.state.value.nextUp)

            // Still paused, 2s from the end.
            viewModel.tapSeek(-1_000L)
            player.positionTicks = PlaybackTicks.msToTicks(18_000L)
            advanceOneTick()
            assertNull("a paused tap seek keeps the hold", viewModel.state.value.nextUp)

            // Its non-end commit, also still inside the window.
            viewModel.commitGlide(targetMs = 17_500L, endClamped = false)
            player.positionTicks = PlaybackTicks.msToTicks(17_500L)
            advanceOneTick()
            assertNull("a non-end commit keeps the hold", viewModel.state.value.nextUp)
            assertEquals("the end-clamp commit was the only pause", 1, player.pauseCallCount)
        }
    }

    /** PRD §6.2: exiting an end-clamp hold reports the pre-seek position, never the clamp point.
     */
    @Test
    fun `stopping during an end-clamp hold reports the true pre-seek position, not the clamp point`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(startPositionTicks = PlaybackTicks.msToTicks(10_000L), runtimeTicks = PlaybackTicks.msToTicks(20_000L))
            val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                viewModel.commitGlide(targetMs = 19_000L, endClamped = true) // the true end clamp for this 20s item
                viewModel.stopPlaybackOnce()

                assertEquals(listOf(PlaybackTicks.msToTicks(10_000L)), gateway.stopPlaybackCalls)
            }
        }

    /** A non-end commit while still paused at the clamp is itself "a seek away from the clamp". */
    @Test
    fun `a paused glide back from the end clamp updates the reported exit position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = PlaybackTicks.msToTicks(10_000L), runtimeTicks = PlaybackTicks.msToTicks(20_000L))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
            viewModel.commitGlide(targetMs = 5_000L, endClamped = false) // glided back, still paused
            viewModel.stopPlaybackOnce()

            assertEquals(listOf(PlaybackTicks.msToTicks(5_000L)), gateway.stopPlaybackCalls)
        }
    }

    /** Once the viewer resumes on their own, the override is gone and a later stop reports live
     * position.
     */
    @Test
    fun `resuming from the end clamp clears the override, so a later stop reports the live position`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(startPositionTicks = PlaybackTicks.msToTicks(10_000L), runtimeTicks = PlaybackTicks.msToTicks(20_000L))
            val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
                player.firePlayWhenReadyChanged(true) // the viewer resumed on their own
                player.positionTicks = PlaybackTicks.msToTicks(19_500L) // played on past the clamp
                viewModel.stopPlaybackOnce()

                assertEquals(listOf(PlaybackTicks.msToTicks(19_500L)), gateway.stopPlaybackCalls)
            }
        }

    /** PRD §6.2: an end-clamp hold established while [PlaybackViewModel.evaluateNextUp]'s
     * `noteEpisodeFinished` is in flight wins over whatever decision it eventually returns.
     */
    @Test
    fun `a hold established while noteEpisodeFinished is in flight wins over the decision it returns`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val fake = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            )
            val decision = CompletableDeferred<StillWatchingDecision>()
            val gateway = object : CoreGateway by fake {
                override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision = decision.await()
            }
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                // 17s in: starts noteEpisodeFinished, left suspended on `decision`.
                player.positionTicks = 170_000_000L
                advanceOneTick()

                viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
                decision.complete(StillWatchingDecision.ASK_STILL_WATCHING)
                runCurrent()

                assertNull("the hold wins over the in-flight decision", viewModel.state.value.nextUp)
                assertNull("no still-watching card either", viewModel.state.value.stillWatching)
            }
        }

    /** A seek landing exactly on the clamp again must not replace the protected exit position. */
    @Test
    fun `seeks landing at the clamp itself never replace the protected exit position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = PlaybackTicks.msToTicks(10_000L)
            advanceOneTick()
            viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
            player.positionTicks = PlaybackTicks.msToTicks(19_000L)
            advanceOneTick()

            viewModel.tapSeek(10_000L) // clamps to 19_000 again
            viewModel.commitGlide(targetMs = 19_000L, endClamped = true)
            player.positionTicks = PlaybackTicks.msToTicks(19_000L)
            advanceOneTick()

            viewModel.stopPlaybackOnce()
            assertEquals(listOf(PlaybackTicks.msToTicks(10_000L)), gateway.stopPlaybackCalls)
        }
    }

    // -- Automatic track selection (docs/09-settings-plan.md step 3) --------

    /** Each language its own group: `TrackGroup.verifyCorrectness()` logs an error otherwise. */
    private fun sampleTracks(): Tracks {
        val englishAudioGroup = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").setLanguage("eng").build()),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
        val japaneseAudioGroup = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").setLanguage("jpn").build()),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        val textGroup = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("text/vtt").setLanguage("eng").build()),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        return Tracks(listOf(englishAudioGroup, japaneseAudioGroup, textGroup))
    }

    @Test
    fun `onTracksChanged resolves and applies a track decision exactly once per session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val decision = TrackDecisionFfi(audioTrackId = 1L, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), resolveTracksResult = decision)
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = sampleTracks()
            player.fireTracksChanged(tracks)

            assertEquals(listOf("series-1" to TrackMapping.toTrackInfos(tracks)), gateway.resolveTracksCalls.map { it.seriesId to it.tracks })
            assertEquals(listOf(decision to tracks), player.applyTrackDecisionCalls)

            // A second announcement for the same session must not re-resolve (ExoPlayer fires
            // onTracksChanged more than once per load).
            player.fireTracksChanged(tracks)
            assertEquals(1, gateway.resolveTracksCalls.size)
            assertEquals(1, player.applyTrackDecisionCalls.size)
        }
    }

    @Test
    fun `a Movie plan passes a null seriesId through to resolveTracks`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = null)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(sampleTracks())
            assertEquals(listOf<String?>(null), gateway.resolveTracksCalls.map { it.seriesId })
        }
    }

    @Test
    fun `LEAVE with no track id still calls applyTrackDecision, which is a no-op on a real player`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), resolveTracksResult = leaveEverythingAloneDecision())
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = sampleTracks()
            player.fireTracksChanged(tracks)

            assertEquals(1, player.applyTrackDecisionCalls.size)
            assertEquals(leaveEverythingAloneDecision(), player.applyTrackDecisionCalls.single().first)
        }
    }

    @Test
    fun `OFF subtitle decision is passed through to applyTrackDecision for the real player to disable text`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val decision = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), resolveTracksResult = decision)
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(sampleTracks())
            assertEquals(SubtitleActionFfi.OFF, player.applyTrackDecisionCalls.single().first.subtitleAction)
        }
    }

    @Test
    fun `an empty Tracks announcement never calls resolveTracks`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(Tracks.EMPTY)
            assertTrue(gateway.resolveTracksCalls.isEmpty())
            assertTrue(player.applyTrackDecisionCalls.isEmpty())
        }
    }

    @Test
    fun `playNext's second session gets its own independent track resolution`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two").copy(seriesId = "series-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val decision = TrackDecisionFfi(audioTrackId = 1L, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1.copy(seriesId = "series-1")),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            resolveTracksResult = decision,
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            val tracksOne = sampleTracks()
            player.fireTracksChanged(tracksOne)
            assertEquals(1, gateway.resolveTracksCalls.size)

            // Cross the threshold and let playNext start a fresh session.
            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals("Episode Two", viewModel.state.value.itemName)

            // The second session's own first announcement must resolve again, independently.
            val tracksTwo = sampleTracks()
            player.fireTracksChanged(tracksTwo)
            assertEquals(2, gateway.resolveTracksCalls.size)
            assertEquals(2, player.applyTrackDecisionCalls.size)
        }
    }

    // -- docs/18 §3.1: a late automatic resolution must never apply over a manual pick or into a
    // session it no longer belongs to. --------------------------------------------------------

    @Test
    fun `a manual subtitle pick cancels a pending automatic resolution so only the manual decision reaches the player`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val autoDecision = TrackDecisionFfi(audioTrackId = 1L, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                resolveTracksResult = autoDecision,
            ).apply { resolveTracksGate = gate }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(sampleTracks())
                assertEquals(1, gateway.resolveTracksCalls.size) // resolution started; suspended on the gate
                assertTrue(player.applyTrackDecisionCalls.isEmpty())

                // The viewer picks "Off" before the automatic result comes back.
                viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
                assertEquals(1, player.applyTrackDecisionCalls.size)
                assertEquals(SubtitleActionFfi.OFF, player.applyTrackDecisionCalls.single().first.subtitleAction)

                // The stale automatic result now lands.
                gate.complete(Unit)
                runCurrent()

                // Still only the manual decision -- the late automatic one never applied.
                assertEquals(1, player.applyTrackDecisionCalls.size)
                assertEquals(SubtitleActionFfi.OFF, player.applyTrackDecisionCalls.single().first.subtitleAction)
            }
        }

    @Test
    fun `a session change while automatic resolution is pending discards the late result`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = samplePlan(itemId = "item-1")
            val plan2 = samplePlan(itemId = "item-2", itemName = "Replacement Movie")
            val decision = TrackDecisionFfi(audioTrackId = 1L, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "item-1" to Result.success(plan1),
                    "item-2" to Result.success(plan2),
                ),
                resolveTracksResult = decision,
            ).apply { resolveTracksGate = gate }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(sampleTracks())
                assertEquals(1, gateway.resolveTracksCalls.size) // resolution started for item-1; suspended on the gate
                assertTrue(player.applyTrackDecisionCalls.isEmpty())

                // A newer session (external Play/deep-link swap) completes before item-1's
                // resolution resolves.
                viewModel.replaceItem(plan2.itemId)
                runCurrent()
                assertEquals(plan2, player.loadedPlan)

                // item-1's stale automatic result now lands.
                gate.complete(Unit)
                runCurrent()

                // Discarded entirely -- no stale mutation into item-2's session.
                assertTrue(player.applyTrackDecisionCalls.isEmpty())
            }
        }

    // -- In-player track picker (docs/09-settings-plan.md slice 3b) --------

    /** Two audio groups (English selected) and one text group (none selected: "Off" reads
     * selected).
     */
    private fun pickerTracks(): Tracks {
        val englishAudio = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("English").setLanguage("eng").build()
        val japaneseAudio = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLanguage("jpn").build()
        val englishSubtitle = Format.Builder().setSampleMimeType("text/vtt").setLabel("English").setLanguage("eng").build()

        val englishAudioGroup = Tracks.Group(TrackGroup(englishAudio), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val japaneseAudioGroup = Tracks.Group(TrackGroup(japaneseAudio), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val englishSubtitleGroup = Tracks.Group(TrackGroup(englishSubtitle), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))

        return Tracks(listOf(englishAudioGroup, japaneseAudioGroup, englishSubtitleGroup))
    }

    @Test
    fun `openTrackPicker is a no-op while no tracks have been announced yet`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openTrackPicker()
            assertNull(viewModel.state.value.trackPicker)
        }
    }

    @Test
    fun `openTrackPicker builds audio choices and a selected-Off subtitle entry`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()

            val picker = viewModel.state.value.trackPicker
            assertNotNull(picker)
            val infos = TrackMapping.toTrackInfos(tracks)
            val englishAudioInfo = infos.single { it.kind == TrackKindFfi.AUDIO && it.title == "English" }
            val japaneseAudioInfo = infos.single { it.kind == TrackKindFfi.AUDIO && it.title == null }

            assertEquals(2, picker!!.audioTracks.size)
            assertEquals("English", picker.audioTracks[0].label)
            assertTrue("the currently-selected audio track must read as selected", picker.audioTracks[0].selected)
            assertEquals(japaneseAudioInfo.lang, picker.audioTracks[1].label)
            assertFalse(picker.audioTracks[1].selected)
            assertEquals(englishAudioInfo.id, picker.audioTracks[0].id)

            assertEquals(2, picker.subtitleTracks.size) // "Off" + English
            assertEquals("Off", picker.subtitleTracks[0].label)
            assertEquals(TRACK_PICKER_SUBTITLE_OFF_ID, picker.subtitleTracks[0].id)
            assertTrue("no real subtitle track is selected, so Off must read as selected", picker.subtitleTracks[0].selected)
            assertEquals("English", picker.subtitleTracks[1].label)
            assertFalse(picker.subtitleTracks[1].selected)
        }
    }

    @Test
    fun `chooseAudio applies the decision, remembers the chosen key for a series, and flips the picker's own selection`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val japaneseId = viewModel.state.value.trackPicker!!.audioTracks[1].id
            val japaneseInfo = TrackMapping.toTrackInfos(tracks).single { it.id == japaneseId }

            viewModel.chooseAudio(japaneseId)

            // .last(), not .single(): fireTracksChanged already drove an automatic resolution;
            // this manual choice is the second entry.
            val applied = player.applyTrackDecisionCalls.last().first
            assertEquals(japaneseId, applied.audioTrackId)
            assertEquals(SubtitleActionFfi.LEAVE, applied.subtitleAction)
            assertNull(applied.subtitleTrackId)

            assertEquals(
                listOf(FakeCoreGateway.RememberTrackChoiceCall("series-1", TrackKindFfi.AUDIO, gateway.trackPrefKeyOf(japaneseInfo))),
                gateway.rememberTrackChoiceCalls,
            )

            val picker = viewModel.state.value.trackPicker!!
            assertFalse("the previously-selected English row must lose its check", picker.audioTracks[0].selected)
            assertTrue("the newly-chosen Japanese row must gain the check immediately, without waiting for a fresh onTracksChanged", picker.audioTracks[1].selected)
        }
    }

    @Test
    fun `chooseSubtitle with Off applies OFF, remembers a null key, and marks Off selected`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // Start from the English subtitle track selected, so choosing Off is a real transition.
            val englishSubtitle = Format.Builder().setSampleMimeType("text/vtt").setLabel("English").setLanguage("eng").build()
            val englishAudio = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("English").build()
            val tracks = Tracks(
                listOf(
                    Tracks.Group(TrackGroup(englishAudio), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(TrackGroup(englishSubtitle), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                ),
            )
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val offId = viewModel.state.value.trackPicker!!.subtitleTracks[0].id
            assertEquals(TRACK_PICKER_SUBTITLE_OFF_ID, offId)
            assertFalse("Off must not read as selected while the English track is", viewModel.state.value.trackPicker!!.subtitleTracks[0].selected)

            viewModel.chooseSubtitle(offId)

            // .last(): the automatic slice 3a resolution adds an earlier entry too.
            val applied = player.applyTrackDecisionCalls.last().first
            assertNull(applied.audioTrackId)
            assertEquals(SubtitleActionFfi.OFF, applied.subtitleAction)
            assertNull(applied.subtitleTrackId)

            assertEquals(
                listOf(FakeCoreGateway.RememberTrackChoiceCall("series-1", TrackKindFfi.SUBTITLE, null)),
                gateway.rememberTrackChoiceCalls,
            )
            assertTrue(viewModel.state.value.trackPicker!!.subtitleTracks[0].selected)
        }
    }

    @Test
    fun `chooseSubtitle with a real track applies it and remembers its key`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val englishSubtitleChoice = viewModel.state.value.trackPicker!!.subtitleTracks[1]
            val englishSubtitleInfo = TrackMapping.toTrackInfos(tracks).single { it.id == englishSubtitleChoice.id }

            viewModel.chooseSubtitle(englishSubtitleChoice.id)

            // .last(): the automatic slice 3a resolution adds an earlier entry too.
            val applied = player.applyTrackDecisionCalls.last().first
            assertNull(applied.audioTrackId)
            assertEquals(englishSubtitleChoice.id, applied.subtitleTrackId)

            assertEquals(
                listOf(FakeCoreGateway.RememberTrackChoiceCall("series-1", TrackKindFfi.SUBTITLE, gateway.trackPrefKeyOf(englishSubtitleInfo))),
                gateway.rememberTrackChoiceCalls,
            )
            val picker = viewModel.state.value.trackPicker!!
            assertFalse(picker.subtitleTracks[0].selected) // Off
            assertTrue(picker.subtitleTracks[1].selected) // English
        }
    }

    @Test
    fun `a Movie plan applies the picker's choice but never calls rememberTrackChoice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(itemId = "movie-1", seriesId = null)
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("movie-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val japaneseId = viewModel.state.value.trackPicker!!.audioTracks[1].id

            viewModel.chooseAudio(japaneseId)

            // 2, not 1: fireTracksChanged above already drove an automatic
            // resolveTrackSelectionOnce call.
            assertEquals(2, player.applyTrackDecisionCalls.size)
            assertEquals(japaneseId, player.applyTrackDecisionCalls.last().first.audioTrackId)
            assertTrue("a Movie (no seriesId) has nothing to key a per-series memory on", gateway.rememberTrackChoiceCalls.isEmpty())
        }
    }

    @Test
    fun `chooseAudio and chooseSubtitle are no-ops for a stale id that no longer resolves`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()
            // Captured rather than asserted "empty": fireTracksChanged already drove an automatic
            // resolution call.
            val callsBeforeStaleChoice = player.applyTrackDecisionCalls.size

            viewModel.chooseAudio(9_999L)
            viewModel.chooseSubtitle(9_999L)

            assertEquals(callsBeforeStaleChoice, player.applyTrackDecisionCalls.size)
            assertTrue(gateway.rememberTrackChoiceCalls.isEmpty())
        }
    }

    @Test
    fun `closeTrackPicker clears the picker state`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()
            assertNotNull(viewModel.state.value.trackPicker)

            viewModel.closeTrackPicker()
            assertNull(viewModel.state.value.trackPicker)
        }
    }

    // -- osd-tier-2: chapters + media segments fetch (docs/12 ranked build order items 6/9/12) --

    @Test
    fun `chapters and media segments are fetched once at session start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val chapters = listOf(sampleChapter("Cold Open", 0L), sampleChapter("Act One", 100_000_000L))
        val segments = listOf(sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail(chapters = chapters))),
            mediaSegmentsByItemId = mapOf(plan.itemId to segments),
            serverDisplayNameValue = "shelf.test",
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // OSD detail and server name await enrichmentGate (docs/12 §11); segments stay immediate.
            player.fireRenderedFirstFrame()
            assertEquals(chapters, viewModel.state.value.chapters)
            assertEquals("mkv", viewModel.state.value.playbackStatsDetail?.container)
            assertEquals("shelf.test", viewModel.state.value.statsServerName)
            assertEquals(segments, viewModel.state.value.mediaSegments)
            assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
            assertTrue(gateway.getItemDetailCalls.isEmpty())
            assertEquals(listOf(plan.itemId), gateway.getMediaSegmentsCalls)
        }
    }

    @Test
    fun `a failed getItemDetail degrades to empty chapters rather than aborting the session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.failure(CoreException.NotSignedIn())),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(PlaybackUiState.Phase.READY, viewModel.state.value.phase)
            assertEquals(emptyList<Any>(), viewModel.state.value.chapters)
        }
    }

    @Test
    fun `an unconfigured getMediaSegments (older server, docs-12 fail-open) leaves mediaSegments empty`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(emptyList<MediaSegment>(), viewModel.state.value.mediaSegments)
        }
    }

    // -- osd-tier-2: info overlay (docs/12 ranked build order item 12) --

    @Test
    fun `openInfoOverlay is a no-op before the ItemDetail fetch has resolved`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        // No itemDetailResultsByItemId entry -- the fake's narrow OSD fetch throws and startup
        // fails open.
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openInfoOverlay()
            assertNull(viewModel.state.value.statsSheetLive)
        }
    }

    @Test
    fun `openInfoOverlay seeds a live snapshot once the OSD detail fetch resolves, closeInfoOverlay clears it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val detail = sampleItemDetail(container = "mp4")
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(detail)),
        )
        val player = FakePlaybackPlayer().apply {
            livePlaybackStatsValue = PlaybackLiveStats(
                bufferedAheadMs = 8_500L,
                allocatedBufferBytes = 4L * 1024L * 1024L,
                bandwidthBytesPerSecond = 2_000_000L,
                state = PlaybackLiveState.PLAYING,
                droppedFrames = 2L,
            )
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch (docs/12 §11) openInfoOverlay needs
            viewModel.openInfoOverlay()
            assertEquals(player.livePlaybackStatsValue, viewModel.state.value.statsSheetLive)

            viewModel.closeInfoOverlay()
            assertNull(viewModel.state.value.statsSheetLive)
        }
    }

    @Test
    fun `an open stats sheet refreshes its live snapshot on the existing progress tick`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val detail = sampleItemDetail(container = "mkv")
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(detail)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch (docs/12 §11) openInfoOverlay needs
            viewModel.openInfoOverlay()
            assertEquals(PlaybackLiveState.IDLE, viewModel.state.value.statsSheetLive?.state)

            player.livePlaybackStatsValue = player.livePlaybackStatsValue.copy(
                bufferedAheadMs = 20_000L,
                state = PlaybackLiveState.PLAYING,
                droppedFrames = 3L,
            )
            advanceOneTick()

            assertEquals(20_000L, viewModel.state.value.statsSheetLive?.bufferedAheadMs)
            assertEquals(PlaybackLiveState.PLAYING, viewModel.state.value.statsSheetLive?.state)
            assertEquals(3L, viewModel.state.value.statsSheetLive?.droppedFrames)
        }
    }

    @Test
    fun `library info performs a fresh request only when opened and closes cleanly`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val detail = sampleItemDetail(container = "mkv").copy(
            name = "Collector Cut",
            playCount = 4,
        )
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(detail)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch (docs/12 §11)
            // Startup requests only the narrow OSD record. Full collector
            // metadata is untouched until the viewer asks for it.
            assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
            assertTrue(gateway.getItemDetailCalls.isEmpty())
            assertNull(viewModel.state.value.libraryInfoOverlay)

            viewModel.openLibraryInfoOverlay()
            assertEquals(listOf(plan.itemId), gateway.getItemDetailCalls)
            val content = viewModel.state.value.libraryInfoOverlay as LibraryInfoOverlayState.Content
            assertEquals("4 times", content.value.fields.single { it.label == "Watched" }.value)

            viewModel.closeLibraryInfoOverlay()
            assertNull(viewModel.state.value.libraryInfoOverlay)
        }
    }

    // -- osd-tier-2: prev/next-chapter transport (docs/12 controls row) --

    @Test
    fun `jumpToChapter seeks to the resolved target and updates positionTicks optimistically`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L) // 20s
        val chapters = listOf(sampleChapter("Cold Open", 0L), sampleChapter("Act One", 100_000_000L))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail(chapters = chapters))),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch (docs/12 §11)
            assertEquals(chapters, viewModel.state.value.chapters) // fetch resolves (Unconfined) right after

            assertEquals(100_000_000L, viewModel.jumpToChapter(forward = true)) // 0s -> Act One at 10s
            assertEquals(listOf(10_000L), player.seekCalls)
            assertEquals(100_000_000L, viewModel.positionTicks.value)

            assertEquals(0L, viewModel.jumpToChapter(forward = false)) // back to Cold Open at 0s
            assertEquals(listOf(10_000L, -10_000L), player.seekCalls)
            assertEquals(0L, viewModel.positionTicks.value)
        }
    }

    @Test
    fun `jumpToChapter is a no-op with no chapter data`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertNull(viewModel.jumpToChapter(forward = true))
            assertNull(viewModel.jumpToChapter(forward = false))
            assertTrue(player.seekCalls.isEmpty())
        }
    }

    // -- osd-tier-2: skip intro/credits (docs/12 "Skip intro/credits") --

    @Test
    fun `skipSegment seeks to the segment's end and returns the pre-skip position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val intro = sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L) // 0-5s

            val before = viewModel.skipSegment(intro)

            assertEquals(0L, before)
            assertEquals(listOf(5_000L), player.seekCalls)
            assertEquals(50_000_000L, viewModel.positionTicks.value)
        }
    }

    @Test
    fun `undoSkip seeks back to the given pre-skip position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val outro = sampleSegment(MediaSegmentKind.OUTRO, 100_000_000L, 150_000_000L)
            val before = viewModel.skipSegment(outro)

            viewModel.undoSkip(before)

            assertEquals(listOf(15_000L, -15_000L), player.seekCalls)
            assertEquals(before, viewModel.positionTicks.value)
        }
    }

    // -- osd-tier-2: buffering treatments (docs/12 "Transients") --

    @Test
    fun `buffering with play intent is not misreported as a pause`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertFalse(viewModel.state.value.isBuffering)

            // A genuine stall: isPlaying is false, but playWhenReady remains
            // true because the viewer did not pause.
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            assertTrue(viewModel.state.value.isBuffering)
            assertFalse(viewModel.state.value.isPaused)
            assertTrue(viewModel.state.value.isPlaying) // control remains PAUSE
            // The session's initial paused=false report already
            // landed; BUFFERING must not add a second edge.
            assertEquals(listOf(false), gateway.reportPausedCalls)

            // Playing (not paused, not buffering) once ready and actually playing.
            player.isPlaying = true
            player.firePlaybackStateChanged(Player.STATE_READY)
            assertFalse(viewModel.state.value.isBuffering)
            assertFalse(viewModel.state.value.isPaused)
        }
    }

    @Test
    fun `hasRenderedFirstFrame flips true once and resets for a fresh session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            assertFalse(viewModel.state.value.hasRenderedFirstFrame)

            player.fireRenderedFirstFrame()
            assertTrue(viewModel.state.value.hasRenderedFirstFrame)

            // Cross the threshold and let playNext start a fresh session.
            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals("Episode Two", viewModel.state.value.itemName)

            assertFalse("a fresh session starts back in the initial-load bucket", viewModel.state.value.hasRenderedFirstFrame)
        }
    }

    // -- osd-tier-2: the unified buffering pill (docs/12 "Transients" + the
    // CLAUDE.md owner buffering directive) ----------------------------------

    @Test
    fun `bufferingInfo appears with a computed percent and throughput while stalled, and clears once ready`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            positionTicks = 0L
            bufferedPositionTicksValue = 5_000_000L // 500ms banked ahead (PlaybackTicks: 10_000 ticks/ms)
            bandwidthBytesPerSecondValue = 3_200_000L
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertNull("no stall yet -- no pill", viewModel.state.value.bufferingInfo)

            player.firePlaybackStateChanged(Player.STATE_BUFFERING)

            val info = viewModel.state.value.bufferingInfo
            assertNotNull("STATE_BUFFERING with playWhenReady (the fake's default) must show the pill", info)
            // 500ms banked / the initial-load 1_000ms resume threshold (no
            // onRenderedFirstFrame yet this session) = 50%.
            assertEquals(50, info!!.percent)
            assertEquals(3_200_000L, info.bytesPerSec)

            player.firePlaybackStateChanged(Player.STATE_READY)
            assertNull("back to READY clears the pill", viewModel.state.value.bufferingInfo)
        }
    }

    @Test
    fun `bufferingInfo measures against the higher rebuffer threshold once the first frame has already rendered`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            positionTicks = 0L
            bufferedPositionTicksValue = 10_000_000L // 1_000ms banked ahead
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()

            player.firePlaybackStateChanged(Player.STATE_BUFFERING)

            // Against the initial 1_000ms threshold this would read 100%;
            // against the post-first-frame 2_000ms rebuffer threshold (the
            // one that actually applies here) it's 50%.
            assertEquals(50, viewModel.state.value.bufferingInfo!!.percent)
        }
    }

    @Test
    fun `a normal short seek buffer transition never flashes bufferingInfo`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            positionTicks = 100_000_000L
            bufferedPositionTicksValue = 500_000_000L
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            viewModel.seek(10_000L, resolveTrickplay = false)
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)

            assertNull("routine decoder repositioning is not network slowness", viewModel.state.value.bufferingInfo)
            advanceTimeBy(USER_SEEK_BUFFERING_GRACE_MS - 1L)
            runCurrent()
            assertNull(viewModel.state.value.bufferingInfo)

            player.firePlaybackStateChanged(Player.STATE_READY)
            advanceTimeBy(2L)
            runCurrent()
            assertNull("a seek recovered inside the grace must never flash late", viewModel.state.value.bufferingInfo)
        }
    }

    @Test
    fun `a seek still stalled after the grace shows live bufferingInfo`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            positionTicks = 100_000_000L
            bufferedPositionTicksValue = 110_000_000L // 1s ahead => 50% of the rebuffer target
            bandwidthBytesPerSecondValue = 4_500_000L
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            viewModel.seek(60_000L, resolveTrickplay = false)
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)

            advanceTimeBy(USER_SEEK_BUFFERING_GRACE_MS)
            runCurrent()

            val info = viewModel.state.value.bufferingInfo
            assertNotNull("a real long seek stall must remain visible", info)
            assertEquals(50, info!!.percent)
            assertEquals(4_500_000L, info.bytesPerSec)
        }
    }

    @Test
    fun `repeated seeks restart the buffering grace from the last press`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            viewModel.seek(10_000L, resolveTrickplay = false)
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            advanceTimeBy(USER_SEEK_BUFFERING_GRACE_MS - 100L)
            runCurrent()

            viewModel.seek(10_000L, resolveTrickplay = false)
            advanceTimeBy(101L)
            runCurrent()
            assertNull("the first seek's old deadline must not leak through", viewModel.state.value.bufferingInfo)

            advanceTimeBy(USER_SEEK_BUFFERING_GRACE_MS - 101L)
            runCurrent()
            assertNotNull("a continued stall appears at the last seek's deadline", viewModel.state.value.bufferingInfo)
        }
    }

    @Test
    fun `bufferingInfo never appears for a stall the viewer paused into`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlayWhenReadyChanged(false)
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            assertNull("playWhenReady == false means the viewer paused -- nothing to blame on the network", viewModel.state.value.bufferingInfo)

            // Resuming intent while still stuck in STATE_BUFFERING must
            // show the pill immediately, without waiting for a further
            // onPlaybackStateChanged (Media3 fires these independently).
            player.firePlayWhenReadyChanged(true)
            assertNotNull(viewModel.state.value.bufferingInfo)
        }
    }

    @Test
    fun `a fresh session via playNext starts with no leftover bufferingInfo`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            assertNotNull(viewModel.state.value.bufferingInfo)

            // Cross the threshold and let playNext start a fresh session
            // while the first session's stall is still "active" on the
            // shared FakePlaybackPlayer.
            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals("Episode Two", viewModel.state.value.itemName)

            assertNull("a fresh session must never inherit the previous one's stall pill", viewModel.state.value.bufferingInfo)
        }
    }

    // -- Transient-network-error survival, exercised via PlaybackViewModel (ReconnectPolicyTest
    // covers the pure policy in isolation). --

    private fun networkError(message: String = "no network") =
        PlaybackException(message, UnknownHostException(message), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    @Test
    fun `a recoverable network error does not abandon the session and enters reconnecting instead`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(networkError())

            assertEquals(0, gateway.abandonPlaybackCallCount)
            assertEquals(0, player.stopAndClearCallCount)
            assertNull("no fatal-error event yet -- this error is recoverable", viewModel.events.replayCache.firstOrNull())
            assertEquals(ReconnectingInfo(attempt = 1), viewModel.state.value.reconnecting)
        }
    }

    @Test
    fun `an exhausted load retry budget is not multiplied by player reconnect`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { loadRetryBudgetExhausted = true }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(networkError())

            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertNull(viewModel.state.value.reconnecting)
            assertTrue(player.retryAfterErrorCalls.isEmpty())
            val event = viewModel.events.replayCache.firstOrNull()
            assertEquals(
                PlaybackEvent.FinishWithMessage("Playback error: lost connection to the server"),
                event,
            )
        }
    }

    @Test
    fun `a codec error is unaffected by the new classification and stays fatal exactly as before`() = runTest(timeout = TEST_TIMEOUT) {
        // Guards against a regression narrowing isRecoverable too broadly: an error with no cause
        // must stay fatal.
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertNull(viewModel.state.value.reconnecting)
            val event = viewModel.events.replayCache.firstOrNull()
            assertTrue(event is PlaybackEvent.FinishWithMessage)
        }
    }

    @Test
    fun `a fatal error after playback became active preserves the resume position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 42_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireIsPlayingChanged(true)
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertEquals(listOf(42_000_000L), gateway.stopPlaybackCalls)
            assertEquals(0, gateway.abandonPlaybackCallCount)
            assertEquals(1, player.stopAndClearCallCount)
            assertTrue(viewModel.events.replayCache.firstOrNull() is PlaybackEvent.FinishWithMessage)
        }
    }

    @Test
    fun `an exhausted network budget after playback became active preserves the resume position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            positionTicks = 77_000_000L
            loadRetryBudgetExhausted = true
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // retain a trustworthy position before the player enters error/idle
            player.fireIsPlayingChanged(true)
            player.positionTicks = 0L // Media3 can expose zero after the source error
            player.fireError(networkError())

            assertEquals(listOf(77_000_000L), gateway.stopPlaybackCalls)
            assertEquals(0, gateway.abandonPlaybackCallCount)
            assertNull(viewModel.state.value.reconnecting)
        }
    }

    @Test
    fun `while reconnecting the position ticker freezes on the last-known-good position and skips reporting`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 50_000_000L } // 5s in
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // last-known-good position becomes 50_000_000L
            assertEquals(50_000_000L, viewModel.positionTicks.value)
            val reportsBeforeError = gateway.reportPositionCalls.size

            player.fireError(networkError())
            // The idle-after-error player can report position 0; the ticker must never surface that
            // while reconnecting.
            player.positionTicks = 0L

            advanceOneTick()
            advanceOneTick()

            assertEquals(
                "the remembered pre-error position must never be clobbered by a live read while reconnecting",
                50_000_000L,
                viewModel.positionTicks.value,
            )
            assertEquals(
                "no position report belongs in flight while the player is idle-after-error",
                reportsBeforeError,
                gateway.reportPositionCalls.size,
            )
        }
    }

    @Test
    fun `a retry fires after the first backoff delay with the remembered position and prior play state`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 77_000_000L; playWhenReady = true }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // last-known-good position becomes 77_000_000L
            player.fireError(networkError())

            advanceTimeBy(999L)
            runCurrent()
            assertTrue("the first retry waits ~1s, not sooner", player.retryAfterErrorCalls.isEmpty())

            advanceTimeBy(2L)
            runCurrent()
            assertEquals(listOf(77_000_000L to true), player.retryAfterErrorCalls)
        }
    }

    @Test
    fun `a retry resumes paused when the viewer had paused before the error`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 10_000_000L; playWhenReady = false }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // last-known-good position becomes 10_000_000L
            player.fireError(networkError())
            advanceOneTick()

            assertEquals(listOf(10_000_000L to false), player.retryAfterErrorCalls)
        }
    }

    @Test
    fun `BUFFERING preserves reconnect budget and READY clears the episode`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(networkError())
            assertNotNull(viewModel.state.value.reconnecting)

            // A retry reaching BUFFERING is Media3's "left STATE_IDLE" recovery signal (see
            // updatePlayState's doc).
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)

            assertNotNull("prepare enters BUFFERING before its network request succeeds", viewModel.state.value.reconnecting)

            player.firePlaybackStateChanged(Player.STATE_READY)
            assertNull("READY proves the retry prepared successfully", viewModel.state.value.reconnecting)
        }
    }

    @Test
    fun `exhausting the retry budget gives up, abandons the session, and finishes with a message`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // Matches ReconnectPolicyTest's give-up schedule: 10 real retries, then the 11th error
            // gives up immediately.
            val delays = listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L)

            player.fireError(networkError())
            for (delayMs in delays) {
                advanceTimeBy(delayMs)
                runCurrent()
                player.fireError(networkError()) // this retry attempt failed again
            }

            assertEquals(delays.size, player.retryAfterErrorCalls.size)
            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
            assertNull(viewModel.state.value.reconnecting)
            val event = viewModel.events.replayCache.firstOrNull()
            assertTrue(event is PlaybackEvent.FinishWithMessage)
        }
    }

    @Test
    fun `BUFFERING then error on every retry still exhausts the shared budget`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val delays = listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L)
            player.fireError(networkError())
            for (delayMs in delays) {
                advanceTimeBy(delayMs)
                runCurrent()
                player.firePlaybackStateChanged(Player.STATE_BUFFERING)
                player.fireError(networkError())
            }

            assertEquals(delays.size, player.retryAfterErrorCalls.size)
            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertTrue(viewModel.events.replayCache.firstOrNull() is PlaybackEvent.FinishWithMessage)
        }
    }

    @Test
    fun `a Back-press exit while reconnecting cancels the pending retry and reports the remembered position`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 33_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            advanceOneTick() // last-known-good position becomes 33_000_000L
            player.fireError(networkError())
            // Simulate the same idle-after-error bogus-position hazard as
            // the freeze test above, right before the exit.
            player.positionTicks = 0L

            // PlaybackScreen's BackHandler drives onFinish -> Activity.finish() -> onStop() ->
            // stopPlaybackOnce().
            viewModel.stopPlaybackOnce()

            assertEquals(listOf(33_000_000L), gateway.stopPlaybackCalls)
            assertEquals(0, gateway.abandonPlaybackCallCount)

            // The retry must never fire into an ended session.
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(player.retryAfterErrorCalls.isEmpty())
        }
    }

    // -- docs/18-playback-quality.md §1/§3: the Auto/Cap transcode fallback --

    @Test
    fun `a non-recoverable error on a DirectPlay plan with the fallback allowed switches to the transcode plan`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(transcodeFallbackAllowed = true)
            val transcodePlan = samplePlan(
                startPositionTicks = 42_000_000L,
                playMethod = PlayMethodFfi.TRANSCODE,
                transcodeReason = "server-decided reason",
            )
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                prepareTranscodeFallbackResult = Result.success(transcodePlan),
            )
            val player = FakePlaybackPlayer().apply { positionTicks = 30_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            withSession(viewModel) {
                advanceOneTick() // last-known-good position becomes 30_000_000L
                player.fireError(error)

                assertEquals(
                    listOf(
                        FakeCoreGateway.PrepareTranscodeFallbackCall(
                            plan.itemId,
                            30_000_000L,
                            "Playback error: ${error.errorCodeName}",
                            plan.playSessionId,
                        ),
                    ),
                    gateway.prepareTranscodeFallbackCalls,
                )
                assertEquals(transcodePlan, player.loadedPlan)
                assertEquals(PlayMethodFfi.TRANSCODE, viewModel.state.value.playMethod)
                assertEquals(transcodePlan.transcodeReason, viewModel.state.value.transcodeReason)
                assertEquals(transcodePlan.startPositionTicks, viewModel.positionTicks.value)
                // The fallback is a plan swap, not an exit -- no fatal event, no stop/abandon
                // report.
                assertNull(viewModel.events.replayCache.firstOrNull())
                assertEquals(0, gateway.abandonPlaybackCallCount)
                assertTrue(gateway.stopPlaybackCalls.isEmpty())
            }
        }

    @Test
    fun `a non-recoverable error when the fallback is not allowed finishes with the server verdict message`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(transcodeFallbackAllowed = false, serverVerdict = "codec not supported")
            val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            withSession(viewModel) {
                player.fireError(error)

                assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
                assertEquals(1, gateway.abandonPlaybackCallCount)
                assertEquals(
                    PlaybackEvent.FinishWithMessage(fatalPlaybackMessage(error.errorCodeName, plan)),
                    viewModel.events.replayCache.firstOrNull(),
                )
            }
        }

    @Test
    fun `a second failure after a successful fallback is fatal and never calls the fallback again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(transcodeFallbackAllowed = true)
            val transcodePlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, transcodeReason = "boom")
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                prepareTranscodeFallbackResult = Result.success(transcodePlan),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                assertEquals(1, gateway.prepareTranscodeFallbackCalls.size)

                // The transcode stream itself now fails -- one fallback per item (docs/18 §1).
                player.fireError(PlaybackException("boom again", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

                assertEquals(
                    "no second fallback call -- the transcode plan's own failure is a plain fatal error",
                    1,
                    gateway.prepareTranscodeFallbackCalls.size,
                )
                assertEquals(1, gateway.abandonPlaybackCallCount)
                assertTrue(viewModel.events.replayCache.firstOrNull() is PlaybackEvent.FinishWithMessage)
            }
        }

    @Test
    fun `a software-only video decoder on a DirectPlay plan with the fallback allowed switches to the transcode plan`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(transcodeFallbackAllowed = true)
            val transcodePlan = samplePlan(
                startPositionTicks = 42_000_000L,
                playMethod = PlayMethodFfi.TRANSCODE,
                transcodeReason = "server-decided reason",
            )
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                prepareTranscodeFallbackResult = Result.success(transcodePlan),
            )
            val player = FakePlaybackPlayer().apply { positionTicks = 30_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                advanceOneTick() // last-known-good position becomes 30_000_000L
                player.onVideoDecoderInitialized?.invoke("OMX.google.h264.decoder")

                assertEquals(
                    listOf(
                        FakeCoreGateway.PrepareTranscodeFallbackCall(
                            plan.itemId,
                            30_000_000L,
                            "Software video decoder OMX.google.h264.decoder -- no hardware decoder on this TV",
                            plan.playSessionId,
                        ),
                    ),
                    gateway.prepareTranscodeFallbackCalls,
                )
                assertEquals(transcodePlan, player.loadedPlan)
                assertEquals(PlayMethodFfi.TRANSCODE, viewModel.state.value.playMethod)
            }
        }

    @Test
    fun `a hardware video decoder never triggers the software-decoder fallback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.onVideoDecoderInitialized?.invoke("OMX.qcom.video.decoder.avc")

            assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
            assertEquals(plan, player.loadedPlan)
        }
    }

    // -- docs/18 §3: a stale fallback result, arriving after a newer session took over the
    // screen, must never act on it. --------------------------------------------------------

    @Test
    fun `a successful fallback result arriving after a newer session started is discarded entirely`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
            val plan2 = samplePlan(itemId = "item-2", itemName = "Replacement Movie")
            val transcodePlan = samplePlan(
                itemId = "item-1",
                startPositionTicks = 42_000_000L,
                playMethod = PlayMethodFfi.TRANSCODE,
                transcodeReason = "server-decided reason",
            )
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "item-1" to Result.success(plan1),
                    "item-2" to Result.success(plan2),
                ),
                prepareTranscodeFallbackResult = Result.success(transcodePlan),
            ).apply { prepareTranscodeFallbackGate = gate }
            val player = FakePlaybackPlayer().apply { positionTicks = 30_000_000L }
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            withSession(viewModel) {
                advanceOneTick() // last-known-good position becomes 30_000_000L
                player.fireError(error) // starts the fallback negotiation; suspends on the gate

                assertEquals(1, gateway.prepareTranscodeFallbackCalls.size)

                // A newer session (an external Play/deep-link swap here, same
                // shape as a playNext transition) starts and completes
                // entirely before the old fallback negotiation resolves.
                viewModel.replaceItem(plan2.itemId)
                assertEquals(plan2, player.loadedPlan)
                assertEquals(PlayMethodFfi.DIRECT_PLAY, viewModel.state.value.playMethod)

                // The stale fallback result now lands.
                gate.complete(Unit)
                runCurrent()

                // Discarded entirely -- no load, no state update, still item-2.
                assertEquals(plan2, player.loadedPlan)
                assertEquals(PlayMethodFfi.DIRECT_PLAY, viewModel.state.value.playMethod)
                assertEquals("Replacement Movie", viewModel.state.value.itemName)
            }
        }

    @Test
    fun `a failing fallback result arriving after a newer session started emits no fatal event and never ends that session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
            val plan2 = samplePlan(itemId = "item-2", itemName = "Replacement Movie")
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "item-1" to Result.success(plan1),
                    "item-2" to Result.success(plan2),
                ),
                prepareTranscodeFallbackResult = CoreException.Api("transcode negotiation failed").asFailure(),
            ).apply { prepareTranscodeFallbackGate = gate }
            val player = FakePlaybackPlayer().apply { positionTicks = 30_000_000L }
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            withSession(viewModel) {
                advanceOneTick()
                player.fireError(error)
                assertEquals(1, gateway.prepareTranscodeFallbackCalls.size)

                viewModel.replaceItem(plan2.itemId) // ends item-1's session (its own stop call), starts item-2's
                assertEquals(plan2, player.loadedPlan)
                val stopCallsBeforeStaleResult = gateway.stopPlaybackCalls.size
                val abandonCallsBeforeStaleResult = gateway.abandonPlaybackCallCount

                // The stale failure now lands.
                gate.complete(Unit)
                runCurrent()

                // Must not touch the NEW (item-2) session at all: no extra
                // stop/abandon call, and no fatal event surfaced.
                assertEquals(stopCallsBeforeStaleResult, gateway.stopPlaybackCalls.size)
                assertEquals(abandonCallsBeforeStaleResult, gateway.abandonPlaybackCallCount)
                assertNull(viewModel.events.replayCache.firstOrNull())
                assertEquals(plan2, player.loadedPlan)
            }
        }

    @Test
    fun `the fallback call passes the current plan's own playSessionId`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true) // playSessionId = "session-1" (samplePlan's default)
        val transcodePlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, transcodeReason = "boom")
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(transcodePlan),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertEquals(plan.playSessionId, gateway.prepareTranscodeFallbackCalls.single().playSessionId)
        }
    }

    @Test
    fun `a CoreException-StalePlaybackSession fallback failure is ignored silently, even for the current session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan(transcodeFallbackAllowed = true)
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                prepareTranscodeFallbackResult = CoreException.StalePlaybackSession().asFailure(),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

                assertEquals(1, gateway.prepareTranscodeFallbackCalls.size)
                assertEquals(0, gateway.abandonPlaybackCallCount)
                assertTrue(gateway.stopPlaybackCalls.isEmpty())
                assertNull(viewModel.events.replayCache.firstOrNull())
                assertEquals(plan, player.loadedPlan)
            }
        }

    // -- fatalPlaybackMessage (docs/18-playback-quality.md §1/§3) -----------

    @Test
    fun `fatalPlaybackMessage is the bare error code when there is no plan at all`() {
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage("DECODING_FAILED", null))
    }

    @Test
    fun `fatalPlaybackMessage is the bare error code for a DirectPlay plan the server never objected to`() {
        val plan = samplePlan(transcodeFallbackAllowed = false)
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage("DECODING_FAILED", plan))
    }

    @Test
    fun `fatalPlaybackMessage is the bare error code for a Transcode plan even if serverVerdict were somehow set`() {
        val plan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, transcodeReason = "bitrate above cap")
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage("DECODING_FAILED", plan))
    }

    @Test
    fun `fatalPlaybackMessage names the server verdict and points at the Quality setting for a DirectPlay plan the server would have transcoded`() {
        val plan = samplePlan(serverVerdict = "codec not supported")
        assertEquals(
            "This file can't be Direct Played on this TV (DECODING_FAILED). Server: codec not supported. " +
                "Set Quality to Auto in Settings › Playback to let the server transcode it.",
            fatalPlaybackMessage("DECODING_FAILED", plan),
        )
    }

    // -- OSD detail setting and playback rate --------------------

    @Test
    fun `osdDetail is read from settings once per session start, defaulting to FULL`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            settings = defaultTestSettings().copy(osdDetail = OsdDetailSetting.MINIMAL),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(OsdDetailSetting.MINIMAL, viewModel.state.value.osdDetail)
        }

        val defaultGateway = FakeCoreGateway(preparePlaybackResult = Result.success(samplePlan(itemId = "item-2")))
        val defaultPlayer = FakePlaybackPlayer()
        val defaultViewModel = buildViewModel(defaultGateway, defaultPlayer, "item-2")
        withSession(defaultViewModel) {
            assertEquals(OsdDetailSetting.FULL, defaultViewModel.state.value.osdDetail)
        }
    }

    @Test
    fun `setPlaybackRate updates both the state and the player`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1f, viewModel.state.value.playbackRate)
            // start() itself already reset the (shared, singleton) player to 1x.
            assertEquals(listOf(1f), player.setPlaybackRateCalls)

            viewModel.setPlaybackRate(1.5f)

            assertEquals(1.5f, viewModel.state.value.playbackRate)
            assertEquals(listOf(1f, 1.5f), player.setPlaybackRateCalls)
        }
    }

    @Test
    fun `playbackRate resets to 1x at the top of every session, including playNext's`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            viewModel.setPlaybackRate(2.0f)
            assertEquals(2.0f, viewModel.state.value.playbackRate)
            assertEquals(2.0f, player.lastPlaybackRate)

            // Cross the threshold and let playNext start a fresh session on
            // the same shared player -- the previous session's rate must
            // never leak into it.
            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals("Episode Two", viewModel.state.value.itemName)

            assertEquals(1f, viewModel.state.value.playbackRate)
            assertEquals(1f, player.lastPlaybackRate)
            // load() then setPlaybackRate(1f) run for both sessions, in order.
            assertEquals(listOf(1f, 2.0f, 1f), player.setPlaybackRateCalls)
        }
    }

    @Test
    fun `hasPreviousEpisode and hasNextEpisode reflect what the gateway returns for an Episode`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-2")
        val previousCard = testCard(id = "ep-1", itemType = "Episode", name = "Episode One", indexNumber = 1)
        val nextCard = testCard(id = "ep-3", itemType = "Episode", name = "Episode Three", indexNumber = 3)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-2" to Result.success(plan)),
            previousEpisodeByItemId = mapOf("ep-2" to previousCard),
            nextEpisodeByItemId = mapOf("ep-2" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(listOf("ep-2"), gateway.previousEpisodeBeforeCalls)
            assertTrue(viewModel.state.value.hasPreviousEpisode)
            assertTrue(viewModel.state.value.hasNextEpisode)
        }
    }

    @Test
    fun `episode neighbor recovery cannot delay player load or READY`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-2")
        val previousCard = testCard(id = "ep-1", itemType = "Episode", name = "Episode One", indexNumber = 1)
        val nextCard = testCard(id = "ep-3", itemType = "Episode", name = "Episode Three", indexNumber = 3)
        val neighborGate = CompletableDeferred<EpisodeNeighbors?>()
        val fake = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-2" to Result.success(plan)))
        val gateway = object : CoreGateway by fake {
            override suspend fun episodeNeighbors(itemId: String, seriesId: String): EpisodeNeighbors? = neighborGate.await()
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(plan, player.loadedPlan)
            assertEquals(PlaybackUiState.Phase.READY, viewModel.state.value.phase)
            assertFalse(viewModel.state.value.hasPreviousEpisode)
            assertFalse(viewModel.state.value.hasNextEpisode)

            neighborGate.complete(EpisodeNeighbors(previousCard, nextCard))
            runCurrent()

            assertTrue(viewModel.state.value.hasPreviousEpisode)
            assertTrue(viewModel.state.value.hasNextEpisode)
        }
    }

    @Test
    fun `hasPreviousEpisode and hasNextEpisode are false with nothing configured, and a Movie never fetches either`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertFalse(viewModel.state.value.hasPreviousEpisode)
            assertFalse(viewModel.state.value.hasNextEpisode)
        }

        val moviePlan = samplePlan(itemId = "movie-1", itemType = "Movie")
        val movieGateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("movie-1" to Result.success(moviePlan)))
        val moviePlayer = FakePlaybackPlayer()
        val movieViewModel = buildViewModel(movieGateway, moviePlayer, moviePlan.itemId)
        withSession(movieViewModel) {
            assertTrue(movieGateway.previousEpisodeBeforeCalls.isEmpty())
            assertFalse(movieViewModel.state.value.hasPreviousEpisode)
            assertFalse(movieViewModel.state.value.hasNextEpisode)
        }
    }

    @Test
    fun `playPrevious ends the current session and starts the previous episode, serialized like playNext`() = runTest(timeout = TEST_TIMEOUT) {
        val plan2 = episodePlan("ep-2")
        val plan1 = episodePlan("ep-1", itemName = "Episode One")
        val previousCard = testCard(id = "ep-1", itemType = "Episode", name = "Episode One", indexNumber = 1)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-2" to Result.success(plan2),
                "ep-1" to Result.success(plan1),
            ),
            previousEpisodeByItemId = mapOf("ep-2" to previousCard),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 42_000_000L }
        val viewModel = buildViewModel(gateway, player, plan2.itemId)
        withSession(viewModel) {
            viewModel.playPrevious()
            viewModel.playPrevious() // a duplicate call must not double-transition, same guard as playNext

            assertEquals(listOf(42_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf("ep-2", "ep-1"), gateway.preparePlaybackCalls)
            assertEquals("Episode One", viewModel.state.value.itemName)
        }
    }

    @Test
    fun `playPrevious is a no-op with no previous episode known`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.playPrevious()
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
        }
    }

    // docs/12 §8 row 5: playNextEpisode is immediate, against nextEpisode directly, unlike
    // playNext's own next-up-card gate.

    @Test
    fun `playNextEpisode ends the current session and starts the next episode, serialized like playPrevious`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 42_000_000L }
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            viewModel.playNextEpisode()
            viewModel.playNextEpisode() // a duplicate call must not double-transition, same guard as playNext/playPrevious

            assertEquals(listOf(42_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)
            assertEquals("Episode Two", viewModel.state.value.itemName)
        }
    }

    @Test
    fun `playNextEpisode is a no-op with no next episode known, and does not require the next-up card to have appeared`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertNull(viewModel.state.value.nextUp)
            viewModel.playNextEpisode()
            assertEquals(listOf("ep-1"), gateway.preparePlaybackCalls)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
        }
    }

    // Every "open" function below closes every other one -- one surface at a time; each pairwise
    // closure is tested independently.

    @Test
    fun `openSpeedMenu opens itself and closes an already-open chapters menu`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openChaptersMenu()
            assertTrue(viewModel.state.value.chaptersMenuOpen)

            viewModel.openSpeedMenu()

            assertTrue(viewModel.state.value.speedMenuOpen)
            assertFalse(viewModel.state.value.chaptersMenuOpen)

            viewModel.closeSpeedMenu()
            assertFalse(viewModel.state.value.speedMenuOpen)
        }
    }

    @Test
    fun `openSpeedMenu closes an already-open track picker`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()
            assertNotNull(viewModel.state.value.trackPicker)

            viewModel.openSpeedMenu()

            assertNull(viewModel.state.value.trackPicker)
            assertTrue(viewModel.state.value.speedMenuOpen)
        }
    }

    @Test
    fun `openSpeedMenu closes an already-open stats sheet`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val detail = sampleItemDetail()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(detail)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch (docs/12 §11) openInfoOverlay needs
            viewModel.openInfoOverlay()
            assertNotNull(viewModel.state.value.statsSheetLive)

            viewModel.openSpeedMenu()

            assertNull(viewModel.state.value.statsSheetLive)
            assertTrue(viewModel.state.value.speedMenuOpen)
        }
    }

    @Test
    fun `openSpeedMenu closes an already-open library info overlay`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val detail = sampleItemDetail()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(detail)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openLibraryInfoOverlay()
            assertNotNull(viewModel.state.value.libraryInfoOverlay)

            viewModel.openSpeedMenu()

            assertNull(viewModel.state.value.libraryInfoOverlay)
            assertTrue(viewModel.state.value.speedMenuOpen)
        }
    }

    @Test
    fun `openChaptersMenu opens itself and closes an already-open speed menu`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openSpeedMenu()
            assertTrue(viewModel.state.value.speedMenuOpen)

            viewModel.openChaptersMenu()

            assertTrue(viewModel.state.value.chaptersMenuOpen)
            assertFalse(viewModel.state.value.speedMenuOpen)

            viewModel.closeChaptersMenu()
            assertFalse(viewModel.state.value.chaptersMenuOpen)
        }
    }

    @Test
    fun `openChaptersMenu closes an already-open track picker`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()
            assertNotNull(viewModel.state.value.trackPicker)

            viewModel.openChaptersMenu()

            assertNull(viewModel.state.value.trackPicker)
            assertTrue(viewModel.state.value.chaptersMenuOpen)
        }
    }

    @Test
    fun `jumpToChapterStart seeks and closes the chapters menu`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openChaptersMenu()
            assertTrue(viewModel.state.value.chaptersMenuOpen)

            viewModel.jumpToChapterStart(100_000_000L)

            assertEquals(100_000_000L, viewModel.positionTicks.value)
            assertFalse(viewModel.state.value.chaptersMenuOpen)
        }
    }

    // Exit cleanup (cancelSessionJobsAndCloseMenus, shared by stopPlaybackOnce
    // and the abandon path) must close a menu left open when the session ends,
    // not just when another surface takes its place.

    @Test
    fun `stopPlaybackOnce closes an open speed menu`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openSpeedMenu()
            assertTrue(viewModel.state.value.speedMenuOpen)

            viewModel.stopPlaybackOnce()

            assertFalse(viewModel.state.value.speedMenuOpen)
        }
    }

    @Test
    fun `abandonPlaybackOnce closes an open chapters menu`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openChaptersMenu()
            assertTrue(viewModel.state.value.chaptersMenuOpen)

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertEquals(1, gateway.abandonPlaybackCallCount)
            assertFalse(viewModel.state.value.chaptersMenuOpen)
        }
    }

    /** Format for a default-flagged track (docs/12 §8's audio & subtitles dot). */
    private fun defaultAudio(label: String, language: String, selected: Boolean) = Format.Builder()
        .setSampleMimeType("audio/mp4a-latm")
        .setLabel(label)
        .setLanguage(language)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
        .let { it to selected }

    private fun nonDefaultAudio(label: String, language: String, selected: Boolean) = Format.Builder()
        .setSampleMimeType("audio/mp4a-latm")
        .setLabel(label)
        .setLanguage(language)
        .build()
        .let { it to selected }

    @Test
    fun `nonDefaultTrackActive turns true when a non-default audio track is chosen and false again once the default is restored`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                val (englishFormat, englishSelected) = defaultAudio("English", "eng", selected = true)
                val (japaneseFormat, japaneseSelected) = nonDefaultAudio("Japanese", "jpn", selected = false)
                val tracks = Tracks(
                    listOf(
                        Tracks.Group(TrackGroup(englishFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(englishSelected)),
                        Tracks.Group(TrackGroup(japaneseFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(japaneseSelected)),
                    ),
                )
                player.fireTracksChanged(tracks)
                assertFalse("the default track is already selected -- nothing non-default yet", viewModel.state.value.nonDefaultTrackActive)

                val japaneseId = TrackMapping.toId(1, 0)
                viewModel.chooseAudio(japaneseId)
                assertTrue("a non-default audio track is now selected", viewModel.state.value.nonDefaultTrackActive)

                val englishId = TrackMapping.toId(0, 0)
                viewModel.chooseAudio(englishId)
                assertFalse("back on the default track -- the dot clears", viewModel.state.value.nonDefaultTrackActive)
            }
        }

    @Test
    fun `nonDefaultTrackActive turns true when the default subtitle track is turned off`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val defaultSubtitle = Format.Builder()
                .setSampleMimeType("text/vtt")
                .setLabel("English")
                .setLanguage("eng")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val audio = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("English").build()
            val tracks = Tracks(
                listOf(
                    Tracks.Group(TrackGroup(audio), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(TrackGroup(defaultSubtitle), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                ),
            )
            player.fireTracksChanged(tracks)
            assertFalse("the default subtitle is selected as-is -- nothing non-default yet", viewModel.state.value.nonDefaultTrackActive)

            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)

            assertTrue("turning the default subtitle off is itself a non-default state", viewModel.state.value.nonDefaultTrackActive)
        }
    }

    @Test
    fun `nonDefaultTrackActive resets to false for a fresh session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            val (englishFormat, _) = defaultAudio("English", "eng", selected = true)
            val (japaneseFormat, _) = nonDefaultAudio("Japanese", "jpn", selected = false)
            val tracks = Tracks(
                listOf(
                    Tracks.Group(TrackGroup(englishFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(TrackGroup(japaneseFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                ),
            )
            player.fireTracksChanged(tracks)
            viewModel.chooseAudio(TrackMapping.toId(1, 0))
            assertTrue(viewModel.state.value.nonDefaultTrackActive)

            player.positionTicks = 170_000_000L
            advanceOneTick()
            player.positionTicks = 200_000_000L
            advanceOneTick()
            assertEquals("Episode Two", viewModel.state.value.itemName)

            assertFalse("a fresh session starts with nothing resolved yet", viewModel.state.value.nonDefaultTrackActive)
        }
    }
}
