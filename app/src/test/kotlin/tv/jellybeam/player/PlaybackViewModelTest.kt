package tv.jellybeam.player

import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.i18n.UsLocaleRule
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
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.data.asFailure
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.data.leaveEverythingAloneDecision
import tv.jellybeam.ui.cards.testCard
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.ChapterInfoFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EpisodeNeighbors
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.FailedTrackFfi
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.NoHandle
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaybackRequest
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.SyncPlayDirective
import uniffi.jellybeam_core.SyncPlayDirectiveAt
import uniffi.jellybeam_core.SyncPlayGroup
import uniffi.jellybeam_core.SyncPlayHost
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlayNotice
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.SyncPlaySeverity
import uniffi.jellybeam_core.SyncPlayStage
import uniffi.jellybeam_core.SyncPlayUpdate
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackMemoryFfi
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
    externalSubtitles: List<ExternalSubtitleFfi> = emptyList(),
    transcodeFallbackAllowed: Boolean = false,
    playSessionId: String = "session-1",
    embeddedSubtitles: List<EmbeddedSubtitleFfi> = emptyList(),
    burnedSubtitleIndex: Int? = null,
) = PlaybackPlan(
    itemId = itemId,
    itemName = itemName,
    url = "http://server/Videos/$itemId/stream?static=true&api_key=tok",
    mediaSourceId = mediaSourceId,
    playSessionId = playSessionId,
    startPositionTicks = startPositionTicks,
    runtimeTicks = runtimeTicks,
    container = "mkv",
    itemType = itemType,
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    seriesId = seriesId,
    externalSubtitles = externalSubtitles,
    embeddedSubtitles = embeddedSubtitles,
    burnedSubtitleIndex = burnedSubtitleIndex,
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

    @get:Rule
    val usLocale = UsLocaleRule()

    /** Unconfined so `reportScope.launch { ... }` runs synchronously ([MainDispatcherRule]'s doc).
     */
    private fun testReportScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())

    /** A gateway whose OSD detail for [plan] carries [meta] as its trickplay manifest. */
    private fun osdGateway(plan: PlaybackPlan, meta: TrickplayMetaFfi?) = FakeCoreGateway(
        preparePlaybackResult = Result.success(plan),
        itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
        osdTrickplay = meta,
    )

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
        parseSidecar: suspend (String, String) -> SidecarCues? = { _, text -> sampleSidecarCues().takeIf { text == "ok" } },
        settingsStore: SettingsStore = SettingsStore(gateway, testReportScope()),
        forGroup: Boolean = false,
    ): PlaybackViewModel =
        PlaybackViewModel(
            gateway,
            player,
            itemId,
            strings = ResourceUiStrings.default,
            reportScope = reportScope,
            startFromBeginning = startFromBeginning,
            clock = clock,
            parseSidecar = parseSidecar,
            settingsStore = settingsStore,
            forGroup = forGroup,
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
        val rejected = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")
        val gateway = FakeCoreGateway(
            preparePlaybackResult = CoreException.Unauthorized(account = rejected).asFailure(),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            val event = viewModel.events.replayCache.firstOrNull()

            assertEquals("the rejected account travels with it", PlaybackEvent.ReauthorizationRequired(rejected), event)
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
        val viewModel = PlaybackViewModel(gateway, player, plan.itemId, ResourceUiStrings.default, testReportScope(), settingsStore = SettingsStore(gateway, testReportScope()))
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

    /** The session a renegotiation lands starts unpaused on the server; a viewer paused across it
     * is reported paused to the new session. */
    @Test
    fun `a pause held across a renegotiation is reported to the landed session`() = runTest(timeout = TEST_TIMEOUT) {
        val burned = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
        val clean = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "clean", embeddedSubtitles = listOf(assStream))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(burned),
            prepareTranscodeFallbackResult = Result.success(clean),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, burned.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()
            player.playbackState = Player.STATE_READY
            player.firePlayWhenReadyChanged(false)
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            runCurrent()
            assertFalse("a paused viewer stays paused across the reload", player.playWhenReady)
            player.firePlayWhenReadyChanged(false) // the callback that setting fires on a real player

            assertEquals(listOf(burned.playSessionId, "clean"), gateway.reportPausedSessionIds)
            assertEquals(listOf(true, true), gateway.reportPausedCalls)
        }
    }

    /** In a chained renegotiation the stream on screen belongs to the session the current attempt
     * took over, so that is the one reports name. */
    @Test
    fun `reports during a chained renegotiation name the session it took over`() = runTest(timeout = TEST_TIMEOUT) {
        val french = EmbeddedSubtitleFfi(index = 3, language = "fre", title = null, forced = false, default = false, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)
        val streams = listOf(assStream, french)
        fun transcode(id: String, burned: Int?) =
            samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = id, embeddedSubtitles = streams, burnedSubtitleIndex = burned)
        val start = transcode("s0", 2)
        val gates = List(2) { CompletableDeferred<Unit>() }
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(start)).apply {
            prepareTranscodeFallbackResultsByCall[0] = Result.success(transcode("s1", 3))
            prepareTranscodeFallbackResultsByCall[1] = Result.success(transcode("s2", null))
            gates.forEachIndexed { call, gate -> prepareTranscodeFallbackGatesByCall[call] = gate }
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, start.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()
            player.playbackState = Player.STATE_READY
            viewModel.chooseSubtitle(streamRowId(3))
            player.firePlayWhenReadyChanged(false)
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            gates[0].complete(Unit)
            runCurrent()
            player.firePlayWhenReadyChanged(true)

            assertEquals(listOf("s0", "s1"), gateway.reportPausedSessionIds)
            gates[1].complete(Unit)
            runCurrent()
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

    private fun FakeCoreGateway.neighborLookups() = accountBoundCalls.count { it.first == "episodeNeighbors" }

    /** docs/12 §8: a failed lookup is repeated once, on entering the next-up trigger zone. */
    @Test
    fun `a failed neighbours lookup is retried on entering the trigger zone and then answers`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        gateway.episodeNeighborsGates.add(CompletableDeferred<EpisodeNeighbors>().apply { completeExceptionally(IllegalStateException("offline")) })
        gateway.episodeNeighborsGates.add(CompletableDeferred(EpisodeNeighbors(previous = null, next = nextCard)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1, gateway.neighborLookups())
            assertFalse(viewModel.state.value.hasNextEpisode)

            player.positionTicks = 100_000_000L // 10s remaining: outside the zone
            advanceOneTick()
            assertEquals("outside the zone nothing is retried", 1, gateway.neighborLookups())

            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertEquals(2, gateway.neighborLookups())
            assertTrue(viewModel.state.value.hasNextEpisode)

            advanceOneTick()
            advanceOneTick()
            assertEquals("an answered retry is not repeated", 2, gateway.neighborLookups())
        }
    }

    @Test
    fun `a neighbours lookup that fails twice is never retried a third time`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        repeat(2) {
            gateway.episodeNeighborsGates.add(CompletableDeferred<EpisodeNeighbors>().apply { completeExceptionally(IllegalStateException("offline")) })
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 170_000_000L
            repeat(4) { advanceOneTick() }
            assertEquals(2, gateway.neighborLookups())
            assertFalse(viewModel.state.value.hasNextEpisode)
        }
    }

    @Test
    fun `a known lookup with no next episode is never retried`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val gateway = FakeCoreGateway(preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(1, gateway.neighborLookups())
            player.positionTicks = 170_000_000L
            repeat(4) { advanceOneTick() }
            assertEquals(1, gateway.neighborLookups())
            assertFalse(viewModel.state.value.hasNextEpisode)
            assertNull(viewModel.state.value.nextUp)
        }
    }

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

    /** docs/18 §2.1: detail resolves ids on the browsed account, so a playback kept on another
     * account stops without routing there.
     */
    @Test
    fun `stillWatchingStop on a playback kept across a switch just finishes`() = runTest(timeout = TEST_TIMEOUT) {
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
            gateway.setEpochs(epoch = 1uL, parked = 0uL)
            runCurrent()

            viewModel.stillWatchingStop()

            assertEquals(listOf(170_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf<PlaybackEvent>(PlaybackEvent.Finish), viewModel.events.replayCache)
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
    fun `full styling toggle is read from settings and passed through to the player's load call`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                settings = defaultTestSettings().copy(subtitleFullAssStyling = true),
            )
            val player = FakePlaybackPlayer()

            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                assertEquals(true, player.lastFullAssStyling)
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
    fun `subtitle style prefs are read from settings at session start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            settings = defaultTestSettings().copy(
                subtitleScale = 1.5f,
                subtitlePosition = SubtitlePositionPreset.HIGHER,
                subtitleBold = true,
                subtitleBackgroundOpacity = 0.5f,
                subtitleColor = SubtitleColorPreset.LIGHT_GREEN,
                subtitleUseSystemStyle = true,
            ),
        )
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(
                SubtitleStyle(
                    scale = 1.5f,
                    position = SubtitlePositionPreset.HIGHER,
                    bold = true,
                    backgroundOpacity = 0.5f,
                    color = SubtitleColorPreset.LIGHT_GREEN,
                    useSystemStyle = true,
                ),
                viewModel.state.value.subtitleStyle,
            )
        }
    }

    @Test
    fun `default subtitle style prefs match the Rust Settings default`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(SubtitleStyle(), viewModel.state.value.subtitleStyle)
        }
    }

    @Test
    fun `refreshSubtitleStyle picks up a Settings change mid-session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()

        val store = SettingsStore(gateway, testReportScope())
        val viewModel = buildViewModel(gateway, player, plan.itemId, settingsStore = store)
        withSession(viewModel) {
            store.update { it.copy(subtitleColor = SubtitleColorPreset.LIGHT_GREEN) }
            viewModel.refreshSubtitleStyle()
            assertEquals(SubtitleColorPreset.LIGHT_GREEN, viewModel.state.value.subtitleStyle.color)
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

    // -- A tick whose position report is cancelled must not evaluate next-up or auto-skip. --

    /** A 20s episode with a next episode and an auto-skipped commercial at 16s-19s, so a tick at
     * 17s both crosses the next-up threshold and enters the segment.
     */
    private fun tickEffectsFixture(): Triple<FakeCoreGateway, FakePlaybackPlayer, PlaybackPlan> {
        val plan = episodePlan("ep-1")
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 160_000_000L, 190_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
            mediaSegmentsByItemId = mapOf("ep-1" to listOf(commercial)),
            settings = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP),
        )
        val player = FakePlaybackPlayer()
        return Triple(gateway, player, plan)
    }

    @Test
    fun `a tick whose position report completes still evaluates next-up and auto-skip`() = runTest(timeout = TEST_TIMEOUT) {
        val (gateway, player, plan) = tickEffectsFixture()
        gateway.reportPositionGate = CompletableDeferred()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.isPlaying = true
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertEquals("the tick is parked in the position report", 1, gateway.reportPositionEntered)

            gateway.reportPositionGate!!.complete(Unit)
            runCurrent()

            assertNotNull("the next-up card appears", viewModel.state.value.nextUp)
            assertEquals(1, gateway.noteEpisodeFinishedCalls.size)
            assertEquals(listOf("ep-2"), gateway.preloadPlaybackCalls)
            assertEquals("the commercial is auto-skipped", listOf(2_000L), player.seekCalls)
        }
    }

    @Test
    fun `ending the session while a tick is reporting position evaluates no next-up and no auto-skip`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (gateway, player, plan) = tickEffectsFixture()
            gateway.reportPositionGate = CompletableDeferred()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.isPlaying = true
                player.positionTicks = 170_000_000L
                advanceOneTick()
                assertEquals("the tick is parked in the position report", 1, gateway.reportPositionEntered)

                viewModel.stopPlaybackOnce()
                gateway.reportPositionGate!!.complete(Unit)
                runCurrent()

                val effects = buildList {
                    if (viewModel.state.value.nextUp != null) add("next-up card")
                    if (viewModel.state.value.stillWatching != null) add("still-watching card")
                    if (gateway.noteEpisodeFinishedCalls.isNotEmpty()) add("still-watching decision")
                    if (gateway.preloadPlaybackCalls.isNotEmpty()) add("next-episode preload")
                    if (player.seekCalls.isNotEmpty()) add("auto-skip seek")
                }
                assertEquals("an ended session evaluates no tick effects", emptyList<String>(), effects)
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
        // osdTrickplay defaults to null -- the detail carries "no manifest".
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
        val gateway = osdGateway(plan, meta)
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // The OSD detail fetch awaits enrichmentGate (docs/12 §11); it resolves
            // (Unconfined) right after the simulated first frame unblocks it.
            player.fireRenderedFirstFrame()
            assertEquals(listOf(plan.mediaSourceId), gateway.getPlaybackOsdDetailMediaSources)

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
        val gateway = osdGateway(plan, meta)
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame() // unblocks the OSD detail fetch's enrichmentGate await (docs/12 §11)
            val result = viewModel.seek(100_000L) // way past the 20s duration
            assertNotNull(result)
            assertEquals(20_000L, result!!.targetPositionMs)
        }
    }

    /** A seek before the OSD detail fetch resolves fails open via `trickplayMeta == null`; a later
     * seek in the same session resolves a real preview.
     */
    @Test
    fun `a seek before the trickplay fetch resolves fails open with no preview`() = runTest(timeout = TEST_TIMEOUT) {
        val meta = sampleTrickplayMeta()
        val plan = samplePlan(runtimeTicks = 72_000_000_000L)
        val fake = osdGateway(plan, null)
        val detail = CompletableDeferred<PlaybackOsdDetail>()
        // Delegation (`by fake`) forwards every other member, including the trickplay lookups.
        val gateway = object : CoreGateway by fake {
            override suspend fun getPlaybackOsdDetail(itemId: String, mediaSourceId: String, accountEpoch: ULong?): PlaybackOsdDetail =
                detail.await()
        }
        val player = FakePlaybackPlayer()

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            // Unblocks the enrichmentGate await (docs/12 §11) so the fetch is in flight,
            // racing the seek below via the deferred detail.
            player.fireRenderedFirstFrame()
            assertNull("a seek racing ahead of an in-flight trickplay fetch must fail open", viewModel.seek(30_000L))
            assertNull(viewModel.trickplayMeta)

            detail.complete(
                PlaybackOsdDetail(container = null, mediaStreams = emptyList(), chapters = emptyList(), sizeBytes = null, path = null, trickplay = meta),
            )
            runCurrent()
            val result = viewModel.seek(30_000L)
            assertNotNull("once the fetch resolves, the same session's later seeks must resolve a real preview", result)
        }
    }

    // -- Enrichment gate (docs/12 §11, docs/18): OSD detail/trickplay/server name wait for the
    // first rendered frame or a 1500ms fallback; segments and episode neighbors stay immediate. --

    @Test
    fun `OSD detail (with its trickplay manifest) and server name are not requested before the first frame`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            osdTrickplay = sampleTrickplayMeta(),
            serverDisplayNameValue = "shelf.test",
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertTrue(gateway.getPlaybackOsdDetailCalls.isEmpty())
            assertEquals(0, gateway.serverDisplayNameCallCount)
        }
    }

    @Test
    fun `OSD detail (with its trickplay manifest) and server name are requested right after the first rendered frame`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val meta = sampleTrickplayMeta()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            osdTrickplay = meta,
            serverDisplayNameValue = "shelf.test",
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
            assertEquals(listOf(plan.mediaSourceId), gateway.getPlaybackOsdDetailMediaSources)
            assertEquals(1, gateway.serverDisplayNameCallCount)
        }
    }

    @Test
    fun `OSD detail (with its trickplay manifest) and server name are requested 1500ms after load when no frame ever renders`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val meta = sampleTrickplayMeta()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
                osdTrickplay = meta,
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
                assertEquals(listOf(plan.mediaSourceId), gateway.getPlaybackOsdDetailMediaSources)
                assertEquals(1, gateway.serverDisplayNameCallCount)
            }
        }

    /** docs/10: one enrichment fetch per playback -- the OSD detail carries the trickplay manifest. */
    @Test
    fun `the trickplay manifest comes from the one OSD detail fetch, with no separate trickplay request`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 72_000_000_000L)
        val meta = sampleTrickplayMeta()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            osdTrickplay = meta,
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)
            assertEquals(listOf(plan.mediaSourceId), gateway.getPlaybackOsdDetailMediaSources)
            assertEquals(meta, viewModel.trickplayMeta)
            assertNotNull(viewModel.seek(30_000L))
        }
    }

    /** docs/10: Play dispatches the negotiation before the main thread builds the lazy ExoPlayer. */
    @Test
    fun `preparePlayback is dispatched, and the plan adopted, before the player is built`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val fake = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val gate = CompletableDeferred<Unit>().also { fake.preparePlaybackGates[plan.itemId] = it }
        val order = mutableListOf<String>()
        val gateway = object : CoreGateway by fake {
            override suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean, request: PlaybackRequest): PlaybackPlan {
                order += "prepare"
                return fake.preparePlayback(itemId, startFromBeginning, request)
            }
        }
        val player = FakePlaybackPlayer().apply { onListenerAdded = { order += "playerBuilt" } }

        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals("the negotiation is in flight and the player is not built yet", listOf("prepare"), order)
            gate.complete(Unit)
            runCurrent()
            assertEquals("the player is wired once, when the plan is adopted", listOf("prepare", "playerBuilt"), order)
            assertEquals(plan, player.loadedPlan)
        }
    }

    /** docs/09: the settings store is the one reader -- a start and every resume share its single read. */
    @Test
    fun `starting and resuming read the settings once, through the store`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.refreshSubtitleStyle()
            viewModel.refreshSubtitleStyle()
            assertEquals(1, gateway.getSettingsCallCount)
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
            assertEquals(listOf("ep-1"), gateway.episodeNeighborsItemIds)
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
                listOf(FakeCoreGateway.RememberTrackMemoryCall("series-1", TrackKindFfi.AUDIO, TrackMemoryFfi.Key(gateway.trackPrefKeyOf(japaneseInfo)!!))),
                gateway.rememberTrackMemoryCalls,
            )

            val picker = viewModel.state.value.trackPicker!!
            assertFalse("the previously-selected English row must lose its check", picker.audioTracks[0].selected)
            assertTrue("the newly-chosen Japanese row must gain the check immediately, without waiting for a fresh onTracksChanged", picker.audioTracks[1].selected)
        }
    }

    @Test
    fun `chooseSubtitle with Off applies OFF, remembers Off, and marks Off selected`() = runTest(timeout = TEST_TIMEOUT) {
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
                listOf(FakeCoreGateway.RememberTrackMemoryCall("series-1", TrackKindFfi.SUBTITLE, TrackMemoryFfi.Off)),
                gateway.rememberTrackMemoryCalls,
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
                listOf(FakeCoreGateway.RememberTrackMemoryCall("series-1", TrackKindFfi.SUBTITLE, TrackMemoryFfi.Key(gateway.trackPrefKeyOf(englishSubtitleInfo)!!))),
                gateway.rememberTrackMemoryCalls,
            )
            val picker = viewModel.state.value.trackPicker!!
            assertFalse(picker.subtitleTracks[0].selected) // Off
            assertTrue(picker.subtitleTracks[1].selected) // English
        }
    }

    @Test
    fun `a Movie plan applies the picker's choice but never calls rememberTrackMemory`() = runTest(timeout = TEST_TIMEOUT) {
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
            assertTrue("a Movie (no seriesId) has nothing to key a per-series memory on", gateway.rememberTrackMemoryCalls.isEmpty())
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
            assertTrue(gateway.rememberTrackMemoryCalls.isEmpty())
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

            viewModel.closeSurface()
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

            viewModel.closeSurface()
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
            assertEquals("4", content.value.fields.single { it.label == "Play count" }.value)

            viewModel.closeSurface()
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

    // -- docs/12 §9: unseekable files never seekTo (a seek would restart from 0:00) --

    @Test
    fun `an unseekable file refuses every seek path and raises the notice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val chapters = listOf(sampleChapter("Cold Open", 0L), sampleChapter("Act One", 100_000_000L))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail(chapters = chapters))),
        )
        val player = FakePlaybackPlayer().apply { seekabilityValue = Seekability.UNSEEKABLE }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            player.firePlaybackStateChanged(Player.STATE_READY)
            assertEquals(Seekability.UNSEEKABLE, viewModel.state.value.seekability)

            assertNull(viewModel.seek(10_000L))
            assertEquals(0L, viewModel.tapSeek(10_000L))
            assertNull(viewModel.jumpToChapter(forward = true))
            assertNull(viewModel.skipSegment(sampleSegment(MediaSegmentKind.INTRO, 10_000_000L, 20_000_000L)))
            viewModel.undoSkip(0L)
            viewModel.jumpToChapterStart(100_000_000L)
            viewModel.commitGlide(5_000L, endClamped = true)

            assertTrue("no seekTo may reach the player", player.seekCalls.isEmpty())
            assertEquals(0L, viewModel.positionTicks.value)
            assertEquals(0, player.pauseCallCount)
            assertEquals(PlaybackEvent.SeekUnavailable, viewModel.events.replayCache.first())
            viewModel.noticeShown()
            assertTrue("a shown notice is not replayed", viewModel.events.replayCache.isEmpty())
        }
    }

    @Test
    fun `openChaptersMenu is refused on an unseekable file with a notice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val chapters = listOf(sampleChapter("Cold Open", 0L), sampleChapter("Act One", 100_000_000L))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail(chapters = chapters))),
        )
        val player = FakePlaybackPlayer().apply { seekabilityValue = Seekability.UNSEEKABLE }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireRenderedFirstFrame()
            player.firePlaybackStateChanged(Player.STATE_READY)

            viewModel.openChaptersMenu()

            assertFalse(viewModel.state.value.chaptersMenuOpen)
            assertEquals(PlaybackEvent.SeekUnavailable, viewModel.events.replayCache.first())
            viewModel.noticeShown()

            player.seekabilityValue = Seekability.SEEKABLE
            viewModel.openChaptersMenu()
            assertTrue(viewModel.state.value.chaptersMenuOpen)
        }
    }

    @Test
    fun `an unseekable file never auto-skips a segment`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 50_000_000L, 100_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(commercial)),
            settings = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP),
        )
        val player = FakePlaybackPlayer().apply {
            positionTicks = 60_000_000L
            seekabilityValue = Seekability.UNSEEKABLE
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_READY)
            advanceOneTick()

            assertTrue(player.seekCalls.isEmpty())
            assertFalse(viewModel.events.replayCache.firstOrNull() is PlaybackEvent.AutoSkipped)
        }
    }

    @Test
    fun `a seekable file still seeks after READY`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_READY)
            assertEquals(Seekability.SEEKABLE, viewModel.state.value.seekability)
            viewModel.seek(10_000L)
            assertEquals(listOf(10_000L), player.seekCalls)
        }
    }

    @Test
    fun `a resume an unseekable file can't honor raises the notice once`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 1_800_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { seekabilityValue = Seekability.UNSEEKABLE }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_READY)
            assertEquals(PlaybackEvent.SeekUnavailable, viewModel.events.replayCache.firstOrNull())
            viewModel.noticeShown()
            player.firePlaybackStateChanged(Player.STATE_READY)
            assertTrue("raised once per session", viewModel.events.replayCache.isEmpty())
        }
    }

    @Test
    fun `seeks are dropped quietly while seekability is still unknown`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { seekabilityValue = Seekability.UNKNOWN }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            assertEquals(Seekability.UNKNOWN, viewModel.state.value.seekability)

            assertNull(viewModel.seek(10_000L))
            assertNull(viewModel.skipSegment(sampleSegment(MediaSegmentKind.INTRO, 10_000_000L, 20_000_000L)))
            assertTrue("no seekTo while unknown", player.seekCalls.isEmpty())
            assertFalse(viewModel.events.replayCache.firstOrNull() is PlaybackEvent.SeekUnavailable)

            player.seekabilityValue = Seekability.SEEKABLE
            player.firePlaybackStateChanged(Player.STATE_READY)
            viewModel.seek(10_000L)
            assertEquals(listOf(10_000L), player.seekCalls)
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
            val before = checkNotNull(viewModel.skipSegment(outro))

            viewModel.undoSkip(before)

            assertEquals(listOf(15_000L, -15_000L), player.seekCalls)
            assertEquals(before, viewModel.positionTicks.value)
        }
    }

    @Test
    fun `a skip leaves the undo token carrying the pre-skip position and segment`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertNull(viewModel.state.value.skipUndo)

            val before = checkNotNull(viewModel.skipSegment(sampleSegment(MediaSegmentKind.OUTRO, 100_000_000L, 150_000_000L)))

            assertEquals(SkipUndoState(before, MediaSegmentKind.OUTRO), viewModel.state.value.skipUndo)
        }
    }

    @Test
    fun `undo within the window seeks back to the pre-skip position and clears the token`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val before = checkNotNull(viewModel.skipSegment(sampleSegment(MediaSegmentKind.OUTRO, 100_000_000L, 150_000_000L)))
            player.positionTicks = 150_000_000L // the player has landed at the segment end
            advanceTimeBy(SKIP_UNDO_WINDOW_MS - 1)

            viewModel.undoSkip()

            assertEquals(listOf(15_000L, -15_000L), player.seekCalls)
            assertEquals(before, viewModel.positionTicks.value)
            assertNull(viewModel.state.value.skipUndo)
        }
    }

    @Test
    fun `after the window the token is cleared and undo does nothing`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.skipSegment(sampleSegment(MediaSegmentKind.OUTRO, 100_000_000L, 150_000_000L))
            advanceTimeBy(SKIP_UNDO_WINDOW_MS - 1)
            runCurrent()
            assertNotNull("still offered one tick before expiry", viewModel.state.value.skipUndo)

            advanceTimeBy(1)
            runCurrent()
            assertNull(viewModel.state.value.skipUndo)

            viewModel.undoSkip()

            assertEquals(listOf(15_000L), player.seekCalls)
        }
    }

    @Test
    fun `dismissing the toast clears the token without seeking`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.skipSegment(sampleSegment(MediaSegmentKind.OUTRO, 100_000_000L, 150_000_000L))

            viewModel.dismissSkipUndo()
            viewModel.undoSkip()

            assertNull(viewModel.state.value.skipUndo)
            assertEquals(listOf(15_000L), player.seekCalls)
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
        val player = FakePlaybackPlayer().apply { positionTicks = 10_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.pause() // the viewer, after the load resumed
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
                            FailedTrackFfi.UNKNOWN,
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
                    PlaybackEvent.FinishWithMessage(fatalPlaybackMessage(ResourceUiStrings.default, error.errorCodeName, plan)),
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
                            FailedTrackFfi.VIDEO,
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
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage(ResourceUiStrings.default, "DECODING_FAILED", null))
    }

    @Test
    fun `fatalPlaybackMessage is the bare error code for a DirectPlay plan the server never objected to`() {
        val plan = samplePlan(transcodeFallbackAllowed = false)
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage(ResourceUiStrings.default, "DECODING_FAILED", plan))
    }

    @Test
    fun `fatalPlaybackMessage is the bare error code for a Transcode plan even if serverVerdict were somehow set`() {
        val plan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, transcodeReason = "bitrate above cap")
        assertEquals("Playback error: DECODING_FAILED", fatalPlaybackMessage(ResourceUiStrings.default, "DECODING_FAILED", plan))
    }

    @Test
    fun `fatalPlaybackMessage names the server verdict and points at the Quality setting for a DirectPlay plan the server would have transcoded`() {
        val plan = samplePlan(serverVerdict = "codec not supported")
        assertEquals(
            "This file can't be Direct Played on this TV (DECODING_FAILED). Server: codec not supported. " +
                "Set Quality to Auto in Settings › Playback to let the server transcode it.",
            fatalPlaybackMessage(ResourceUiStrings.default, "DECODING_FAILED", plan),
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
            assertEquals(listOf("ep-2"), gateway.episodeNeighborsItemIds)
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
            override suspend fun episodeNeighbors(itemId: String, seriesId: String, accountEpoch: ULong?): EpisodeNeighbors? = neighborGate.await()
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
            assertTrue(movieGateway.episodeNeighborsItemIds.isEmpty())
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
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail(chapters = listOf(sampleChapter("Cold Open", 0L))))),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.openChaptersMenu()
            assertTrue(viewModel.state.value.chaptersMenuOpen)

            viewModel.openSpeedMenu()

            assertTrue(viewModel.state.value.speedMenuOpen)
            assertFalse(viewModel.state.value.chaptersMenuOpen)

            viewModel.closeSurface()
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

            viewModel.closeSurface()
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
                player.fireTracksChanged(Tracks.EMPTY) // a reconnect retry's, before the tracks return
                assertTrue("an empty announcement says nothing", viewModel.state.value.nonDefaultTrackActive)
                player.fireTracksChanged(
                    Tracks(
                        listOf(
                            Tracks.Group(TrackGroup(englishFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                            Tracks.Group(TrackGroup(japaneseFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                        ),
                    ),
                )
                assertTrue("the returning tracks still read the choice", viewModel.state.value.nonDefaultTrackActive)

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

    // -- Sidecar subtitles (docs/18-playback-quality.md §3.2) --------------

    private fun sampleSidecar(index: Int = 5) = ExternalSubtitleFfi(
        index = index, codec = "srt", language = "eng", displayTitle = null, isDefault = false, isForced = false,
    )

    private val subtitlesOff = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)

    @Test
    fun `choosing a sidecar drops embedded text, fetches it once, and shows its cues`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar()))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "ok"))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val sidecarId = ExternalSubtitles.idFor(5)
            val row = viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == sidecarId }
            assertEquals("en srt · External", row.meta)
            assertTrue("nothing is fetched before the pick", gateway.fetchExternalSubtitleCalls.isEmpty())

            viewModel.chooseSubtitle(sidecarId)

            assertEquals(subtitlesOff, player.applyTrackDecisionCalls.last().first)
            assertEquals(listOf("session-1" to 5), gateway.fetchExternalSubtitleCalls)
            assertEquals("a loaded sidecar is remembered", 1, gateway.rememberTrackMemoryCalls.size)
            assertNotNull(viewModel.state.value.sidecarCues)
            assertTrue(viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == sidecarId }.selected)

            val embeddedId = TrackMapping.toTrackInfos(tracks).single { it.kind == TrackKindFfi.SUBTITLE }.id
            viewModel.chooseSubtitle(embeddedId)
            assertNull(viewModel.state.value.sidecarCues)
            assertEquals(embeddedId, player.applyTrackDecisionCalls.last().first.subtitleTrackId)

            viewModel.chooseSubtitle(sidecarId)
            assertNotNull(viewModel.state.value.sidecarCues)
            assertEquals("a second pick reuses the parsed file", 1, gateway.fetchExternalSubtitleCalls.size)
        }
    }

    @Test
    fun `with full styling on an ass sidecar loads whole into the overlay, and leaving it hides it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar().copy(codec = "ass")))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val target = AssSidecarTarget(AssOverlay(NoHandle), "sidecar:5", 1uL)
        player.assTarget = { index -> target.takeIf { index == 5 } }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            val tracks = pickerTracks()
            player.fireTracksChanged(tracks)
            viewModel.openTrackPicker()
            val sidecarId = ExternalSubtitles.idFor(5)

            viewModel.chooseSubtitle(sidecarId)

            assertEquals(subtitlesOff, player.applyTrackDecisionCalls.last().first)
            assertEquals(listOf(Triple("session-1", 5, "sidecar:5")), gateway.loadAssSidecarCalls)
            assertTrue("never read as text", gateway.fetchExternalSubtitleCalls.isEmpty())
            assertEquals(target, player.shownAssSidecars.last())
            assertNull("no plain cues alongside it", viewModel.state.value.sidecarCues)
            assertTrue(viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == sidecarId }.selected)

            viewModel.chooseSubtitle(TrackMapping.toTrackInfos(tracks).single { it.kind == TrackKindFfi.SUBTITLE }.id)
            assertNull("an embedded pick hides it", player.shownAssSidecars.last())
        }
    }

    @Test
    fun `the sidecar feed runs on the position behind the subtitle delay`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1")
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(FakeCoreGateway(preparePlaybackResult = Result.success(plan)), player, plan.itemId)
        player.positionTicks = PlaybackTicks.msToTicks(10_000)
        withSession(viewModel) {
            player.setSubtitleDelayMs(2_000)
            assertEquals(8_000_000L, viewModel.sidecarClockUs())
            player.setSubtitleDelayMs(-500)
            assertEquals(10_500_000L, viewModel.sidecarClockUs())
        }
    }

    @Test
    fun `a styled ass sidecar the overlay refuses goes back to Off with a notice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar().copy(codec = "ass")))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)).apply { assSidecarLoads = false }
        val player = FakePlaybackPlayer()
        player.assTarget = { AssSidecarTarget(AssOverlay(NoHandle), "sidecar:$it", 1uL) }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()

            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            assertTrue("never shown", player.shownAssSidecars.all { it == null })
            assertEquals(PlaybackEvent.SubtitleUnavailable, viewModel.events.replayCache.last())
            assertTrue(viewModel.state.value.trackPicker!!.subtitleTracks.first().selected)
        }
    }

    @Test
    fun `with full styling off an ass sidecar is read as plain cues`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar().copy(codec = "ass")))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "ok"))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()

            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            assertEquals(listOf("session-1" to 5), gateway.fetchExternalSubtitleCalls)
            assertTrue(gateway.loadAssSidecarCalls.isEmpty())
            assertNotNull(viewModel.state.value.sidecarCues)
        }
    }

    @Test
    fun `a sidecar that fails to load goes back to Off with a notice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar()))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "malformed"))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()

            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            assertNull(viewModel.state.value.sidecarCues)
            assertEquals(PlaybackEvent.SubtitleUnavailable, viewModel.events.replayCache.last())
            val rows = viewModel.state.value.trackPicker!!.subtitleTracks
            assertTrue("Off reads selected again", rows.first().selected)
            assertEquals("en srt · External · Unavailable", rows.single { it.id == ExternalSubtitles.idFor(5) }.meta)
            assertTrue("a broken file is never remembered for the series", gateway.rememberTrackMemoryCalls.isEmpty())
        }
    }

    @Test
    fun `a core panic while fetching a sidecar costs the track, not playback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(externalSubtitles = listOf(sampleSidecar()))
        val gateway = object : CoreGateway by FakeCoreGateway(preparePlaybackResult = Result.success(plan)) {
            override suspend fun fetchExternalSubtitle(playSessionId: String, index: Int): String? =
                throw IllegalStateException("panic")
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())

            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            assertNull(viewModel.state.value.sidecarCues)
            assertEquals(PlaybackEvent.SubtitleUnavailable, viewModel.events.replayCache.last())
        }
    }

    @Test
    fun `a fallback to another media source drops its sidecar and re-picks the same language there`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(
            mediaSourceId = "src-2",
            playMethod = PlayMethodFfi.TRANSCODE,
            externalSubtitles = listOf(sampleSidecar(5).copy(language = "fra"), sampleSidecar(7)),
            playSessionId = "session-2",
        )
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
            externalSubtitleText = mapOf(5 to "ok", 7 to "ok"),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            val firstCues = viewModel.state.value.sidecarCues
            viewModel.openTrackPicker()

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertEquals("the re-pick fetches under the new session", listOf("session-1" to 5, "session-2" to 7), gateway.fetchExternalSubtitleCalls)
            assertNull("the old source's rows can't be picked", viewModel.state.value.trackPicker)
            assertNotNull(viewModel.state.value.sidecarCues)
            assertTrue("the old source's cues are gone", viewModel.state.value.sidecarCues !== firstCues)
        }
    }

    @Test
    fun `a fallback on the same media source keeps the showing sidecar`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, externalSubtitles = listOf(sampleSidecar(5)))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
            externalSubtitleText = mapOf(5 to "ok"),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            val cues = viewModel.state.value.sidecarCues

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertSame(cues, viewModel.state.value.sidecarCues)
            assertEquals(1, gateway.fetchExternalSubtitleCalls.size)
        }
    }

    @Test
    fun `a same-source fallback that stops listing a loaded sidecar keeps showing it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, externalSubtitles = emptyList())
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
            externalSubtitleText = mapOf(5 to "ok"),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            val cues = viewModel.state.value.sidecarCues

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            player.fireTracksChanged(pickerTracks()) // the reloaded player announces its tracks

            assertSame(cues, viewModel.state.value.sidecarCues)
            viewModel.openTrackPicker()
            assertTrue("its row stays, selected", viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == ExternalSubtitles.idFor(5) }.selected)
        }
    }

    @Test
    fun `stopping clears the sidecar so a stopped player never draws its first cue`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(externalSubtitles = listOf(sampleSidecar(5)))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "ok"))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            assertNotNull(viewModel.state.value.sidecarCues)

            viewModel.stopPlaybackOnce()

            assertNull(viewModel.state.value.sidecarCues)
        }
    }

    @Test
    fun `an open picker is rebuilt from the player's new tracks`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()
            assertEquals(2, viewModel.state.value.trackPicker!!.subtitleTracks.size)

            player.fireTracksChanged(Tracks(pickerTracks().groups.take(2)))

            assertEquals(listOf(TRACK_PICKER_SUBTITLE_OFF_ID), viewModel.state.value.trackPicker!!.subtitleTracks.map { it.id })
        }
    }

    @Test
    fun `automatic selection can choose a sidecar`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(seriesId = "series-1", externalSubtitles = listOf(sampleSidecar()))
        val decision = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = ExternalSubtitles.idFor(5))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            resolveTracksResult = decision,
            externalSubtitleText = mapOf(5 to "ok"),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())

            assertTrue(gateway.resolveTracksCalls.single().tracks.any { it.id == ExternalSubtitles.idFor(5) })
            assertTrue(player.applyTrackDecisionCalls.all { it.first.subtitleTrackId == null })
            assertEquals(SubtitleActionFfi.OFF, player.applyTrackDecisionCalls.first().first.subtitleAction)
            assertNotNull(viewModel.state.value.sidecarCues)
        }
    }

    /** Distinct parsed cues per file text, so a test can tell whose result is showing. */
    private fun cuesPerText(): Pair<MutableMap<String, SidecarCues>, suspend (String, String) -> SidecarCues?> {
        val byText = mutableMapOf<String, SidecarCues>()
        return byText to { _, text -> byText.getOrPut(text) { sampleSidecarCues() } }
    }

    private fun sidecarMeta(viewModel: PlaybackViewModel, index: Int): String? {
        viewModel.openTrackPicker()
        return viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == ExternalSubtitles.idFor(index) }.meta
    }

    @Test
    fun `a pick made while another sidecar loads wins, and the first one's late result never shows`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(externalSubtitles = listOf(sampleSidecar(5), sampleSidecar(7).copy(language = "fra")))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "five", 7 to "seven"))
        val gate = CompletableDeferred<Unit>().also { gateway.externalSubtitleGates["session-1" to 5] = it }
        val (cues, parse) = cuesPerText()
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId, parseSidecar = parse)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            assertEquals("en srt · External · Loading…", sidecarMeta(viewModel, 5))

            viewModel.chooseSubtitle(ExternalSubtitles.idFor(7))
            assertSame(cues["seven"], viewModel.state.value.sidecarCues)
            assertEquals("the abandoned load stops reading Loading", "en srt · External", sidecarMeta(viewModel, 5))

            gate.complete(Unit)
            runCurrent()

            assertSame("the late file never replaces the pick", cues["seven"], viewModel.state.value.sidecarCues)
            assertNull("its parse never ran", cues["five"])
            assertFalse(viewModel.events.replayCache.any { it == PlaybackEvent.SubtitleUnavailable })
        }
    }

    @Test
    fun `a new session mid-fetch drops the old file's result, even at the same index`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1", externalSubtitles = listOf(sampleSidecar(5)))
        val plan2 = samplePlan(itemId = "item-2", externalSubtitles = listOf(sampleSidecar(5)), playSessionId = "session-2")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(plan2)),
            externalSubtitleText = mapOf(5 to "ok"),
        )
        val gate = CompletableDeferred<Unit>().also { gateway.externalSubtitleGates["session-1" to 5] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            viewModel.replaceItem(plan2.itemId)
            runCurrent()
            assertEquals(plan2, player.loadedPlan)
            gate.complete(Unit)
            runCurrent()

            assertNull("item-1's file never draws over item-2", viewModel.state.value.sidecarCues)
            player.fireTracksChanged(pickerTracks())
            assertEquals("item-2's row was never loaded or chosen", "en srt · External", sidecarMeta(viewModel, 5))
            assertFalse(viewModel.state.value.trackPicker!!.subtitleTracks.single { it.id == ExternalSubtitles.idFor(5) }.selected)
        }
    }

    @Test
    fun `a new item never reuses the last item's parsed file at the same index`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1", externalSubtitles = listOf(sampleSidecar(5)))
        val plan2 = samplePlan(itemId = "item-2", externalSubtitles = listOf(sampleSidecar(5)), playSessionId = "session-2")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(plan2)),
        ).apply { externalSubtitleTextBySession = mapOf(("session-1" to 5) to "one", ("session-2" to 5) to "two") }
        val (cues, parse) = cuesPerText()
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId, parseSidecar = parse)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            assertSame(cues["one"], viewModel.state.value.sidecarCues)

            viewModel.replaceItem(plan2.itemId)
            runCurrent()
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            assertEquals(listOf("session-1" to 5, "session-2" to 5), gateway.fetchExternalSubtitleCalls)
            assertSame("item-2 shows its own file", cues["two"], viewModel.state.value.sidecarCues)
        }
    }

    @Test
    fun `a same-source fallback mid-fetch asks again under the new session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, externalSubtitles = listOf(sampleSidecar(5)), playSessionId = "session-2")
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
        ).apply {
            // The core cleared session-1's sidecars when the fallback stopped it.
            externalSubtitleTextBySession = mapOf(("session-1" to 5) to null, ("session-2" to 5) to "ok")
        }
        val gate = CompletableDeferred<Unit>().also { gateway.externalSubtitleGates["session-1" to 5] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            assertEquals(fallbackPlan, player.loadedPlan)
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("session-1" to 5, "session-2" to 5), gateway.fetchExternalSubtitleCalls)
            assertNotNull("the same file shows once the new session serves it", viewModel.state.value.sidecarCues)
            assertFalse("the old session's refusal is not the file's failure", viewModel.events.replayCache.any { it == PlaybackEvent.SubtitleUnavailable })
        }
    }

    /** A fallback whose negotiation is held open, with session-1's fetch already refused (the
     * core retired its sidecars when the fallback stopped it). */
    private fun refusedMidSwap(fallbackSourceId: String): Triple<FakeCoreGateway, CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val plan = samplePlan(seriesId = "series-1", transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(
            seriesId = "series-1",
            mediaSourceId = fallbackSourceId,
            playMethod = PlayMethodFfi.TRANSCODE,
            externalSubtitles = listOf(sampleSidecar(5)),
            playSessionId = "session-2",
        )
        val negotiation = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
        ).apply {
            prepareTranscodeFallbackGate = negotiation
            externalSubtitleTextBySession = mapOf(("session-1" to 5) to null, ("session-2" to 5) to "ok")
        }
        val oldFetch = CompletableDeferred<Unit>().also { gateway.externalSubtitleGates["session-1" to 5] = it }
        return Triple(gateway, oldFetch, negotiation)
    }

    @Test
    fun `a refusal that lands before a same-source fallback finishes keeps the pick and asks the new session`() = runTest(timeout = TEST_TIMEOUT) {
        val (gateway, oldFetch, negotiation) = refusedMidSwap(fallbackSourceId = "src-1")
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            oldFetch.complete(Unit)
            runCurrent()
            assertFalse("the old session's refusal is not the file failing", viewModel.events.replayCache.any { it == PlaybackEvent.SubtitleUnavailable })
            assertEquals("still chosen while the swap negotiates", "en srt · External · Loading…", sidecarMeta(viewModel, 5))

            negotiation.complete(Unit)
            runCurrent()

            assertEquals(listOf("session-1" to 5, "session-2" to 5), gateway.fetchExternalSubtitleCalls)
            assertNotNull(viewModel.state.value.sidecarCues)
            assertEquals("the manual pick is still remembered once it loads", 1, gateway.rememberTrackMemoryCalls.size)
        }
    }

    @Test
    fun `a refusal that lands before a fallback to another source finishes re-picks the same language there`() = runTest(timeout = TEST_TIMEOUT) {
        val (gateway, oldFetch, negotiation) = refusedMidSwap(fallbackSourceId = "src-2")
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            oldFetch.complete(Unit)
            runCurrent()
            negotiation.complete(Unit)
            runCurrent()

            assertEquals(listOf("session-1" to 5, "session-2" to 5), gateway.fetchExternalSubtitleCalls)
            assertNotNull(viewModel.state.value.sidecarCues)
            assertFalse(viewModel.events.replayCache.any { it == PlaybackEvent.SubtitleUnavailable })
        }
    }

    @Test
    fun `Off chosen while a fallback negotiates wins over the refused pick`() = runTest(timeout = TEST_TIMEOUT) {
        val (gateway, oldFetch, negotiation) = refusedMidSwap(fallbackSourceId = "src-1")
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, "item-1")
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            oldFetch.complete(Unit)
            runCurrent()

            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            negotiation.complete(Unit)
            runCurrent()
            player.fireTracksChanged(pickerTracks()) // the reloaded player announces its tracks

            assertEquals("never asked again", listOf("session-1" to 5), gateway.fetchExternalSubtitleCalls)
            assertNull(viewModel.state.value.sidecarCues)
            assertEquals("en srt · External", sidecarMeta(viewModel, 5))
        }
    }

    @Test
    fun `a fallback to another source mid-fetch never lets the old file land`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, externalSubtitles = listOf(sampleSidecar(5)))
        val fallbackPlan = samplePlan(
            mediaSourceId = "src-2",
            playMethod = PlayMethodFfi.TRANSCODE,
            externalSubtitles = listOf(sampleSidecar(5)),
            playSessionId = "session-2",
        )
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallbackPlan),
        ).apply { externalSubtitleTextBySession = mapOf(("session-1" to 5) to "old", ("session-2" to 5) to "new") }
        val oldGate = CompletableDeferred<Unit>().also { gateway.externalSubtitleGates["session-1" to 5] = it }
        val (cues, parse) = cuesPerText()
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId, parseSidecar = parse)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.chooseSubtitle(ExternalSubtitles.idFor(5))

            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()
            assertSame("the re-pick on the new source shows its own file", cues["new"], viewModel.state.value.sidecarCues)

            oldGate.complete(Unit)
            runCurrent()

            assertSame(cues["new"], viewModel.state.value.sidecarCues)
            assertNull("the old source's file was never parsed", cues["old"])
        }
    }

    @Test
    fun `a timeline change alone settles seekability and raises the resume notice`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 1_800_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { seekabilityValue = Seekability.UNKNOWN }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            assertEquals(Seekability.UNKNOWN, viewModel.state.value.seekability)

            player.seekabilityValue = Seekability.UNSEEKABLE
            player.fireTimelineChanged()

            assertEquals(Seekability.UNSEEKABLE, viewModel.state.value.seekability)
            assertEquals(PlaybackEvent.SeekUnavailable, viewModel.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `a reconnect on an unseekable file restarts from zero and says so once`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply {
            seekabilityValue = Seekability.UNSEEKABLE
            positionTicks = 33_000_000L
        }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_READY)
            advanceOneTick()

            player.fireError(networkError())
            assertEquals(PlaybackEvent.SeekUnavailable, viewModel.events.replayCache.lastOrNull())
            viewModel.noticeShown()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals("a seek would restart anyway, so the retry asks for 0:00", 0L, player.retryAfterErrorCalls.first().first)

            player.fireError(networkError())
            advanceTimeBy(30_000L)
            runCurrent()
            assertTrue("only the first attempt raises the notice", viewModel.events.replayCache.isEmpty())
            assertTrue(player.retryAfterErrorCalls.all { it.first == 0L })
        }
    }

    private fun sampleSidecarCues(): SidecarCues =
        SidecarCues.of(listOf(androidx.media3.extractor.text.CuesWithTiming(listOf(androidx.media3.common.text.Cue.Builder().setText("hi").build()), 0L, 1_000_000L)))!!

    // -- docs/18 §2.1 request ownership: gated, both completion orders --------

    private fun assertNoEvent(viewModel: PlaybackViewModel) = assertNull(viewModel.events.replayCache.firstOrNull())

    @Test
    fun `an initial prepare overtaken by a replacement never publishes, in either order and outcome`() =
        runTest(timeout = TEST_TIMEOUT) {
            val oldOutcomes = listOf(
                Result.success(samplePlan(itemId = "item-1", playSessionId = "old")),
                CoreException.StalePlaybackSession().asFailure(),
                CoreException.Api("negotiation failed").asFailure(),
                CoreException.Unauthorized(account = null).asFailure(),
            )
            for (oldFirst in listOf(true, false)) {
                for (old in oldOutcomes) {
                    val replacement = samplePlan(itemId = "item-2", itemName = "Replacement", playSessionId = "new")
                    val gateway = FakeCoreGateway(
                        preparePlaybackResultsByItemId = mapOf("item-1" to old, "item-2" to Result.success(replacement)),
                    )
                    val oldGate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates["item-1"] = it }
                    val newGate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates["item-2"] = it }
                    val player = FakePlaybackPlayer()
                    val viewModel = buildViewModel(gateway, player, "item-1")
                    withSession(viewModel) {
                        viewModel.replaceItem("item-2")
                        if (oldFirst) {
                            oldGate.complete(Unit)
                            runCurrent()
                            newGate.complete(Unit)
                        } else {
                            newGate.complete(Unit)
                            runCurrent()
                            oldGate.complete(Unit)
                        }
                        runCurrent()

                        val case = "oldFirst=$oldFirst old=$old"
                        assertEquals(case, listOf(replacement), player.loadedPlans)
                        assertNoEvent(viewModel)
                        if (old.isSuccess) assertEquals(case, listOf("old"), gateway.abandonPlaybackSessionIds)
                    }
                }
            }
        }

    @Test
    fun `a stop while the first prepare negotiates retires it and abandons its plan`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val gate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates[plan.itemId] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.stopPlaybackOnce()
            gate.complete(Unit)
            runCurrent()

            assertTrue(player.loadedPlans.isEmpty())
            assertEquals(listOf(plan.playSessionId), gateway.abandonPlaybackSessionIds)
            assertNoEvent(viewModel)
        }
    }

    /** docs/18 §2.1: the stop names the session the fallback replaced, which the core ends the
     * fallback through if it installed; abandoning it as well could race ahead of that stop. */
    @Test
    fun `a stop while a fallback negotiates never loads the fallback and leaves its ending to the stop`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true)
        val fallback = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "fallback")
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallback),
        ).apply { prepareTranscodeFallbackGate = gate }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            viewModel.stopPlaybackOnce()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(plan), player.loadedPlans)
            assertEquals(listOf(plan.playSessionId), gateway.stopPlaybackSessionIds)
            assertTrue("no abandon races the stop", gateway.abandonPlaybackSessionIds.isEmpty())
            assertNoEvent(viewModel)
        }
    }

    @Test
    fun `a playback error after stop never starts a fallback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(samplePlan(playMethod = PlayMethodFfi.TRANSCODE)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.stopPlaybackOnce()
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()

            assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
            assertNoEvent(viewModel)
        }
    }

    @Test
    fun `a playback error during a transition never starts a fallback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
        val plan2 = samplePlan(itemId = "item-2", playSessionId = "session-2")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(plan2)),
            prepareTranscodeFallbackResult = Result.success(samplePlan(playMethod = PlayMethodFfi.TRANSCODE)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            val gate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates["item-2"] = it }
            viewModel.replaceItem("item-2")
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            gate.complete(Unit)
            runCurrent()

            assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
            assertEquals(listOf(plan1, plan2), player.loadedPlans)
        }
    }

    @Test
    fun `a transition during a held fallback plays the new item in either completion order`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (fallbackFirst in listOf(true, false)) {
                val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
                val plan2 = samplePlan(itemId = "item-2", itemName = "Replacement", playSessionId = "session-2")
                val fallback = samplePlan(itemId = "item-1", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "fallback")
                val fallbackGate = CompletableDeferred<Unit>()
                val gateway = FakeCoreGateway(
                    preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(plan2)),
                    prepareTranscodeFallbackResult = Result.success(fallback),
                ).apply { prepareTranscodeFallbackGate = fallbackGate }
                val player = FakePlaybackPlayer()
                val viewModel = buildViewModel(gateway, player, plan1.itemId)
                withSession(viewModel) {
                    player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                    val newGate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates["item-2"] = it }
                    viewModel.replaceItem("item-2")
                    assertEquals("the stop names the old session before anything else runs", listOf(plan1.playSessionId), gateway.stopPlaybackSessionIds)
                    if (fallbackFirst) {
                        fallbackGate.complete(Unit)
                        runCurrent()
                        newGate.complete(Unit)
                    } else {
                        newGate.complete(Unit)
                        runCurrent()
                        fallbackGate.complete(Unit)
                    }
                    runCurrent()

                    assertEquals("fallbackFirst=$fallbackFirst", listOf(plan1, plan2), player.loadedPlans)
                    // The core ends an installed fallback through this stop (docs/18 §2.1), so a fallback
                    // landing after it is never abandoned on top of it.
                    if (fallbackFirst) assertFalse("fallback" in gateway.abandonPlaybackSessionIds)
                    assertNoEvent(viewModel)
                }
            }
        }

    /** docs/18 §2.1: a transition's stop, once captured, reaches the core even if the view model is
     * cleared first; it is what ends a fallback landing behind it. */
    @Test
    fun `a transition stop queued when the player is torn down still reaches the core`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
        val fallback = samplePlan(itemId = "item-1", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "fallback")
        val fallbackGate = CompletableDeferred<Unit>()
        val stopGate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(samplePlan(itemId = "item-2", playSessionId = "session-2"))),
            prepareTranscodeFallbackResult = Result.success(fallback),
        ).apply { prepareTranscodeFallbackGate = fallbackGate }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            assertEquals("the fallback is negotiating", 1, gateway.prepareTranscodeFallbackCalls.size)
            gateway.stopPlaybackGate = stopGate
            viewModel.replaceItem("item-2")
            assertEquals("the transition's stop is on its way to the core", 1, gateway.stopPlaybackEntered)
            ViewModelStore().apply { put("playback", viewModel) }.clear()
            stopGate.complete(Unit)
            runCurrent()
            fallbackGate.complete(Unit)
            runCurrent()

            assertEquals(listOf(plan1.playSessionId), gateway.stopPlaybackSessionIds)
            assertFalse("a torn-down player starts nothing", "item-2" in gateway.preparePlaybackCalls)
            assertFalse("the stop ends the landed fallback in the core", "fallback" in gateway.abandonPlaybackSessionIds)
        }
    }

    @Test
    fun `a plan landing during an account call is judged at once by the core's account`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (accountChanged in listOf(false, true)) {
                val plan = samplePlan()
                val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
                val gate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates[plan.itemId] = it }
                val player = FakePlaybackPlayer()
                val viewModel = buildViewModel(gateway, player, plan.itemId)
                withSession(viewModel) {
                    // A sign-in on another screen is still running; the core may already have reset.
                    if (accountChanged) gateway.accountEpoch = 1uL
                    gate.complete(Unit)
                    runCurrent()

                    if (accountChanged) {
                        assertTrue(player.loadedPlans.isEmpty())
                        assertEquals(listOf(plan.playSessionId), gateway.abandonPlaybackSessionIds)
                        assertEquals(PlaybackEvent.Finish, viewModel.events.replayCache.single())
                    } else {
                        assertEquals(listOf(plan), player.loadedPlans)
                        assertNoEvent(viewModel)
                    }
                }
            }
        }

    @Test
    fun `steady playback survives an account call that leaves the epoch alone`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            gateway.accountEpoch = gateway.accountEpoch // a sign-in attempt that fails re-reports the same epoch
            runCurrent()

            assertEquals(listOf(plan), player.loadedPlans)
            assertTrue(gateway.abandonPlaybackSessionIds.isEmpty())
            assertNoEvent(viewModel)
        }
    }

    @Test
    fun `an account change closes live playback`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            gateway.accountEpoch = 1uL
            runCurrent()

            assertEquals(PlaybackEvent.Finish, viewModel.events.replayCache.single())
            assertEquals(listOf(plan.playSessionId), gateway.abandonPlaybackSessionIds)
            assertTrue(gateway.stopPlaybackCalls.isEmpty())
        }
    }

    @Test
    fun `a switch keeps playback on its own account, next episode included`() = runTest(timeout = TEST_TIMEOUT) {
        val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(episodePlan("ep-1")),
                "ep-2" to Result.success(episodePlan("ep-2", itemName = "Episode Two")),
                "other-1" to Result.success(samplePlan(itemId = "other-1", playSessionId = "session-other")),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to nextCard),
        )
        val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
        val viewModel = buildViewModel(gateway, player, "ep-1")
        withSession(viewModel) {
            advanceOneTick()
            // The core kept this playback on its account (epoch 0) across the switch to epoch 1.
            gateway.setEpochs(epoch = 1uL, parked = 0uL)
            runCurrent()

            assertNoEvent(viewModel)
            assertTrue(gateway.abandonPlaybackSessionIds.isEmpty())
            viewModel.playNext()
            runCurrent()
            assertEquals(listOf(0uL, 0uL), gateway.playbackRequests.map { it.accountEpoch })
            assertTrue(gateway.accountBoundCalls.isNotEmpty())
            assertTrue("${gateway.accountBoundCalls}", gateway.accountBoundCalls.all { it.second == 0uL })

            // A new Play from the account now browsed starts on it.
            viewModel.replaceItem("other-1")
            runCurrent()
            assertEquals(1uL, gateway.playbackRequests.last().accountEpoch)
            assertEquals(1uL, viewModel.accountEpoch)
        }
    }

    /** docs/18 §2.1: removing the parked playback's account clears its epoch without moving the
     * current one; the player closes all the same.
     */
    @Test
    fun `removing the parked account closes playback kept across a switch`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val viewModel = buildViewModel(gateway, FakePlaybackPlayer(), plan.itemId)
        withSession(viewModel) {
            gateway.setEpochs(epoch = 1uL, parked = 0uL)
            runCurrent()
            assertNoEvent(viewModel)

            gateway.parkedEpoch = null
            runCurrent()

            assertEquals(PlaybackEvent.Finish, viewModel.events.replayCache.single())
        }
    }

    @Test
    fun `only the owning fallback asks for re-authorization`() = runTest(timeout = TEST_TIMEOUT) {
        val rejected = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")
        for (owned in listOf(true, false)) {
            val plan = samplePlan(transcodeFallbackAllowed = true)
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "item-1" to Result.success(plan),
                    "item-2" to Result.success(samplePlan(itemId = "item-2", playSessionId = "session-2")),
                ),
                prepareTranscodeFallbackResult = CoreException.Unauthorized(account = rejected).asFailure(),
            ).apply { prepareTranscodeFallbackGate = gate }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                if (!owned) viewModel.replaceItem("item-2")
                gate.complete(Unit)
                runCurrent()

                val events = viewModel.events.replayCache
                if (owned) {
                    assertEquals(PlaybackEvent.ReauthorizationRequired(rejected), events.single())
                } else {
                    assertTrue("owned=false: $events", events.isEmpty())
                }
            }
        }
    }
    // -- docs/18 §3.1 reload reapplication: a same-item fallback restores the choice ------

    private fun format(mime: String, lang: String?, label: String? = null, forced: Boolean = false): Format =
        Format.Builder().setSampleMimeType(mime).setLanguage(lang).setLabel(label)
            .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0).build()

    /** One group per (format, selected) pair, in order. */
    private fun tracksOf(vararg tracks: Pair<Format, Boolean>): Tracks = Tracks(
        tracks.map { (format, selected) -> Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected)) },
    )

    private val enAudio = format("audio/mp4a-latm", "eng", "English")
    private val jaAudio = format("audio/mp4a-latm", "jpn")
    private val enText = format("text/vtt", "eng", "English")

    /** A same-source fallback, triggered after [before] was announced and [pick] ran; returns the
     * player with the fallback plan loaded and nothing announced yet.
     */
    private suspend fun TestScope.reloadAfter(
        before: Tracks,
        resolveGate: CompletableDeferred<Unit>? = null,
        autoDecision: TrackDecisionFfi? = null,
        pick: (PlaybackViewModel) -> Unit = {},
        fallbackSubtitles: List<EmbeddedSubtitleFfi> = emptyList(),
        body: (FakePlaybackPlayer, FakeCoreGateway, PlaybackViewModel) -> Unit,
    ) {
        val plan = samplePlan(transcodeFallbackAllowed = true)
        val fallback = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "fallback", embeddedSubtitles = fallbackSubtitles)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(fallback),
        ).apply {
            resolveTracksGate = resolveGate
            autoDecision?.let { resolveTracksResult = it }
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(before)
            runCurrent()
            pick(viewModel)
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()
            assertEquals(fallback, player.loadedPlan)
            body(player, gateway, viewModel)
        }
    }

    @Test
    fun `Off survives a fallback reload, before and after the new tracks`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, enText to true)
        reloadAfter(before, pick = { it.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID) }) { player, gateway, _ ->
            assertTrue("off at once, no track needed", player.textDisabled)
            player.fireTracksChanged(tracksOf(enText to false, enAudio to true))
            assertTrue(player.textDisabled)
            assertEquals("no automatic resolution over a manual choice", 1, gateway.resolveTracksCalls.size)
        }
    }

    @Test
    fun `an embedded pick is found again on reordered tracks with a rewritten title`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, jaAudio to false, enText to false)
        reloadAfter(before, pick = { it.chooseSubtitle(TrackMapping.toId(2, 0)) }) { player, _, _ ->
            assertTrue("text stays off until matched", player.textDisabled)
            val after = tracksOf(format("text/vtt", "en", "English (SRT)") to false, enAudio to true)
            player.fireTracksChanged(after)

            assertFalse(player.textDisabled)
            assertEquals(0 to 0, player.selectedTextOverride(after))
        }
    }

    @Test
    fun `an ambiguous match leaves subtitles off rather than guessing`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, format("text/vtt", "eng", "Full") to false)
        reloadAfter(before, pick = { it.chooseSubtitle(TrackMapping.toId(1, 0)) }) { player, _, _ ->
            val after = tracksOf(enAudio to true, format("text/vtt", "en", "Signs") to false, format("text/vtt", "en", "SDH") to false)
            player.fireTracksChanged(after)

            assertTrue(player.textDisabled)
            assertNull(player.selectedTextOverride(after))
        }
    }

    @Test
    fun `a pick survives empty and audio-only announcements and the audio choice comes back too`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to false, jaAudio to true, enText to false)
        reloadAfter(before, pick = { it.chooseAudio(TrackMapping.toId(1, 0)); it.chooseSubtitle(TrackMapping.toId(2, 0)) }) { player, _, _ ->
            player.fireTracksChanged(Tracks.EMPTY)
            val audioOnly = tracksOf(enAudio to true, jaAudio to false)
            player.fireTracksChanged(audioOnly)
            assertEquals("audio restored by language", 1 to 0, player.selectedAudioOverride(audioOnly))
            assertTrue("text still waiting, still off", player.textDisabled)

            val full = tracksOf(enAudio to true, jaAudio to false, enText to false)
            player.fireTracksChanged(full)
            assertFalse(player.textDisabled)
            assertEquals(2 to 0, player.selectedTextOverride(full))
            assertEquals("the audio choice is untouched by the text announcement", 1 to 0, player.selectedAudioOverride(full))

            val applied = player.applyTrackDecisionCalls.size
            player.fireTracksChanged(full)
            assertEquals("idempotent under its own callbacks", applied, player.applyTrackDecisionCalls.size)
        }
    }

    @Test
    fun `an audio pick while the subtitle is still being restored keeps the subtitle restoration`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, jaAudio to false, enText to false)
        reloadAfter(before, pick = { it.chooseSubtitle(TrackMapping.toId(2, 0)) }) { player, _, viewModel ->
            player.fireTracksChanged(tracksOf(enAudio to true, jaAudio to false))
            viewModel.chooseAudio(TrackMapping.toId(1, 0))

            val full = tracksOf(enAudio to false, jaAudio to true, enText to false)
            player.fireTracksChanged(full)
            assertFalse("the subtitle came back", player.textDisabled)
            assertEquals(2 to 0, player.selectedTextOverride(full))
        }
    }

    @Test
    fun `a subtitle pick while the audio is still being restored keeps the audio restoration`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to false, jaAudio to true, enText to false)
        reloadAfter(before, pick = { it.chooseAudio(TrackMapping.toId(1, 0)) }) { player, _, viewModel ->
            player.fireTracksChanged(tracksOf(enText to false))
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)

            val full = tracksOf(enText to false, enAudio to true, jaAudio to false)
            player.fireTracksChanged(full)
            assertEquals("the audio came back", 2 to 0, player.selectedAudioOverride(full))
            assertTrue(player.textDisabled)
        }
    }

    @Test
    fun `a burn-in pick while the audio is still being restored keeps the audio restoration`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to false, jaAudio to true)
        reloadAfter(before, pick = { it.chooseAudio(TrackMapping.toId(1, 0)) }, fallbackSubtitles = listOf(assStream)) { player, gateway, viewModel ->
            gateway.prepareTranscodeFallbackGatesByCall[1] = CompletableDeferred() // the burn-in renegotiation stays pending
            val french = format("text/vtt", "fre", "French") // delivered, so it hides no burn-in row
            player.fireTracksChanged(tracksOf(french to false))
            viewModel.chooseSubtitle(streamRowId(2))
            assertEquals("the pick renegotiates", 2, gateway.prepareTranscodeFallbackCalls.size)

            val full = tracksOf(french to false, enAudio to true, jaAudio to false)
            player.fireTracksChanged(full)
            assertEquals("the audio came back", 2 to 0, player.selectedAudioOverride(full))
        }
    }

    @Test
    fun `audio announced after the subtitle was restored leaves the subtitle on`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to false, jaAudio to true, enText to false)
        reloadAfter(before, pick = { it.chooseAudio(TrackMapping.toId(1, 0)); it.chooseSubtitle(TrackMapping.toId(2, 0)) }) { player, _, _ ->
            val textOnly = tracksOf(enText to false)
            player.fireTracksChanged(textOnly)
            assertFalse(player.textDisabled)

            val full = tracksOf(enText to false, enAudio to true, jaAudio to false)
            player.fireTracksChanged(full)
            assertFalse("the subtitle stays on", player.textDisabled)
            assertEquals(2 to 0, player.selectedAudioOverride(full))
        }
    }

    @Test
    fun `an automatic choice that already applied is kept as selected`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, enText to false)
        val auto = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = TrackMapping.toId(1, 0))
        reloadAfter(before, autoDecision = auto) { player, gateway, _ ->
            val after = tracksOf(enAudio to true, enText to false)
            player.fireTracksChanged(after)

            assertFalse(player.textDisabled)
            assertEquals(1 to 0, player.selectedTextOverride(after))
            assertEquals("not re-decided", 1, gateway.resolveTracksCalls.size)
        }
    }

    @Test
    fun `an automatic choice still resolving re-resolves for the new load and the old answer is dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val before = tracksOf(enAudio to true, jaAudio to false, enText to false)
            reloadAfter(before, resolveGate = gate) { player, gateway, _ ->
                gateway.resolveTracksResult = TrackDecisionFfi(audioTrackId = TrackMapping.toId(1, 0), subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
                val after = tracksOf(enText to false, enAudio to true, jaAudio to false)
                player.fireTracksChanged(after)
                val appliedBefore = player.applyTrackDecisionCalls.size
                gate.complete(Unit)
                runCurrent()

                assertEquals("resolved once per load", 2, gateway.resolveTracksCalls.size)
                assertEquals("only the new load's answer applies", appliedBefore + 1, player.applyTrackDecisionCalls.size)
                assertEquals(after, player.applyTrackDecisionCalls.last().second)
            }
        }
    // -- docs/18 §3.1 negotiated subtitle choice: never burn in what the viewer turned off ------

    private val assStream = EmbeddedSubtitleFfi(index = 2, language = "eng", title = null, forced = false, default = true, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)

    @Test
    fun `a fallback names no subtitle stream when the viewer chose Off`() = runTest(timeout = TEST_TIMEOUT) {
        val before = tracksOf(enAudio to true, enText to true)
        reloadAfter(before, pick = { it.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID) }) { _, gateway, _ ->
            val call = gateway.prepareTranscodeFallbackCalls.single()
            assertEquals(NO_SUBTITLE_STREAM, call.subtitleStreamIndex)
            assertEquals(FailedTrackFfi.UNKNOWN, call.failed)
        }
    }

    @Test
    fun `a choice changed while the fallback negotiates renegotiates before anything loads`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, embeddedSubtitles = listOf(assStream))
        val landed = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "landed", embeddedSubtitles = listOf(assStream))
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(landed),
        ).apply { prepareTranscodeFallbackGate = gate }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true, enText to false))
            runCurrent()
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            viewModel.chooseSubtitle(TrackMapping.toId(1, 0)) // English, on the old player, mid-negotiation
            gate.complete(Unit)
            runCurrent()

            val calls = gateway.prepareTranscodeFallbackCalls
            assertEquals(listOf(NO_SUBTITLE_STREAM, 2), calls.map { it.subtitleStreamIndex })
            assertEquals("renegotiated from the landed session", "landed", calls[1].playSessionId)
            assertEquals("the copy policy is resent", calls[0].failed, calls[1].failed)
            assertEquals("the first landing never loaded", listOf(plan, landed), player.loadedPlans)
        }
    }

    /** docs/18 §3.1: however often the choice changes mid-negotiation, what finally loads never
     * contradicts it; burned-in pixels can't be turned off after the fact. */
    @Test
    fun `a choice changed during every renegotiation never loads a contradicting burn-in`() = runTest(timeout = TEST_TIMEOUT) {
        val french = EmbeddedSubtitleFfi(index = 3, language = "fre", title = null, forced = false, default = false, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)
        val streams = listOf(assStream, french)
        fun transcode(id: String, burned: Int?) =
            samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = id, embeddedSubtitles = streams, burnedSubtitleIndex = burned)
        val start = transcode("s0", 2)
        val gates = List(4) { CompletableDeferred<Unit>() }
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(start)).apply {
            listOf(transcode("s1", 3), transcode("s2", null), transcode("s3", 2), transcode("s4", null))
                .forEachIndexed { call, plan -> prepareTranscodeFallbackResultsByCall[call] = Result.success(plan) }
            gates.forEachIndexed { call, gate -> prepareTranscodeFallbackGatesByCall[call] = gate }
        }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, start.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()
            viewModel.chooseSubtitle(streamRowId(3))
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            gates[0].complete(Unit)
            runCurrent()
            viewModel.chooseSubtitle(streamRowId(2))
            gates[1].complete(Unit)
            runCurrent()
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            gates[2].complete(Unit)
            runCurrent()
            gates[3].complete(Unit)
            runCurrent()

            assertEquals(listOf(3, NO_SUBTITLE_STREAM, 2, NO_SUBTITLE_STREAM), gateway.prepareTranscodeFallbackCalls.map { it.subtitleStreamIndex })
            assertEquals("only the last landing loads", listOf("s0", "s4"), player.loadedPlans.map { it.playSessionId })
        }
    }

    @Test
    fun `Off over a burned-in subtitle renegotiates without it, and the burned row reads selected`() =
        runTest(timeout = TEST_TIMEOUT) {
            val burnedPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
            val clean = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "clean", embeddedSubtitles = listOf(assStream))
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(burnedPlan),
                prepareTranscodeFallbackResult = Result.success(clean),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, burnedPlan.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(tracksOf(enAudio to true))
                runCurrent()
                viewModel.openTrackPicker()
                val rows = viewModel.state.value.trackPicker!!.subtitleTracks
                assertEquals(listOf(false, true), rows.map { it.selected })
                assertEquals(streamRowId(2), rows[1].id)

                viewModel.chooseSubtitle(streamRowId(2))
                assertTrue("picking the burned row changes nothing", gateway.prepareTranscodeFallbackCalls.isEmpty())

                viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
                runCurrent()

                val call = gateway.prepareTranscodeFallbackCalls.single()
                assertEquals(NO_SUBTITLE_STREAM, call.subtitleStreamIndex)
                assertNull("no fallback happened, so no copy is forbidden", call.failed)
                assertEquals(listOf(burnedPlan, clean), player.loadedPlans)
            }
        }

    @Test
    fun `an automatic Off over a burned-in subtitle renegotiates, an automatic default does not`() =
        runTest(timeout = TEST_TIMEOUT) {
            val off = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)
            val leave = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
            for ((decision, renegotiates) in listOf(off to true, leave to false)) {
                val burnedPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
                val gateway = FakeCoreGateway(
                    preparePlaybackResult = Result.success(burnedPlan),
                    prepareTranscodeFallbackResult = Result.success(samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "clean")),
                ).apply { resolveTracksResult = decision }
                // A real suspension, as in production, so the resolution's own job is active when it applies.
                val resolved = CompletableDeferred<Unit>().also { gateway.resolveTracksGate = it }
                val player = FakePlaybackPlayer()
                val viewModel = buildViewModel(gateway, player, burnedPlan.itemId)
                withSession(viewModel) {
                    player.fireTracksChanged(tracksOf(enAudio to true))
                    runCurrent()
                    resolved.complete(Unit)
                    runCurrent()

                    val calls = gateway.prepareTranscodeFallbackCalls
                    if (renegotiates) {
                        assertEquals(NO_SUBTITLE_STREAM, calls.single().subtitleStreamIndex)
                    } else {
                        assertTrue("the server's default stands: $calls", calls.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `automatic resolution sees burn-in-only streams and burns in the one it picks`() =
        runTest(timeout = TEST_TIMEOUT) {
            val forced = EmbeddedSubtitleFfi(index = 3, language = "eng", title = null, forced = true, default = false, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)
            // (burned now, renegotiation expected): keeping a forced stream already burned in is a no-op.
            for ((burned, renegotiates) in listOf(3 to false, null to true)) {
                val plan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(forced), burnedSubtitleIndex = burned)
                val gateway = FakeCoreGateway(
                    preparePlaybackResult = Result.success(plan),
                    prepareTranscodeFallbackResult = Result.success(samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "forced", embeddedSubtitles = listOf(forced), burnedSubtitleIndex = 3)),
                ).apply {
                    resolveTracksResult = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = streamRowId(3))
                }
                val resolved = CompletableDeferred<Unit>().also { gateway.resolveTracksGate = it }
                val player = FakePlaybackPlayer()
                val viewModel = buildViewModel(gateway, player, plan.itemId)
                withSession(viewModel) {
                    player.fireTracksChanged(tracksOf(enAudio to true))
                    runCurrent()
                    resolved.complete(Unit)
                    runCurrent()

                    val offered = gateway.resolveTracksCalls.single().tracks.single { it.kind == TrackKindFfi.SUBTITLE }
                    assertEquals(streamRowId(3), offered.id)
                    assertTrue(offered.isForced)
                    assertEquals(subtitlesOff, player.applyTrackDecisionCalls.last().first)
                    val calls = gateway.prepareTranscodeFallbackCalls
                    if (renegotiates) {
                        assertEquals(3, calls.single().subtitleStreamIndex)
                    } else {
                        assertTrue("the forced stream is already burned in: $calls", calls.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a default sidecar never shows over a burned-in subtitle`() = runTest(timeout = TEST_TIMEOUT) {
        val sidecar = sampleSidecar().copy(isDefault = true)
        val plan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2, externalSubtitles = listOf(sidecar))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), externalSubtitleText = mapOf(5 to "ok"))
        val resolved = CompletableDeferred<Unit>().also { gateway.resolveTracksGate = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()
            resolved.complete(Unit)
            runCurrent()

            assertTrue("the default sidecar stays unloaded", gateway.fetchExternalSubtitleCalls.isEmpty())
            assertNull(viewModel.state.value.sidecarCues)
            assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
        }
    }

    @Test
    fun `the accent dot reads a burned-in subtitle like any other`() = runTest(timeout = TEST_TIMEOUT) {
        val french = EmbeddedSubtitleFfi(index = 3, language = "fre", title = null, forced = false, default = false, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)
        // (pick, what the landed plan burns, dot once landed): a landing that burns something other
        // than what was asked is what the dot follows.
        val cases = listOf(
            Triple(null, 2, false),
            Triple(TRACK_PICKER_SUBTITLE_OFF_ID, null, true),
            Triple(streamRowId(3), 3, true),
            Triple(TRACK_PICKER_SUBTITLE_OFF_ID, 2, false),
            Triple(streamRowId(3), 2, false),
        )
        for ((pick, burnedAfter, dotAfter) in cases) {
            val streams = listOf(assStream, french)
            val burnedPlan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = streams, burnedSubtitleIndex = 2)
            val landed = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "landed", embeddedSubtitles = streams, burnedSubtitleIndex = burnedAfter)
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(burnedPlan),
                prepareTranscodeFallbackResult = Result.success(landed),
            ).apply { prepareTranscodeFallbackGate = gate }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, burnedPlan.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(tracksOf(enAudio to true))
                runCurrent()
                assertFalse("the server's default is burned in", viewModel.state.value.nonDefaultTrackActive)
                if (pick != null) {
                    viewModel.chooseSubtitle(pick)
                    assertTrue("$pick, while it negotiates", viewModel.state.value.nonDefaultTrackActive)
                    gate.complete(Unit)
                    runCurrent()
                    player.fireTracksChanged(tracksOf(enAudio to true))
                    runCurrent()
                    assertEquals("$pick landing on $burnedAfter", dotAfter, viewModel.state.value.nonDefaultTrackActive)
                }
            }
        }
    }

    @Test
    fun `a stream only burn-in can show is offered while transcoding and picking it renegotiates`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clean = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream))
            val burned = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "burned", embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
            val gateway = FakeCoreGateway(
                preparePlaybackResult = Result.success(clean),
                prepareTranscodeFallbackResult = Result.success(burned),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, clean.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(tracksOf(enAudio to true))
                runCurrent()
                viewModel.openTrackPicker()
                val rows = viewModel.state.value.trackPicker!!.subtitleTracks
                assertEquals(listOf(TRACK_PICKER_SUBTITLE_OFF_ID, streamRowId(2)), rows.map { it.id })
                assertEquals(listOf(true, false), rows.map { it.selected })

                viewModel.chooseSubtitle(streamRowId(2))
                runCurrent()

                assertEquals(2, gateway.prepareTranscodeFallbackCalls.single().subtitleStreamIndex)
                assertEquals(listOf(clean, burned), player.loadedPlans)
                assertTrue("text stays off on the player; the subtitle is in the video", player.textDisabled)
            }
        }

    @Test
    fun `a delivered text track is not offered twice and picking it never renegotiates`() = runTest(timeout = TEST_TIMEOUT) {
        val srt = EmbeddedSubtitleFfi(index = 3, language = "eng", title = "English", forced = false, default = false, codec = "srt", displayTitle = "English", delivery = SubtitleDeliveryFfi.HLS)
        val plan = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(srt))
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true, enText to false))
            runCurrent()
            viewModel.openTrackPicker()
            assertEquals(2, viewModel.state.value.trackPicker!!.subtitleTracks.size)

            viewModel.chooseSubtitle(TrackMapping.toId(1, 0))
            runCurrent()

            assertTrue(gateway.prepareTranscodeFallbackCalls.isEmpty())
        }
    }

    /** docs/18 §3.1: text a transcode's manifest lacks is a row whose pick renegotiates naming it;
     * the landed manifest delivers it to the player, nothing is burned in. */
    @Test
    fun `text the manifest lacks is picked by renegotiating and lands as a player track`() = runTest(timeout = TEST_TIMEOUT) {
        // Untitled like the ASS stream, so only the row's own index tells them apart.
        val srt = EmbeddedSubtitleFfi(index = 3, language = "eng", title = null, forced = false, default = false, codec = "subrip", displayTitle = "English - SUBRIP", delivery = SubtitleDeliveryFfi.HLS)
        val streams = listOf(assStream, srt)
        val bare = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = streams)
        val withText = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "text", embeddedSubtitles = streams)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(bare),
            prepareTranscodeFallbackResult = Result.success(withText),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, bare.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()
            viewModel.openTrackPicker()
            assertEquals(listOf(TRACK_PICKER_SUBTITLE_OFF_ID, streamRowId(2), streamRowId(3)), viewModel.state.value.trackPicker!!.subtitleTracks.map { it.id })

            viewModel.chooseSubtitle(streamRowId(3))
            runCurrent()
            assertEquals(3, gateway.prepareTranscodeFallbackCalls.single().subtitleStreamIndex)
            assertEquals(listOf(bare, withText), player.loadedPlans)

            // Every subtitle is in the landed manifest; the ASS copy is not offered as text.
            val assCopy = format("text/vtt", "eng", "English - ASS")
            val srtRendition = format("text/vtt", "eng", "English - SUBRIP")
            player.fireTracksChanged(tracksOf(enAudio to true, assCopy to false, srtRendition to false))
            runCurrent()
            assertEquals("the chosen stream shows as player text", TrackMapping.toId(2, 0), player.applyTrackDecisionCalls.last().first.subtitleTrackId)
            viewModel.openTrackPicker()
            assertEquals(listOf(TRACK_PICKER_SUBTITLE_OFF_ID, streamRowId(2), TrackMapping.toId(2, 0)), viewModel.state.value.trackPicker!!.subtitleTracks.map { it.id })
        }
    }

    // -- review fixes: a renegotiation in flight owns the session until it lands -------------

    @Test
    fun `a pick during a renegotiation waits for it, and stop names the session it retired`() = runTest(timeout = TEST_TIMEOUT) {
        val burned = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
        val clean = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "clean", embeddedSubtitles = listOf(assStream))
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(burned),
            prepareTranscodeFallbackResult = Result.success(clean),
        )
        val first = CompletableDeferred<Unit>().also { gateway.prepareTranscodeFallbackGatesByCall[0] = it }
        CompletableDeferred<Unit>().also { gateway.prepareTranscodeFallbackGatesByCall[1] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, burned.itemId)
        try {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()

            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            viewModel.chooseSubtitle(streamRowId(2)) // changed mind mid-negotiation
            player.fireError(PlaybackException("old stream died", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            runCurrent()
            assertEquals("no second negotiation while one is in flight", 1, gateway.prepareTranscodeFallbackCalls.size)
            assertNoEvent(viewModel)

            first.complete(Unit)
            runCurrent()
            val calls = gateway.prepareTranscodeFallbackCalls
            assertEquals(listOf(NO_SUBTITLE_STREAM, 2), calls.map { it.subtitleStreamIndex })
            assertEquals("the landed session is renegotiated", "clean", calls[1].playSessionId)

            viewModel.stopPlaybackOnce()
            assertEquals("stop names the session the chained negotiation retires", listOf("clean"), gateway.stopPlaybackSessionIds)
            assertEquals("nothing loaded mid-chain", listOf(burned), player.loadedPlans)
        } finally {
            viewModel.stopPlaybackOnce()
        }
    }

    @Test
    fun `a second pick away from the burned stream waits for the renegotiation in flight`() = runTest(timeout = TEST_TIMEOUT) {
        val signs = EmbeddedSubtitleFfi(index = 3, language = "eng", title = "Signs", forced = true, default = false, codec = "ass", delivery = SubtitleDeliveryFfi.ENCODE)
        val burned = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream, signs), burnedSubtitleIndex = 2)
        val landed = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "signs", embeddedSubtitles = listOf(assStream, signs), burnedSubtitleIndex = 3)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(burned),
            prepareTranscodeFallbackResult = Result.success(landed),
        )
        val first = CompletableDeferred<Unit>().also { gateway.prepareTranscodeFallbackGatesByCall[0] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, burned.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()

            viewModel.chooseSubtitle(streamRowId(3))
            viewModel.openTrackPicker()
            assertEquals(
                "mid-negotiation the asked-for stream reads selected",
                streamRowId(3),
                viewModel.state.value.trackPicker!!.subtitleTracks.single { it.selected }.id,
            )
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            runCurrent()
            assertEquals("the in-flight one re-checks on landing", listOf(3), gateway.prepareTranscodeFallbackCalls.map { it.subtitleStreamIndex })

            first.complete(Unit)
            runCurrent()
            assertEquals(listOf(3, NO_SUBTITLE_STREAM), gateway.prepareTranscodeFallbackCalls.map { it.subtitleStreamIndex })
            assertEquals("renegotiated from the landed session", "signs", gateway.prepareTranscodeFallbackCalls[1].playSessionId)
        }
    }

    @Test
    fun `the first request waits for the launch restore and carries its epoch`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val restored = CompletableDeferred<Unit>().also { gateway.accountRestored = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            runCurrent()
            assertTrue("nothing prepared before the restore", gateway.preparePlaybackCalls.isEmpty())

            gateway.accountEpoch = 1uL
            restored.complete(Unit)
            runCurrent()

            assertEquals(1uL, gateway.playbackRequests.single().accountEpoch)
            assertEquals(listOf(plan), player.loadedPlans)
        }
    }

    @Test
    fun `a negotiation still running when the next item starts is not inherited by it`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = samplePlan(itemId = "item-1", transcodeFallbackAllowed = true)
        val plan2 = samplePlan(itemId = "item-2", playSessionId = "session-2")
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("item-1" to Result.success(plan1), "item-2" to Result.success(plan2)),
            prepareTranscodeFallbackResult = Result.success(samplePlan(itemId = "item-1", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "fallback")),
        ).apply { prepareTranscodeFallbackGate = gate }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            viewModel.replaceItem("item-2")
            runCurrent()
            assertEquals(listOf(plan1, plan2), player.loadedPlans)

            player.fireError(PlaybackException("item 2 fails", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()
            assertTrue("item 2's own error is handled", viewModel.events.replayCache.single() is PlaybackEvent.FinishWithMessage)
            assertEquals("item 1 was stopped by the session its negotiation retired", listOf("session-1"), gateway.stopPlaybackSessionIds)
            assertEquals("item 2's failure ends item 2", "session-2", gateway.abandonPlaybackSessionIds.last())
            gate.complete(Unit)
            runCurrent()
        }
    }

    // -- docs/17 §2, docs/18 §2.1: everything a session launches belongs to that session ------

    /** docs/17 §2: the Undo toast belongs to the session whose segment was skipped. */
    @Test
    fun `an Undo offered by one episode's credits skip seeks nothing once autoplay loaded the next episode`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = episodePlan("ep-1")
            val plan2 = episodePlan("ep-2", itemName = "Episode Two")
            val nextCard = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val credits = sampleSegment(MediaSegmentKind.OUTRO, 160_000_000L, 190_000_000L)
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan1), "ep-2" to Result.success(plan2)),
                nextEpisodeByItemId = mapOf("ep-1" to nextCard),
                mediaSegmentsByItemId = mapOf("ep-1" to listOf(credits)),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                player.positionTicks = 150_000_000L
                advanceOneTick()
                viewModel.skipSegment(credits)
                assertNotNull("the credits skip offers its Undo", viewModel.state.value.skipUndo)

                viewModel.playNextEpisode() // autoplay: the next episode reaches READY inside the 5s window
                runCurrent()
                assertEquals(plan2, player.loadedPlan)
                val seeksOnceNextEpisodeLoaded = player.seekCalls.size

                viewModel.undoSkip() // Select with the OSD hidden
                runCurrent()

                assertEquals(
                    "an Undo from the old session must not seek the new item",
                    seeksOnceNextEpisodeLoaded,
                    player.seekCalls.size,
                )
            }
        }

    @Test
    fun `an Undo offered by a credits skip still seeks back within the same session`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = episodePlan("ep-1")
        val credits = sampleSegment(MediaSegmentKind.OUTRO, 160_000_000L, 190_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
            mediaSegmentsByItemId = mapOf("ep-1" to listOf(credits)),
        )
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.positionTicks = 150_000_000L
            advanceOneTick()
            viewModel.skipSegment(credits)
            viewModel.undoSkip()

            assertEquals(listOf(4_000L, -4_000L), player.seekCalls)
            assertNull(viewModel.state.value.skipUndo)
        }
    }

    /** docs/18 §2.1: a tick parked in its report when replaceItem starts a newer session lands on nothing. */
    @Test
    fun `a position report held across replaceItem evaluates nothing against the new item`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan3 = episodePlan("ep-3", itemName = "Episode Three")
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 160_000_000L, 190_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan1), "ep-3" to Result.success(plan3)),
            nextEpisodeByItemId = mapOf(
                "ep-1" to testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2),
                "ep-3" to testCard(id = "ep-4", itemType = "Episode", name = "Episode Four", indexNumber = 4),
            ),
            mediaSegmentsByItemId = mapOf("ep-1" to listOf(commercial), "ep-3" to listOf(commercial)),
            settings = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP),
        )
        val held = CompletableDeferred<Unit>().also { gateway.reportPositionGate = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            player.isPlaying = true
            player.positionTicks = 170_000_000L
            advanceOneTick()
            assertEquals("the tick is parked in the position report", 1, gateway.reportPositionEntered)

            gateway.reportPositionGate = null
            viewModel.replaceItem("ep-3")
            runCurrent()
            assertEquals(plan3, player.loadedPlan)
            held.complete(Unit)
            runCurrent()

            val effects = buildList {
                if (viewModel.state.value.nextUp != null) add("next-up card")
                if (gateway.noteEpisodeFinishedCalls.isNotEmpty()) add("still-watching decision")
                if (gateway.preloadPlaybackCalls.isNotEmpty()) add("next-episode preload")
                if (player.seekCalls.isNotEmpty()) add("auto-skip seek")
            }
            assertEquals("a replaced session evaluates no tick effects", emptyList<String>(), effects)
        }
    }

    /** docs/18 §2.1: a late answer is the session's own or nothing, even for the same item id. */
    @Test
    fun `a first session's late neighbours lookup publishes nothing into the replacement of the same item`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = episodePlan("ep-1")
            val freshNext = testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)
            val stalePrevious = testCard(id = "stale-prev", itemType = "Episode", name = "Stale Previous", indexNumber = 0)
            val staleNext = testCard(id = "stale-next", itemType = "Episode", name = "Stale Next", indexNumber = 9)
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "ep-1" to Result.success(plan),
                    "ep-2" to Result.success(episodePlan("ep-2", itemName = "Episode Two")),
                ),
                nextEpisodeByItemId = mapOf("ep-1" to freshNext),
            )
            val first = CompletableDeferred<EpisodeNeighbors>().also { gateway.episodeNeighborsGates.add(it) }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                assertFalse("the first lookup is parked", viewModel.state.value.hasNextEpisode)

                viewModel.replaceItem("ep-1")
                runCurrent()
                assertTrue("the second session's own lookup answered", viewModel.state.value.hasNextEpisode)
                assertFalse(viewModel.state.value.hasPreviousEpisode)

                first.complete(EpisodeNeighbors(previous = stalePrevious, next = staleNext))
                runCurrent()

                assertFalse("the first session's answer must not add a previous episode", viewModel.state.value.hasPreviousEpisode)
                viewModel.playNextEpisode()
                runCurrent()
                assertEquals("Next episode is the second session's own answer", "ep-2", gateway.preparePlaybackCalls.last())
            }
        }

    @Test
    fun `a first session's late OSD detail publishes nothing into the replacement of the same item`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val freshChapters = listOf(sampleChapter("Fresh Chapter", 0L))
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(plan.itemId to Result.success(plan)),
                itemDetailResultsByItemId = mapOf(
                    plan.itemId to Result.success(sampleItemDetail(container = "fresh-container", chapters = freshChapters)),
                ),
            )
            val first = CompletableDeferred<PlaybackOsdDetail>().also { gateway.playbackOsdDetailGates.add(it) }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireRenderedFirstFrame() // releases the enrichment gate: the first fetch parks on `first`
                assertEquals(listOf(plan.itemId), gateway.getPlaybackOsdDetailCalls)

                viewModel.replaceItem(plan.itemId)
                runCurrent()
                player.fireRenderedFirstFrame()
                assertEquals("fresh-container", viewModel.state.value.playbackStatsDetail?.container)

                first.complete(
                    PlaybackOsdDetail(
                        container = "stale-container",
                        mediaStreams = emptyList(),
                        chapters = listOf(sampleChapter("Stale Chapter", 0L)),
                        sizeBytes = null,
                        path = null,
                        trickplay = null,
                    ),
                )
                runCurrent()

                assertEquals("stats detail stays the second session's", "fresh-container", viewModel.state.value.playbackStatsDetail?.container)
                assertEquals("chapters stay the second session's", freshChapters, viewModel.state.value.chapters)
            }
        }

    @Test
    fun `a first session's late media segments publish nothing into the replacement of the same item`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan = samplePlan()
            val freshSegments = listOf(sampleSegment(MediaSegmentKind.INTRO, 0L, 50_000_000L))
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(plan.itemId to Result.success(plan)),
                mediaSegmentsByItemId = mapOf(plan.itemId to freshSegments),
            )
            val first = CompletableDeferred<List<MediaSegment>>().also { gateway.mediaSegmentsGates.add(it) }
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                viewModel.replaceItem(plan.itemId)
                runCurrent()
                assertEquals(freshSegments, viewModel.state.value.mediaSegments)

                first.complete(listOf(sampleSegment(MediaSegmentKind.OUTRO, 160_000_000L, 190_000_000L)))
                runCurrent()

                assertEquals("segments stay the second session's", freshSegments, viewModel.state.value.mediaSegments)
            }
        }

    /** docs/18 §2.1: a request names the account its session plays on, never one read after a switch. */
    @Test
    fun `a first session's parked enrichment fetches request nothing on the account of a Play elsewhere`() =
        runTest(timeout = TEST_TIMEOUT) {
            val plan1 = samplePlan()
            val plan2 = samplePlan(itemId = "other-1", playSessionId = "session-other")
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(plan1.itemId to Result.success(plan1), "other-1" to Result.success(plan2)),
            )
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan1.itemId)
            withSession(viewModel) {
                advanceOneTick() // t=1.0s: the first session's enrichment gate (1.5s fallback) still holds its fetches

                gateway.setEpochs(epoch = 1uL, parked = 0uL) // switch account; the playback is parked on its own
                runCurrent()
                viewModel.replaceItem("other-1") // Play from the account now browsed
                runCurrent()
                assertEquals(1uL, viewModel.accountEpoch)

                advanceTimeBy(600L) // t=1.6s: only the first session's gate has opened
                runCurrent()

                val enrichment = setOf("getPlaybackOsdDetail", "serverDisplayName")
                assertEquals(
                    "the first session requests nothing under the new account's epoch",
                    emptyList<Pair<String, ULong?>>(),
                    gateway.accountBoundCalls.filter { it.first in enrichment && it.second == 1uL },
                )
            }
        }

    /** docs/17 §2: a Play intent arriving mid-transition is the newest request and wins. */
    @Test
    fun `a Play intent arriving during an autoplay transition is what ends up playing`() = runTest(timeout = TEST_TIMEOUT) {
        val plan1 = episodePlan("ep-1")
        val plan2 = episodePlan("ep-2", itemName = "Episode Two")
        val intentPlan = samplePlan(itemId = "movie-b", itemName = "Movie B", playSessionId = "session-b")
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf(
                "ep-1" to Result.success(plan1),
                "ep-2" to Result.success(plan2),
                "movie-b" to Result.success(intentPlan),
            ),
            nextEpisodeByItemId = mapOf("ep-1" to testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)),
        )
        val autoplayGate = CompletableDeferred<Unit>().also { gateway.preparePlaybackGates["ep-2"] = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan1.itemId)
        withSession(viewModel) {
            viewModel.playNextEpisode() // autoplay: ep-2's prepare is in flight
            runCurrent()
            assertEquals(listOf("ep-1", "ep-2"), gateway.preparePlaybackCalls)

            viewModel.replaceItem("movie-b") // PiP Play intent
            runCurrent()
            autoplayGate.complete(Unit)
            runCurrent()

            assertEquals("the newest request plays", "movie-b", player.loadedPlans.last().itemId)
            assertEquals("Movie B", viewModel.state.value.itemName)
        }
    }

    // -- docs/18 §2.1 / docs/17 §2: one stop funnel, one scope for a negotiation, one surface funnel ----

    private enum class FinalStop { STOP_ONCE, NATURAL_END, STILL_WATCHING_TIMEOUT, FATAL_ERROR }

    @Test
    fun `every final stop lands before a replacement prepares, and names the old session with its position`() = runTest(timeout = TEST_TIMEOUT) {
        val failures = mutableListOf<String>()
        for (entry in FinalStop.values()) {
            try {
            val plan = episodePlan("ep-1")
            val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
            val gateway = FakeCoreGateway(
                preparePlaybackResultsByItemId = mapOf(
                    "ep-1" to Result.success(plan),
                    "other" to Result.success(samplePlan(itemId = "other", playSessionId = "session-other")),
                ),
                nextEpisodeByItemId = mapOf("ep-1" to testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)),
                noteEpisodeFinishedDecisions = ArrayDeque(listOf(StillWatchingDecision.ASK_STILL_WATCHING)),
                settings = settings,
            )
            val player = FakePlaybackPlayer().apply { positionTicks = 170_000_000L }
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                if (entry == FinalStop.STILL_WATCHING_TIMEOUT) {
                    advanceOneTick()
                    assertNotNull("$entry: the card is up", viewModel.state.value.stillWatching)
                }
                val gate = CompletableDeferred<Unit>().also { gateway.stopPlaybackGate = it }
                when (entry) {
                    FinalStop.STOP_ONCE -> viewModel.stopPlaybackOnce()
                    FinalStop.NATURAL_END -> player.firePlaybackStateChanged(Player.STATE_ENDED)
                    FinalStop.STILL_WATCHING_TIMEOUT -> {
                        advanceTimeBy(5_000L)
                        runCurrent()
                    }
                    FinalStop.FATAL_ERROR -> {
                        player.fireRenderedFirstFrame()
                        player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                    }
                }
                assertEquals("$entry: the final stop is on its way to the core", 1, gateway.stopPlaybackEntered)

                viewModel.replaceItem("other") // PiP Play-from-Home
                runCurrent()
                assertFalse("$entry: the replacement waits for the old stop", "other" in gateway.preparePlaybackCalls)

                gate.complete(Unit)
                runCurrent()
                assertEquals("$entry", listOf(plan.playSessionId), gateway.stopPlaybackSessionIds)
                assertEquals("$entry", listOf(170_000_000L), gateway.stopPlaybackCalls)
                assertTrue("$entry: then the replacement prepares", "other" in gateway.preparePlaybackCalls)
            }
            } catch (e: AssertionError) {
                failures += e.message.orEmpty()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `a stop before a chained negotiation lands starts no second fallback and disposes the landing`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(transcodeFallbackAllowed = true, embeddedSubtitles = listOf(assStream))
        val landed = samplePlan(playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "landed", embeddedSubtitles = listOf(assStream))
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            prepareTranscodeFallbackResult = Result.success(landed),
        ).apply { prepareTranscodeFallbackGate = gate }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.fireTracksChanged(tracksOf(enAudio to true, enText to false))
            runCurrent()
            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            viewModel.chooseSubtitle(TrackMapping.toId(1, 0)) // the landing will chain a second negotiation

            viewModel.stopPlaybackOnce()
            gate.complete(Unit)
            runCurrent()

            assertEquals("no second fallback", 1, gateway.prepareTranscodeFallbackCalls.size)
            assertEquals("the stop names the session the negotiation took over", listOf(plan.playSessionId), gateway.stopPlaybackSessionIds)
            assertEquals("the landing never loads", listOf(plan), player.loadedPlans)
            assertFalse("the stop ends the landed session in the core", "landed" in gateway.abandonPlaybackSessionIds)
        }
    }

    /** [FakeCoreGateway] whose [getItemDetail] is held at [gate]; [cancelled] records a cancelled wait. */
    private class HeldItemDetailGateway(
        private val inner: FakeCoreGateway,
        val gate: CompletableDeferred<Unit>,
    ) : CoreGateway by inner {
        var cancelled = false

        override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail {
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            return inner.getItemDetail(itemId, accountEpoch)
        }
    }

    @Test
    fun `opening any other surface cancels a loading library info request and the late result changes nothing`() = runTest(timeout = TEST_TIMEOUT) {
        val opens: List<Pair<String, (PlaybackViewModel) -> Unit>> = listOf(
            "Speed" to { it.openSpeedMenu() },
            "Chapters" to { it.openChaptersMenu() },
            "Tracks" to { it.openTrackPicker() },
            "Stats" to { it.openInfoOverlay() },
        )
        val failures = mutableListOf<String>()
        for ((name, open) in opens) {
            try {
            val plan = samplePlan()
            val inner = FakeCoreGateway(
                preparePlaybackResult = Result.success(plan),
                itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
            )
            val gateway = HeldItemDetailGateway(inner, CompletableDeferred())
            val player = FakePlaybackPlayer()
            val viewModel = buildViewModel(gateway, player, plan.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(tracksOf(enAudio to true))
                player.fireRenderedFirstFrame() // the stats sheet needs the OSD detail
                runCurrent()
                viewModel.openLibraryInfoOverlay()
                assertTrue("$name: loading", viewModel.state.value.openSurface is OpenSurface.LibraryInfo)

                open(viewModel)
                val opened = viewModel.state.value.openSurface
                assertFalse("$name: replaced the library info", opened is OpenSurface.LibraryInfo || opened is OpenSurface.None)
                assertTrue("$name: the request is cancelled", gateway.cancelled)

                gateway.gate.complete(Unit)
                runCurrent()
                assertEquals("$name: the late result changes nothing", opened, viewModel.state.value.openSurface)
            }
            } catch (e: AssertionError) {
                failures += e.message.orEmpty()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private enum class SurfaceExit { BACK, ESCAPE, SESSION_END, TRANSITION_START }

    /** docs/12 §12: Back, Escape, a session end and a transition start each close whatever surface is up and kill its library-info request. */
    @Test
    fun `every way a surface can end leaves none open and no library info request alive`() = runTest(timeout = TEST_TIMEOUT) {
        val opens: List<Pair<String, (PlaybackViewModel) -> Unit>> = listOf(
            "Speed" to { it.openSpeedMenu() },
            "Chapters" to { it.openChaptersMenu() },
            "Tracks" to { it.openTrackPicker() },
            "Stats" to { it.openInfoOverlay() },
            "LibraryInfo" to { it.openLibraryInfoOverlay() },
        )
        val failures = mutableListOf<String>()
        for (exit in SurfaceExit.values()) {
            for ((name, open) in opens) {
                try {
                    val plan = samplePlan()
                    val inner = FakeCoreGateway(
                        preparePlaybackResultsByItemId = mapOf(
                            plan.itemId to Result.success(plan),
                            "other" to Result.success(samplePlan(itemId = "other", playSessionId = "session-other")),
                        ),
                        itemDetailResultsByItemId = mapOf(plan.itemId to Result.success(sampleItemDetail())),
                    )
                    val gateway = HeldItemDetailGateway(inner, CompletableDeferred())
                    val player = FakePlaybackPlayer()
                    val viewModel = buildViewModel(gateway, player, plan.itemId)
                    val otherGate = CompletableDeferred<Unit>().also { inner.preparePlaybackGates["other"] = it }
                    withSession(viewModel) {
                        player.fireTracksChanged(tracksOf(enAudio to true))
                        player.fireRenderedFirstFrame()
                        runCurrent()
                        open(viewModel)
                        assertFalse("$exit/$name: opened", viewModel.state.value.openSurface is OpenSurface.None)

                        when (exit) {
                            SurfaceExit.BACK, SurfaceExit.ESCAPE -> viewModel.closeSurface()
                            SurfaceExit.SESSION_END -> viewModel.stopPlaybackOnce()
                            SurfaceExit.TRANSITION_START -> viewModel.replaceItem("other")
                        }
                        assertEquals("$exit/$name: closed", OpenSurface.None, viewModel.state.value.openSurface)
                        if (name == "LibraryInfo") assertTrue("$exit/$name: the request is cancelled", gateway.cancelled)

                        gateway.gate.complete(Unit)
                        otherGate.complete(Unit)
                        runCurrent()
                        assertEquals("$exit/$name: a late result reopens nothing", OpenSurface.None, viewModel.state.value.openSurface)
                    }
                } catch (e: AssertionError) {
                    failures += e.message.orEmpty()
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // -- docs/17 §2: the MediaSession's commands reach the view model ----------------------------

    @Test
    fun `a remote stop reports the final position once and finishes like Back`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer().apply { positionTicks = 42_000_000L }
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.stopFromRemote()
            viewModel.stopFromRemote()

            assertEquals(listOf(42_000_000L), gateway.stopPlaybackCalls)
            assertEquals(listOf(plan.playSessionId), gateway.stopPlaybackSessionIds)
            assertEquals(1, player.stopAndClearCallCount)
            assertEquals(PlaybackEvent.Finish, viewModel.events.replayCache.single())
        }
    }

    @Test
    fun `a remote absolute seek goes through the seek path and honours an unseekable file`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(startPositionTicks = 100_000_000L) // 10s in
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            viewModel.seekToPositionMs(40_000L)

            assertEquals(listOf(30_000L), player.seekCalls)
            assertEquals(400_000_000L, viewModel.positionTicks.value)

            player.seekabilityValue = Seekability.UNSEEKABLE
            viewModel.seekToPositionMs(60_000L)

            assertEquals(listOf(30_000L), player.seekCalls)
        }
    }

    // -- docs/17 §2: a remote Stop is never dropped, whatever the session is doing ---------------

    private enum class StopMoment { PLAYING, AUTOPLAY_AWAITING_LANDING, AUTOPLAY_AWAITING_RESTORE, REPLACE_AWAITING_LANDING, AUTOPLAY_PREPARING }

    @Test
    fun `a remote stop during any transition stops the old session once, loads nothing and finishes`() = runTest(timeout = TEST_TIMEOUT) {
        val failures = mutableListOf<String>()
        for (moment in StopMoment.values()) {
            try {
                val plan1 = samplePlan(itemId = "ep-1", itemType = "Episode", seriesId = "series-1", playSessionId = "session-1", runtimeTicks = 200_000_000L)
                val plan2 = samplePlan(itemId = "ep-2", itemType = "Episode", seriesId = "series-1", playSessionId = "session-2", runtimeTicks = 200_000_000L)
                val other = samplePlan(itemId = "other", playSessionId = "session-other")
                val gateway = FakeCoreGateway(
                    preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan1), "ep-2" to Result.success(plan2), "other" to Result.success(other)),
                    nextEpisodeByItemId = mapOf("ep-1" to testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)),
                )
                val player = FakePlaybackPlayer().apply { positionTicks = 42_000_000L }
                val viewModel = buildViewModel(gateway, player, plan1.itemId)
                withSession(viewModel) {
                    val stopGate = CompletableDeferred<Unit>()
                    val restored = CompletableDeferred<Unit>()
                    val prepareGate = CompletableDeferred<Unit>()
                    when (moment) {
                        StopMoment.PLAYING -> Unit
                        StopMoment.AUTOPLAY_AWAITING_LANDING -> {
                            gateway.stopPlaybackGate = stopGate
                            viewModel.playNextEpisode()
                        }
                        StopMoment.AUTOPLAY_AWAITING_RESTORE -> {
                            gateway.accountRestored = restored
                            viewModel.playNextEpisode()
                        }
                        StopMoment.REPLACE_AWAITING_LANDING -> {
                            gateway.stopPlaybackGate = stopGate
                            viewModel.replaceItem("other")
                        }
                        StopMoment.AUTOPLAY_PREPARING -> {
                            gateway.preparePlaybackGates["ep-2"] = prepareGate
                            viewModel.playNextEpisode()
                        }
                    }
                    runCurrent()

                    viewModel.stopFromRemote()
                    stopGate.complete(Unit)
                    restored.complete(Unit)
                    prepareGate.complete(Unit)
                    runCurrent()

                    assertEquals("$moment: S1 is stopped once, with its position", listOf(plan1.playSessionId), gateway.stopPlaybackSessionIds)
                    assertEquals("$moment", listOf(42_000_000L), gateway.stopPlaybackCalls)
                    assertEquals("$moment: the refused title never loads", listOf(plan1), player.loadedPlans)
                    assertEquals("$moment: Finish is emitted", PlaybackEvent.Finish, viewModel.events.replayCache.lastOrNull())
                    if (moment == StopMoment.AUTOPLAY_PREPARING) {
                        assertEquals("$moment: the landed prepare is abandoned", listOf("session-2"), gateway.abandonPlaybackSessionIds)
                    } else {
                        assertFalse("$moment: the next plan is never loaded", "ep-2" in gateway.preparePlaybackCalls || "other" in gateway.preparePlaybackCalls)
                    }
                }
            } catch (e: AssertionError) {
                failures += e.message.orEmpty()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private enum class Ending { REMOTE_STOP, BACK }
    private enum class EndingCard { STILL_WATCHING, STILL_WATCHING_PARKED, NEXT_UP, PLAYING }

    /** What [PlaybackScreen]'s Back does: each press peels one layer; the last one finishes the activity, which stops the session. */
    private fun pressBack(viewModel: PlaybackViewModel) {
        while (true) {
            val state = viewModel.state.value
            when (resolveBackAction(pickerOpen = false, nextUpShown = state.nextUp != null, osdVisible = false, stillWatchingShown = state.stillWatching != null)) {
                BackAction.STOP_STILL_WATCHING -> return viewModel.stillWatchingStop()
                BackAction.DISMISS_NEXT_UP -> viewModel.dismissNextUp()
                else -> return viewModel.stopPlaybackOnce()
            }
        }
    }

    @Test
    fun `Stop and Back end the session the same way with the card up, parked, next-up shown or playing`() = runTest(timeout = TEST_TIMEOUT) {
        val failures = mutableListOf<String>()
        for (ending in Ending.values()) for (card in EndingCard.values()) {
            val label = "$ending x $card"
            try {
                val plan = episodePlan("ep-1")
                val settings = defaultTestSettings().let { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = 5u)) }
                val decision = if (card == EndingCard.NEXT_UP) StillWatchingDecision.COUNTDOWN else StillWatchingDecision.ASK_STILL_WATCHING
                val gateway = FakeCoreGateway(
                    preparePlaybackResultsByItemId = mapOf("ep-1" to Result.success(plan)),
                    nextEpisodeByItemId = mapOf("ep-1" to testCard(id = "ep-2", itemType = "Episode", name = "Episode Two", indexNumber = 2)),
                    noteEpisodeFinishedDecisions = ArrayDeque(listOf(decision)),
                    settings = settings.let { if (card == EndingCard.NEXT_UP) it.copy(autoplayEnabled = false) else it },
                )
                val player = FakePlaybackPlayer().apply { positionTicks = if (card == EndingCard.PLAYING) 42_000_000L else 170_000_000L }
                val viewModel = buildViewModel(gateway, player, plan.itemId)
                withSession(viewModel) {
                    if (card != EndingCard.PLAYING) advanceOneTick()
                    when (card) {
                        EndingCard.STILL_WATCHING, EndingCard.STILL_WATCHING_PARKED -> assertNotNull("$label: the card is up", viewModel.state.value.stillWatching)
                        EndingCard.NEXT_UP -> assertNotNull("$label: next-up is shown", viewModel.state.value.nextUp)
                        EndingCard.PLAYING -> Unit
                    }
                    if (card == EndingCard.STILL_WATCHING_PARKED) {
                        gateway.setEpochs(epoch = 1uL, parked = 0uL)
                        runCurrent()
                    }

                    when (ending) {
                        Ending.REMOTE_STOP -> viewModel.stopFromRemote()
                        Ending.BACK -> pressBack(viewModel)
                    }
                    val events = viewModel.events.replayCache.toList()
                    advanceTimeBy(10_000L)
                    runCurrent()

                    assertEquals("$label: one stop, with the position", listOf(player.positionTicks), gateway.stopPlaybackCalls)
                    assertEquals("$label: nothing happens after the timeout", events, viewModel.events.replayCache.toList())
                    assertEquals("$label: nothing else is prepared", listOf("ep-1"), gateway.preparePlaybackCalls)
                    val expected = when (card) {
                        EndingCard.STILL_WATCHING -> PlaybackEvent.FinishToDetail("ep-2")
                        else -> PlaybackEvent.Finish
                    }
                    if (card == EndingCard.STILL_WATCHING || card == EndingCard.STILL_WATCHING_PARKED || ending == Ending.REMOTE_STOP) {
                        val want = if (card == EndingCard.STILL_WATCHING_PARKED) PlaybackEvent.Finish else expected
                        assertEquals("$label: the event", listOf<PlaybackEvent>(want), events)
                    }
                }
            } catch (e: AssertionError) {
                failures += e.message.orEmpty()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // -- docs/10 / docs/17 §2: the player is wired only by start, and a dead VM leaves a live VM's hook alone ----

    @Test
    fun `the player is neither built nor wired before the account restore lets Play start`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan))
        val restored = CompletableDeferred<Unit>().also { gateway.accountRestored = it }
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            runCurrent()
            assertFalse("not wired before the restore", player.hasActiveListener)
            assertTrue("no prepare yet", gateway.preparePlaybackCalls.isEmpty())

            restored.complete(Unit)
            runCurrent()

            assertEquals(listOf(plan.itemId), gateway.preparePlaybackCalls)
            assertTrue("wired once the plan is adopted", player.hasActiveListener)
        }
    }

    @Test
    fun `clearing a view model after a newer one installed its decoder hook leaves the newer hook`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val player = FakePlaybackPlayer()
        val first = buildViewModel(FakeCoreGateway(preparePlaybackResult = Result.success(plan)), player, plan.itemId)
        val second = buildViewModel(FakeCoreGateway(preparePlaybackResult = Result.success(plan)), player, plan.itemId)
        val installed = player.onVideoDecoderInitialized
        try {
            assertNotNull("the newer view model owns the hook", installed)

            ViewModelStore().apply { put("first", first) }.clear()

            assertSame("the newer view model's hook survives", installed, player.onVideoDecoderInitialized)
        } finally {
            ViewModelStore().apply { put("second", second) }.clear() // a live session would keep runTest's scheduler busy
        }
        assertNull("clearing the owner removes it", player.onVideoDecoderInitialized)
    }

    // -- docs/12 §16: the subtitle delay control. --------------------------------------------

    /** [pickerTracks] with its English subtitle selected. */
    private fun tracksWithSubtitleOn(): Tracks {
        val english = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("English").setLanguage("eng").build()
        val subtitle = Format.Builder().setSampleMimeType("text/vtt").setLabel("English").setLanguage("eng").build()
        return Tracks(
            listOf(
                Tracks.Group(TrackGroup(english), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(TrackGroup(subtitle), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            ),
        )
    }

    /** Two English subtitles, the one at [selected] showing, after one English audio track. */
    private fun tracksWithTwoSubtitles(selected: Int): Tracks {
        val audio = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("English").setLanguage("eng").build()
        fun subtitle(label: String) = Format.Builder().setSampleMimeType("text/vtt").setLabel(label).setLanguage("eng").build()
        fun group(format: Format, on: Boolean) = Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(on))
        return Tracks(listOf(group(audio, true), group(subtitle("Full"), selected == 0), group(subtitle("Forced"), selected == 1)))
    }

    private fun delayViewModel(player: FakePlaybackPlayer): PlaybackViewModel {
        val plan = samplePlan()
        return buildViewModel(FakeCoreGateway(preparePlaybackResult = Result.success(plan)), player, plan.itemId)
    }

    @Test
    fun `the picker has no Delay row while subtitles are off`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())
            viewModel.openTrackPicker()

            val picker = viewModel.state.value.trackPicker!!
            assertFalse(picker.delayRowShown)
            assertEquals(4, picker.rowCount) // 2 audio, Off, English
        }
    }

    @Test
    fun `the picker ends with a Delay row showing the current delay once a subtitle is selected`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.setSubtitleDelayMs(250)
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openTrackPicker()

            val picker = viewModel.state.value.trackPicker!!
            assertTrue(picker.delayRowShown)
            assertEquals(4, picker.rowCount) // 1 audio, Off, English, Delay
            assertEquals("+250 ms", picker.delayText)
        }
    }

    @Test
    fun `choosing Off in the picker takes the Delay row away at once`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openTrackPicker()
            assertTrue(viewModel.state.value.trackPicker!!.delayRowShown)

            viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)

            assertFalse(viewModel.state.value.trackPicker!!.delayRowShown)
        }
    }

    @Test
    fun `opening the delay control from the picker replaces it and leaves the OSD unpinned`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.setSubtitleDelayMs(-500)
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openTrackPicker()

            viewModel.openSubtitleDelay()

            val state = viewModel.state.value
            assertNull("the picker is gone", state.trackPicker)
            assertEquals(-500L, state.subtitleDelay!!.delayMs)
            assertEquals("English · VTT", state.subtitleDelay!!.track)
            assertFalse("the control hides the OSD instead of pinning it", state.openSurface.pinsOsd)
            assertTrue("every other surface pins it", OpenSurface.Speed.pinsOsd && OpenSurface.Chapters.pinsOsd)
            assertFalse(OpenSurface.None.pinsOsd)
        }
    }

    @Test
    fun `the delay control is refused while subtitles are off`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(pickerTracks())

            viewModel.openSubtitleDelay()

            assertNull(viewModel.state.value.subtitleDelay)
        }
    }

    @Test
    fun `each step reaches the player at once and the control shows it`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()

            viewModel.subtitleDelayKey(DelayKey.Right(0))
            viewModel.subtitleDelayKey(DelayKey.Right(0))
            assertEquals(100L, player.subtitleDelayMs)
            assertEquals(100L, viewModel.state.value.subtitleDelay!!.delayMs)

            viewModel.subtitleDelayKey(DelayKey.Left(1_000))
            assertEquals(-150L, player.subtitleDelayMs)
            assertEquals(-150L, viewModel.state.value.subtitleDelay!!.delayMs)
        }
    }

    @Test
    fun `a fresh Select press resets the delay on the player and keeps the control open`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.setSubtitleDelayMs(1_500)
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()

            viewModel.subtitleDelayKey(DelayKey.Reset)
            viewModel.subtitleDelayKey(DelayKey.Swallow)

            assertEquals(0L, player.subtitleDelayMs)
            assertEquals(0L, viewModel.state.value.subtitleDelay!!.delayMs)
        }
    }

    @Test
    fun `Close keeps the delay`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.setSubtitleDelayMs(1_500)
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()

            viewModel.subtitleDelayKey(DelayKey.Close)

            assertNull(viewModel.state.value.subtitleDelay)
            assertEquals(1_500L, player.subtitleDelayMs)
        }
    }

    @Test
    fun `opening and stepping never pauses playback`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            val playingBefore = player.playWhenReady

            viewModel.openSubtitleDelay()
            viewModel.subtitleDelayKey(DelayKey.Right(0))
            viewModel.subtitleDelayKey(DelayKey.Reset)
            viewModel.subtitleDelayKey(DelayKey.Swallow)

            assertEquals(playingBefore, player.playWhenReady)
            assertEquals(0, player.togglePlayPauseCallCount)
        }
    }

    @Test
    fun `the delay returns when the same item is opened again and each item keeps its own`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        suspend fun session(itemId: String, body: suspend (PlaybackViewModel) -> Unit) {
            val plan = samplePlan(itemId = itemId)
            val viewModel = buildViewModel(FakeCoreGateway(preparePlaybackResult = Result.success(plan)), player, plan.itemId)
            withSession(viewModel) {
                player.fireTracksChanged(tracksWithSubtitleOn())
                viewModel.openSubtitleDelay()
                body(viewModel)
            }
        }

        session("item-a") { viewModel ->
            repeat(15) { viewModel.subtitleDelayKey(DelayKey.Right(0)) }
            assertEquals(750L, player.subtitleDelayMs)
        }
        session("item-b") { viewModel ->
            assertEquals("a different item starts at zero", 0L, viewModel.state.value.subtitleDelay!!.delayMs)
            repeat(4) { viewModel.subtitleDelayKey(DelayKey.Left(0)) }
            assertEquals(-200L, player.subtitleDelayMs)
        }
        session("item-a") { viewModel ->
            assertEquals("the first item gets its own value back", 750L, viewModel.state.value.subtitleDelay!!.delayMs)
        }
        session("item-b") { viewModel ->
            assertEquals(-200L, viewModel.state.value.subtitleDelay!!.delayMs)
        }
    }

    @Test
    fun `picking a different subtitle track returns the item's delay to zero`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithTwoSubtitles(selected = 1))
            player.setSubtitleDelayMs(900)
            val subtitles = TrackMapping.toTrackInfos(player.currentTracks!!).filter { it.kind == TrackKindFfi.SUBTITLE }
            val other = subtitles.first { !it.isSelected }
            viewModel.openTrackPicker()

            viewModel.chooseSubtitle(other.id)

            assertEquals(0L, player.subtitleDelayMs)
            assertEquals("0 ms", viewModel.state.value.trackPicker?.delayText)
        }
    }

    @Test
    fun `picking the subtitle that already shows leaves the delay`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithTwoSubtitles(selected = 1))
            player.setSubtitleDelayMs(900)
            val shown = TrackMapping.toTrackInfos(player.currentTracks!!).first { it.kind == TrackKindFfi.SUBTITLE && it.isSelected }

            viewModel.chooseSubtitle(shown.id)

            assertEquals(900L, player.subtitleDelayMs)
        }
    }

    @Test
    fun `a track announcement without a choice leaves the delay`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithTwoSubtitles(selected = 1))
            player.setSubtitleDelayMs(900)

            player.fireTracksChanged(tracksWithTwoSubtitles(selected = 1))
            player.fireTracksChanged(tracksWithTwoSubtitles(selected = 0))

            assertEquals(900L, player.subtitleDelayMs)
        }
    }

    @Test
    fun `keys sent after the control closed change nothing`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())

            viewModel.subtitleDelayKey(DelayKey.Right(0))

            assertEquals(0L, player.subtitleDelayMs)
            assertNull(viewModel.state.value.subtitleDelay)
        }
    }

    @Test
    fun `the control closes when the player reports no subtitle selected`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()
            assertNotNull(viewModel.state.value.subtitleDelay)

            player.fireTracksChanged(pickerTracks())

            assertNull(viewModel.state.value.subtitleDelay)
        }
    }

    @Test
    fun `the control survives a track announcement that still has the subtitle and an empty one`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()

            player.fireTracksChanged(tracksWithSubtitleOn())
            player.fireTracksChanged(Tracks.EMPTY)

            assertNotNull(viewModel.state.value.subtitleDelay)
        }
    }

    @Test
    fun `stopping the session closes the control`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSubtitleDelay()

            viewModel.stopPlaybackOnce()

            assertNull(viewModel.state.value.subtitleDelay)
        }
    }

    @Test
    fun `closeSubtitleDelay closes only the delay control`() = runTest(timeout = TEST_TIMEOUT) {
        val player = FakePlaybackPlayer()
        val viewModel = delayViewModel(player)
        withSession(viewModel) {
            player.fireTracksChanged(tracksWithSubtitleOn())
            viewModel.openSpeedMenu()
            viewModel.closeSubtitleDelay()
            assertTrue(viewModel.state.value.speedMenuOpen)

            viewModel.openSubtitleDelay()
            viewModel.closeSubtitleDelay()
            assertEquals(OpenSurface.None, viewModel.state.value.openSurface)
        }
    }

    // -- Watch Together (docs/28 §1, §4, §5) ----------------------------------------

    /** A running membership over [gateway] whose port is [viewModel], as `PlaybackActivity`'s group launch attaches it. */
    private class GroupRig(
        private val scope: TestScope,
        val gateway: FakeCoreGateway,
        val player: FakePlaybackPlayer,
        val viewModel: PlaybackViewModel,
        val exec: SyncPlayExecutor,
    ) {
        val inputs: List<SyncPlayInput> get() = gateway.syncPlayInputs

        fun step(update: SyncPlayUpdate) {
            gateway.syncPlayListener!!.onStep(update)
            scope.runCurrent()
        }

        fun directive(seq: ULong, directive: SyncPlayDirective) = step(SyncPlayUpdate(listOf(SyncPlayDirectiveAt(seq, directive)), emptyList(), false))

        fun attach() {
            exec.send(GroupEvent.PortAttached(viewModel.portFor(exec)))
            scope.runCurrent()
        }

        /** The group's first item, [itemId] at [startTicks], loaded into the attached player and ready. */
        fun loadAndLand(itemId: String, startTicks: Long = 0L) {
            directive(1uL, SyncPlayDirective.Load(itemId, "entry-1", startTicks))
            attach()
            player.firePlaybackStateChanged(Player.STATE_READY)
            scope.runCurrent()
        }
    }

    /** Runs [body] against a group-launched player; the membership ends after it, so the process slot is free for the next test. */
    private fun groupTest(
        gateway: FakeCoreGateway,
        itemId: String = "item-g",
        playerOf: (FakePlaybackPlayer) -> PlaybackPlayer = { it },
        body: suspend TestScope.(GroupRig) -> Unit,
    ) = runTest(timeout = TEST_TIMEOUT) {
        gateway.syncPlayGroup = SyncPlayGroup.Starting(name = "member-a's group")
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, playerOf(player), itemId, forGroup = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entry = SyncPlayExecutor.create(gateway.accountEpoch, gateway) { epoch ->
            SyncPlayExecutor(gateway, epoch, {}, backgroundScope, dispatcher) { 1_000_000L + testScheduler.currentTime }
        }
        val exec = (entry as GroupEntry.Started).executor
        runCurrent()
        try {
            body(GroupRig(this, gateway, player, viewModel, exec))
        } finally {
            viewModel.stopPlaybackOnce()
            exec.send(GroupEvent.EpochMoved)
            runCurrent()
        }
    }

    /** As ExoPlayer: a seek buffers, reported with whatever play intent the player has at that moment. */
    private class SeekBuffersPlayer(private val fake: FakePlaybackPlayer) : PlaybackPlayer by fake {
        override fun seekGroup(targetMs: Long, onLanded: ((landedTicks: Long, latencyMs: Long) -> Unit)?) {
            fake.seekGroup(targetMs, onLanded)
            fake.firePlaybackStateChanged(Player.STATE_BUFFERING)
        }
    }

    private fun groupGateway(plan: PlaybackPlan, transcode: PlaybackPlan? = null) = FakeCoreGateway(
        preparePlaybackResult = Result.success(plan),
        prepareTranscodeFallbackResult = transcode?.let { Result.success(it) },
    )

    private val member = SyncPlayGroup.Member(
        role = SyncPlayRole.HOST,
        stage = SyncPlayStage.PLAYING,
        host = SyncPlayHost.Me,
        participants = listOf("member-a"),
        phase = SyncPlayPhase.PLAYING,
        connected = true,
        promptShown = false,
    )

    /** docs/28 §8: a player that never joins a group pays nothing for Watch Together. */
    @Test
    fun `solo playback makes no watch together call`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan(runtimeTicks = 600_000_000L)
        val intro = sampleSegment(MediaSegmentKind.INTRO, 100_000_000L, 200_000_000L)
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan), mediaSegmentsByItemId = mapOf(plan.itemId to listOf(intro)))
        val player = FakePlaybackPlayer()
        val viewModel = buildViewModel(gateway, player, plan.itemId)
        withSession(viewModel) {
            player.firePlaybackStateChanged(Player.STATE_READY)
            player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            player.firePlaybackStateChanged(Player.STATE_READY)
            viewModel.togglePlayPause()
            viewModel.seek(10_000L)
            viewModel.tapSeek(10_000L)
            viewModel.commitGlide(30_000L, endClamped = false)
            viewModel.jumpToChapterStart(50_000_000L)
            viewModel.skipSegment(intro)
            viewModel.seekToPositionMs(5_000L)
            viewModel.setPlaybackRate(1.5f)
            advanceOneTick()
            advanceOneTick()
            player.fireError(PlaybackException("boom", UnknownHostException("boom"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            player.firePlaybackStateChanged(Player.STATE_ENDED)
            runCurrent()

            assertEquals(1, player.togglePlayPauseCallCount)
            assertEquals(6, player.seekCalls.size)
            assertEquals(0, gateway.syncPlayCalls)
            assertNull(viewModel.state.value.syncPlay)
        }
    }

    /** docs/28 §3.3: a final stop still on its way to the core holds a create back until the core has it. */
    @Test
    fun `create waits for a launched final stop`() = runTest(timeout = TEST_TIMEOUT) {
        val plan = samplePlan()
        val gateway = FakeCoreGateway(preparePlaybackResult = Result.success(plan)).apply { syncPlayGroup = SyncPlayGroup.Starting(name = "member-a's group") }
        val viewModel = buildViewModel(gateway, FakePlaybackPlayer(), plan.itemId)
        val stopGate = CompletableDeferred<Unit>().also { gateway.stopPlaybackGate = it }
        try {
            viewModel.stopPlaybackOnce()
            val entry = async { SyncPlayExecutor.create(1uL, gateway) { epoch -> SyncPlayExecutor(gateway, epoch, {}, backgroundScope, StandardTestDispatcher(testScheduler)) } }
            runCurrent()
            assertEquals("the final stop is on its way to the core", 1, gateway.stopPlaybackEntered)
            assertEquals("no create while it is", 0, gateway.syncPlayCalls)

            stopGate.complete(Unit)
            runCurrent()
            assertEquals("then the create goes out", listOf("member-a's group"), gateway.syncPlayCreatedNames)
            assertTrue(entry.await() is GroupEntry.Started)
        } finally {
            stopGate.complete(Unit)
            SyncPlayExecutor.onEpochMoved()
            advanceUntilIdle()
        }
    }

    /** docs/28 §5, design Q2: the group's Load replaces what plays, paused on exactly its frame, and its ready answers ItemLoaded. */
    @Test
    fun `a group load starts paused exactly and reports ItemLoaded on ready`() = groupTest(groupGateway(samplePlan(itemId = "item-g", startPositionTicks = 90_000_000L))) { rig ->
        assertEquals("a group launch prepares nothing before the group's Load", emptyList<String>(), rig.gateway.preparePlaybackCalls)

        rig.loadAndLand("item-g", startTicks = 300_000_000L)

        assertEquals(listOf("item-g"), rig.gateway.preparePlaybackCalls)
        assertEquals(listOf(true), rig.gateway.preparePlaybackStartFromBeginningCalls)
        assertEquals(LoadStart.GroupExactPaused(300_000_000L), rig.player.lastLoadStart)
        assertFalse(rig.player.playWhenReady)
        assertEquals(300_000_000L, rig.viewModel.positionTicks.value)
        assertEquals(listOf<SyncPlayInput>(SyncPlayInput.ItemLoaded("entry-1", 300_000_000L)), rig.inputs)
    }

    /** docs/28 §4, design Q7: the server moves the player, so a press is a request and the OSD waits for the directive. */
    @Test
    fun `in a group play pause and seek keys become requests and leave the player alone`() =
        groupTest(groupGateway(samplePlan(itemId = "item-g", runtimeTicks = 6_000_000_000L))) { rig ->
            rig.loadAndLand("item-g", startTicks = 300_000_000L)
            val viewModel = rig.viewModel

            viewModel.togglePlayPause() // loaded paused
            rig.player.playWhenReady = true
            viewModel.togglePlayPause()
            viewModel.seek(10_000L)
            viewModel.commitGlide(600_000L, endClamped = true)
            viewModel.seekToPositionMs(120_000L)
            viewModel.playNextEpisode()
            viewModel.playPrevious()
            runCurrent()

            assertEquals(
                listOf(
                    SyncPlayInput.ItemLoaded("entry-1", 300_000_000L),
                    SyncPlayInput.Unpause,
                    SyncPlayInput.Pause,
                    SyncPlayInput.Seek(400_000_000L),
                    SyncPlayInput.Seek(6_000_000_000L),
                    SyncPlayInput.Seek(1_200_000_000L),
                    SyncPlayInput.Next,
                    SyncPlayInput.Prev,
                ),
                rig.inputs,
            )
            assertEquals(0, rig.player.togglePlayPauseCallCount)
            assertEquals(0, rig.player.pauseCallCount)
            assertEquals(emptyList<Long>(), rig.player.seekCalls)
            assertEquals(emptyList<Long>(), rig.player.groupSeekCalls)
            assertEquals(300_000_000L, viewModel.positionTicks.value)
        }

    /** docs/28 §1: per-member auto-skip would cause seek storms, and the queue, not next-up, picks what follows. */
    @Test
    fun `in a group auto-skip next-up autoplay still-watching and speed stay off`() {
        val plan = episodePlan("ep-g")
        val commercial = sampleSegment(MediaSegmentKind.COMMERCIAL, 160_000_000L, 180_000_000L)
        val gateway = FakeCoreGateway(
            preparePlaybackResult = Result.success(plan),
            mediaSegmentsByItemId = mapOf(plan.itemId to listOf(commercial)),
            nextEpisodeByItemId = mapOf(plan.itemId to testCard(id = "ep-next", itemType = "Episode", name = "Next", indexNumber = 2)),
        )
        groupTest(gateway, itemId = plan.itemId) { rig ->
            rig.loadAndLand(plan.itemId, startTicks = 170_000_000L)
            rig.player.playWhenReady = true
            advanceOneTick()
            advanceOneTick()
            rig.viewModel.setPlaybackRate(2f)
            rig.viewModel.openSpeedMenu()

            assertEquals(emptyList<Long>(), rig.player.seekCalls)
            assertNull(rig.viewModel.state.value.nextUp)
            assertNull(rig.viewModel.state.value.stillWatching)
            assertNull(rig.viewModel.events.replayCache.firstOrNull())
            assertEquals(listOf(plan.itemId), rig.gateway.preparePlaybackCalls)
            assertEquals(listOf(1f), rig.player.setPlaybackRateCalls)
            assertEquals(1f, rig.viewModel.state.value.playbackRate)
            assertFalse(rig.viewModel.state.value.speedMenuOpen)
            assertEquals(SyncPlayInput.PositionSample(170_000_000L), rig.inputs.last())
        }
    }

    /** docs/28 §1: the end of an item is the group's to handle; the queue moves on or returns to the lobby. */
    @Test
    fun `in a group the natural end reports PlayerEnded and keeps the player`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.player.firePlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()

        assertEquals(SyncPlayInput.PlayerEnded, rig.inputs.last())
        assertEquals(0, rig.player.stopAndClearCallCount)
        assertEquals(emptyList<Long>(), rig.gateway.stopPlaybackCalls)
        assertNull(rig.viewModel.events.replayCache.firstOrNull())
    }

    /** docs/28 §4: leaving the player leaves the group, once, however many exit paths run. */
    @Test
    fun `in a group Back reports PlayerClosed once`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        repeat(3) { rig.viewModel.stopPlaybackOnce() } // onStop, onDestroy, onCleared
        runCurrent()

        assertEquals(1, rig.inputs.count { it == SyncPlayInput.PlayerClosed })
        assertEquals(1, rig.gateway.stopPlaybackCalls.size)
        assertNull(rig.viewModel.state.value.syncPlay)
    }

    /** docs/28 §1: the end of the queue returns to the lobby and the viewer stays in the group. */
    @Test
    fun `return to lobby closes the player without a PlayerClosed`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.directive(2uL, SyncPlayDirective.ReturnToLobby)
        rig.viewModel.stopPlaybackOnce() // the closing Activity's onStop
        runCurrent()

        assertEquals(PlaybackEvent.Finish, rig.viewModel.events.replayCache.last())
        assertEquals(1, rig.gateway.stopPlaybackCalls.size)
        assertFalse(rig.inputs.contains(SyncPlayInput.PlayerClosed))
    }

    /** docs/28 §4: a fatal error leaves the group as a playback error, never as a closed player. */
    @Test
    fun `a fatal error in a group reports Fatal and no PlayerClosed`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        rig.viewModel.stopPlaybackOnce()
        runCurrent()

        assertEquals(SyncPlayInput.PlaybackError(SyncPlaySeverity.FATAL), rig.inputs.last())
        assertFalse(rig.inputs.contains(SyncPlayInput.PlayerClosed))
        assertTrue(rig.viewModel.events.replayCache.last() is PlaybackEvent.FinishWithMessage)
    }

    @Test
    fun `a failed prepare in a group reports Fatal and no PlayerClosed`() =
        groupTest(FakeCoreGateway(preparePlaybackResult = CoreException.WouldTranscode("codec").asFailure())) { rig ->
            rig.directive(1uL, SyncPlayDirective.Load("item-g", "entry-1", 0L))
            rig.attach()
            rig.viewModel.stopPlaybackOnce()
            runCurrent()

            assertEquals(listOf<SyncPlayInput>(SyncPlayInput.PlaybackError(SyncPlaySeverity.FATAL)), rig.inputs)
            assertTrue(rig.viewModel.events.replayCache.last() is PlaybackEvent.FinishWithMessage)
        }

    /** docs/28 §4: an account change leaves the group, and a playing video keeps playing solo. */
    @Test
    fun `an epoch move in a group goes solo and keeps playing`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.gateway.syncPlayGroup = member
        rig.step(SyncPlayUpdate(emptyList(), emptyList(), true))
        assertSame(rig.exec, rig.viewModel.state.value.syncPlay)
        val before = rig.inputs.toList()

        rig.gateway.setEpochs(epoch = 1uL, parked = 0uL)
        runCurrent()
        rig.viewModel.togglePlayPause()
        runCurrent()

        assertNull(rig.viewModel.state.value.syncPlay)
        assertEquals(1, rig.player.togglePlayPauseCallCount)
        assertEquals(0, rig.player.stopAndClearCallCount)
        assertNull(rig.viewModel.events.replayCache.firstOrNull())
        assertEquals(before, rig.inputs)
    }

    /** docs/28 §5: the server's Seek lands exactly, and its landing answers the directive that asked. */
    @Test
    fun `a group seek reports SeekLanded with its directive`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.player.playWhenReady = true
        rig.directive(2uL, SyncPlayDirective.Seek(450_000_000L))

        assertEquals(listOf(45_000L), rig.player.groupSeekCalls)
        assertFalse(rig.player.playWhenReady)
        rig.player.groupLanded!!(450_000_000L, 120L)
        runCurrent()
        assertEquals(SyncPlayInput.SeekLanded(2uL, 450_000_000L), rig.inputs.last())
    }

    /** docs/28 §5: a pause or play moves the player onto the group's position only from 400 ms of drift, reporting no landing. */
    @Test
    fun `a group pause or play aligns the player only beyond the tolerance`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        val now = 1_000_000L + testScheduler.currentTime
        rig.directive(2uL, SyncPlayDirective.Pause(freezeTicks = 3_000_000L, dueWallMs = now))
        assertFalse(rig.player.playWhenReady)
        rig.directive(3uL, SyncPlayDirective.Unpause(positionTicks = 50_000_000L, dueWallMs = now))

        assertEquals(listOf(5_000L), rig.player.groupSeekCalls)
        assertTrue(rig.player.playWhenReady)
        assertNull(rig.player.groupLanded)
    }

    /** As PlayerHolder: group seeks go through the real [SeekSerializer] and move the position once issued; [land] is Media3's landing signal. */
    private class SerializedSeekPlayer(private val fake: FakePlaybackPlayer) : PlaybackPlayer by fake {
        private val seeks = SeekSerializer()

        override fun seekGroup(targetMs: Long, onLanded: ((landedTicks: Long, latencyMs: Long) -> Unit)?) {
            fake.seekGroup(targetMs, onLanded)
            val report = onLanded?.let { { ms: Long, latencyMs: Long -> it(ms * 10_000L, latencyMs) } }
            if (seeks.request(targetMs, nowMs = 0L, report) is SeekSerializer.Decision.Issue) fake.positionTicks = targetMs * 10_000L
        }

        fun land(nowMs: Long) {
            seeks.landed(nowMs, positionMs = fake.positionTicks / 10_000L)?.let { fake.positionTicks = it * 10_000L }
        }
    }

    /** docs/28 §5: a pause that aligns mid-correction keeps the correction's landing owed, so the member is never left silent. */
    @Test
    fun `a group pause during a drift correction still reports the correction's landing`() {
        var player: SerializedSeekPlayer? = null
        groupTest(groupGateway(samplePlan(itemId = "item-g", runtimeTicks = 6_000_000_000L)), playerOf = { SerializedSeekPlayer(it).also { p -> player = p } }) { rig ->
            rig.loadAndLand("item-g")
            rig.player.playWhenReady = true
            rig.directive(2uL, SyncPlayDirective.Correct(300_000_000L))
            rig.directive(3uL, SyncPlayDirective.Pause(freezeTicks = 100_000_000L, dueWallMs = 1_000_000L + testScheduler.currentTime))
            assertEquals("the pause aligns behind the correction", listOf(30_000L, 10_000L), rig.player.groupSeekCalls)

            player!!.land(nowMs = 200L)
            player!!.land(nowMs = 450L)
            runCurrent()
            assertEquals(SyncPlayInput.SeekLanded(2uL, 100_000_000L), rig.inputs.last())

            advanceOneTick()
            advanceOneTick()
            assertEquals("steady again, so drift is sampled", SyncPlayInput.PositionSample(100_000_000L), rig.inputs.last())
        }
    }

    /** docs/28 §5: a play that must first land on the group's position lands while paused, so its buffering is no stall. */
    @Test
    fun `a group play aligns before it plays so the align is no stall`() =
        groupTest(groupGateway(samplePlan(itemId = "item-g")), playerOf = ::SeekBuffersPlayer) { rig ->
            rig.loadAndLand("item-g")
            val now = 1_000_000L + testScheduler.currentTime
            rig.directive(2uL, SyncPlayDirective.Unpause(positionTicks = 50_000_000L, dueWallMs = now))
            rig.player.firePlaybackStateChanged(Player.STATE_READY)
            runCurrent()

            assertEquals(listOf(5_000L), rig.player.groupSeekCalls)
            assertTrue(rig.player.playWhenReady)
            assertEquals(listOf<SyncPlayInput>(SyncPlayInput.ItemLoaded("entry-1", 0L)), rig.inputs)
        }

    /** docs/28 §5: until the group's next Load reaches the player, a READY is the old item's and answers nothing. */
    @Test
    fun `an old item's ready during a group reload is not the new item's landing`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        val stopGate = CompletableDeferred<Unit>().also { rig.gateway.stopPlaybackGate = it }
        try {
            rig.player.positionTicks = 120_000_000L
            rig.directive(2uL, SyncPlayDirective.Load("item-g", "entry-2", 300_000_000L))
            rig.player.firePlaybackStateChanged(Player.STATE_BUFFERING)
            rig.player.firePlaybackStateChanged(Player.STATE_READY)
            runCurrent()
            assertEquals("the old item's stop is still on its way", listOf<SyncPlayInput>(SyncPlayInput.ItemLoaded("entry-1", 0L)), rig.inputs)

            stopGate.complete(Unit)
            runCurrent()
            rig.player.firePlaybackStateChanged(Player.STATE_READY)
            runCurrent()
            assertEquals(listOf(SyncPlayInput.ItemLoaded("entry-1", 0L), SyncPlayInput.ItemLoaded("entry-2", 300_000_000L)), rig.inputs)
        } finally {
            stopGate.complete(Unit)
        }
    }

    /** docs/28 §4: a membership this player left reaches it no more, a late Load included. */
    @Test
    fun `a load from a group the player already left changes nothing`() {
        val plan = samplePlan(itemId = "item-g", transcodeFallbackAllowed = true)
        val transcode = samplePlan(itemId = "item-g", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "session-t")
        groupTest(groupGateway(plan, transcode)) { rig ->
            rig.loadAndLand("item-g")
            rig.player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()
            rig.directive(2uL, SyncPlayDirective.Load("item-h", "entry-2", 0L))

            assertEquals(listOf("item-g"), rig.gateway.preparePlaybackCalls)
            assertEquals(transcode, rig.player.loadedPlan)
            assertNull(rig.viewModel.state.value.syncPlay)
        }
    }

    /** docs/28 §4: choosing other content is leaving the group, so the Leave goes before the solo replace. */
    @Test
    fun `playing another title from a group leaves the group first`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.viewModel.replaceItem("item-other")
        runCurrent()
        rig.viewModel.togglePlayPause()
        runCurrent()

        assertEquals(listOf(SyncPlayInput.ItemLoaded("entry-1", 0L), SyncPlayInput.Leave), rig.inputs)
        assertEquals(listOf("item-g", "item-other"), rig.gateway.preparePlaybackCalls)
        assertEquals(LoadStart.Resume, rig.player.lastLoadStart)
        assertEquals(1, rig.player.togglePlayPauseCallCount)
    }

    /** As ExoPlayer: a seek moves the reported position to its target at once. */
    private class SeekMovesPlayer(private val fake: FakePlaybackPlayer) : PlaybackPlayer by fake {
        override fun seekGroup(targetMs: Long, onLanded: ((landedTicks: Long, latencyMs: Long) -> Unit)?) {
            fake.seekGroup(targetMs, onLanded)
            fake.positionTicks = targetMs * 10_000L
        }
    }

    /** docs/28 §4: taps add up from the newest ask still on its way, through the ticker and the server's answers to older asks. */
    @Test
    fun `seek taps in a playing group add up while earlier seeks are in flight`() =
        groupTest(groupGateway(samplePlan(itemId = "item-g", runtimeTicks = 6_000_000_000L)), playerOf = ::SeekMovesPlayer) { rig ->
            rig.loadAndLand("item-g", startTicks = 300_000_000L)
            rig.player.playWhenReady = true
            val tap = { rig.viewModel.seek(10_000L, resolveTrickplay = false) }

            tap()
            rig.player.positionTicks = 305_000_000L
            advanceOneTick()
            tap()
            rig.directive(2uL, SyncPlayDirective.Seek(400_000_000L))
            tap()
            advanceOneTick()
            rig.directive(3uL, SyncPlayDirective.Seek(600_000_000L))
            tap()
            runCurrent()

            assertEquals(
                "every tap is start + k x 10 s",
                listOf(400_000_000L, 500_000_000L, 600_000_000L, 700_000_000L),
                rig.inputs.filterIsInstance<SyncPlayInput.Seek>().map { it.ticks },
            )
        }

    /** docs/28 §4: a group seek nobody here asked for starts the taps over from where it landed. */
    @Test
    fun `another member's seek starts group taps over from its target`() =
        groupTest(groupGateway(samplePlan(itemId = "item-g", runtimeTicks = 6_000_000_000L)), playerOf = ::SeekMovesPlayer) { rig ->
            rig.loadAndLand("item-g", startTicks = 300_000_000L)
            repeat(2) { rig.viewModel.seek(10_000L, resolveTrickplay = false) }
            rig.directive(2uL, SyncPlayDirective.Seek(2_000_000_000L))
            rig.viewModel.seek(10_000L, resolveTrickplay = false)
            runCurrent()

            assertEquals(
                listOf(400_000_000L, 500_000_000L, 2_100_000_000L),
                rig.inputs.filterIsInstance<SyncPlayInput.Seek>().map { it.ticks },
            )
        }

    /** docs/28 §5: a drift correction is nobody's ask, so taps keep adding to the ask on its way. */
    @Test
    fun `a group drift correction leaves the taps adding up`() =
        groupTest(groupGateway(samplePlan(itemId = "item-g", runtimeTicks = 6_000_000_000L)), playerOf = ::SeekMovesPlayer) { rig ->
            rig.loadAndLand("item-g", startTicks = 300_000_000L)
            rig.player.playWhenReady = true
            rig.viewModel.seek(10_000L, resolveTrickplay = false)
            rig.directive(2uL, SyncPlayDirective.Correct(350_000_000L))
            rig.viewModel.seek(10_000L, resolveTrickplay = false)
            runCurrent()

            assertEquals(listOf(400_000_000L, 500_000_000L), rig.inputs.filterIsInstance<SyncPlayInput.Seek>().map { it.ticks })
        }

    @Test
    fun `a group seek retires our ask and older ones and anyone else's starts over`() {
        val asked = listOf(400L, 500L, 600L)
        assertEquals(listOf(500L, 600L), seeksAfterGroupSeek(asked, 400L))
        assertEquals(emptyList<Long>(), seeksAfterGroupSeek(asked, 600L))
        assertEquals(emptyList<Long>(), seeksAfterGroupSeek(asked, 900L))
        assertEquals("the oldest matching ask is the one that ran", listOf(500L, 400L), seeksAfterGroupSeek(listOf(400L, 500L, 400L), 400L))
    }

    /** docs/28 §1, design Q4: a transcode needs its own stream, so the viewer leaves and falls back solo. */
    @Test
    fun `a transcode fallback in a group leaves then falls back solo`() {
        val plan = samplePlan(itemId = "item-g", transcodeFallbackAllowed = true)
        val transcode = samplePlan(itemId = "item-g", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "session-t")
        groupTest(groupGateway(plan, transcode)) { rig ->
            rig.loadAndLand("item-g")
            rig.player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()
            rig.viewModel.togglePlayPause()
            runCurrent()

            assertEquals(listOf(SyncPlayInput.ItemLoaded("entry-1", 0L), SyncPlayInput.Leave), rig.inputs)
            assertEquals(transcode, rig.player.loadedPlan)
            assertNull(rig.viewModel.state.value.syncPlay)
            assertEquals(1, rig.player.togglePlayPauseCallCount)
        }
    }

    // -- docs/28 §5: a group never renegotiates a transcode for a subtitle -----------------------

    private val groupBurnedPlan = samplePlan(itemId = "item-g", playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(assStream), burnedSubtitleIndex = 2)
    private val groupCleanPlan = samplePlan(itemId = "item-g", playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "clean", embeddedSubtitles = listOf(assStream))

    /** A group playing [plan] with its tracks reported and the picker open. */
    private suspend fun TestScope.landWithPicker(rig: GroupRig, tracks: Tracks) {
        rig.loadAndLand("item-g")
        rig.player.fireTracksChanged(tracks)
        runCurrent()
        rig.viewModel.openTrackPicker()
    }

    /** Neither a renegotiation nor a reload, nor a leave, nor any change to the player or the picker. */
    private suspend fun TestScope.assertRefused(rig: GroupRig, before: List<TrackChoice>, decisionsBefore: Int) {
        runCurrent()
        assertTrue("no renegotiation", rig.gateway.prepareTranscodeFallbackCalls.isEmpty())
        assertEquals("nothing reloads", 1, rig.player.loadedPlans.size)
        assertFalse("still in the group", rig.inputs.contains(SyncPlayInput.Leave))
        assertEquals("the player's tracks are untouched", decisionsBefore, rig.player.applyTrackDecisionCalls.size)
        assertEquals("the picker selection is unchanged", before, rig.viewModel.state.value.trackPicker!!.subtitleTracks)
    }

    @Test
    fun `in a group a stream row pick is refused and nothing reloads`() =
        groupTest(groupGateway(groupCleanPlan, groupBurnedPlan)) { rig ->
            landWithPicker(rig, tracksOf(enAudio to true))
            val before = rig.viewModel.state.value.trackPicker!!.subtitleTracks
            assertEquals(listOf(TRACK_PICKER_SUBTITLE_OFF_ID, streamRowId(2)), before.map { it.id })
            val decisions = rig.player.applyTrackDecisionCalls.size

            rig.viewModel.chooseSubtitle(streamRowId(2))

            assertRefused(rig, before, decisions)
        }

    @Test
    fun `in a group turning off a burned-in subtitle is refused`() =
        groupTest(groupGateway(groupBurnedPlan, groupCleanPlan)) { rig ->
            landWithPicker(rig, tracksOf(enAudio to true))
            val before = rig.viewModel.state.value.trackPicker!!.subtitleTracks
            assertEquals("the burned row reads selected", listOf(false, true), before.map { it.selected })
            val decisions = rig.player.applyTrackDecisionCalls.size

            rig.viewModel.chooseSubtitle(TRACK_PICKER_SUBTITLE_OFF_ID)

            assertRefused(rig, before, decisions)
        }

    @Test
    fun `in a group switching delivered text tracks still works`() {
        val en = EmbeddedSubtitleFfi(index = 3, language = "eng", title = "English", forced = false, default = false, codec = "srt", displayTitle = "English", delivery = SubtitleDeliveryFfi.HLS)
        val fr = EmbeddedSubtitleFfi(index = 4, language = "fre", title = "French", forced = false, default = false, codec = "srt", displayTitle = "French", delivery = SubtitleDeliveryFfi.HLS)
        val plan = samplePlan(itemId = "item-g", playMethod = PlayMethodFfi.TRANSCODE, embeddedSubtitles = listOf(en, fr))
        groupTest(groupGateway(plan, groupCleanPlan)) { rig ->
            landWithPicker(rig, tracksOf(enAudio to true, enText to true, format("text/vtt", "fre", "French") to false))
            val french = TrackMapping.toId(2, 0)

            rig.viewModel.chooseSubtitle(french)
            runCurrent()

            assertEquals(french, rig.player.applyTrackDecisionCalls.last().first.subtitleTrackId)
            assertEquals(french, rig.viewModel.state.value.trackPicker!!.subtitleTracks.single { it.selected }.id)
            assertTrue(rig.gateway.prepareTranscodeFallbackCalls.isEmpty())
            assertEquals(1, rig.player.loadedPlans.size)
        }
    }

    @Test
    fun `in a group automatic resolution never renegotiates`() {
        val gateway = groupGateway(groupBurnedPlan, groupCleanPlan).apply { resolveTracksResult = subtitlesOff }
        groupTest(gateway) { rig ->
            rig.loadAndLand("item-g")
            rig.player.fireTracksChanged(tracksOf(enAudio to true))
            runCurrent()

            assertEquals("resolution ran", 1, rig.gateway.resolveTracksCalls.size)
            assertTrue("the server's burn-in stays", rig.gateway.prepareTranscodeFallbackCalls.isEmpty())
            assertEquals(1, rig.player.loadedPlans.size)
            assertFalse(rig.inputs.contains(SyncPlayInput.Leave))
        }
    }

    /** docs/28 §1: the player names its membership from the group's load on, before the item lands, until it leaves. */
    @Test
    fun `a group load names the membership in the state and leaving clears it`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        assertNull(rig.viewModel.state.value.syncPlay)
        rig.directive(1uL, SyncPlayDirective.Load("item-g", "entry-1", 0L))
        rig.attach()

        assertSame(rig.exec, rig.viewModel.state.value.syncPlay)
        rig.viewModel.leaveGroup()
        runCurrent()
        assertNull(rig.viewModel.state.value.syncPlay)
        assertTrue(rig.inputs.contains(SyncPlayInput.Leave))
    }

    /** docs/28 §1: a notice the player read leaves the executor's state once, and the player keeps no copy of it. */
    @Test
    fun `a notice the player read drops exactly one live notice`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.step(SyncPlayUpdate(emptyList(), listOf(SyncPlayNotice.UserJoined("ana"), SyncPlayNotice.UserJoined("ana")), false))

        rig.viewModel.syncPlayNoticesShown()
        runCurrent()
        assertEquals(listOf<SyncPlayNotice>(SyncPlayNotice.UserJoined("ana")), rig.exec.state.value.notices)
        assertSame(rig.exec, rig.viewModel.state.value.syncPlay)
    }

    /** docs/28 §1: a subtitle pick the group refuses is shown once, and nothing reloads. */
    @Test
    fun `a refused subtitle pick in a group is shown once`() = groupTest(groupGateway(samplePlan(itemId = "item-g"))) { rig ->
        rig.loadAndLand("item-g")
        rig.player.fireTracksChanged(tracksOf(enAudio to true))
        runCurrent()

        rig.viewModel.chooseSubtitle(streamRowId(3))
        assertTrue(rig.viewModel.state.value.subtitlePickRefused)
        rig.viewModel.subtitlePickRefusedShown()
        assertFalse(rig.viewModel.state.value.subtitlePickRefused)
        assertTrue(rig.gateway.prepareTranscodeFallbackCalls.isEmpty())
    }

    /** docs/28 §5: every pick a group refuses, and only those. */
    @Test
    fun `a group refuses a stream row pick and any pick over a burn-in`() = runTest(timeout = TEST_TIMEOUT) {
        val group = PlayerMode.Group(SyncPlayExecutor(FakeCoreGateway(), 1uL, {}, backgroundScope, StandardTestDispatcher(testScheduler)), loadSeq = 1uL)
        val clean = samplePlan(playMethod = PlayMethodFfi.TRANSCODE)
        val burned = clean.copy(burnedSubtitleIndex = 2)
        val direct = samplePlan()
        // (mode, plan, stream row) to refused
        val cases = listOf(
            Triple(group, burned, true) to true,
            Triple(group, burned, false) to true,
            Triple(group, clean, true) to true,
            Triple(group, clean, false) to false,
            Triple(group, direct, false) to false,
            Triple(group, null, false) to false,
            Triple(PlayerMode.Solo, burned, true) to false,
            Triple(PlayerMode.Solo, burned, false) to false,
        )
        for ((input, refused) in cases) {
            val (mode, plan, streamRow) = input
            assertEquals("$mode $plan $streamRow", refused, groupRefusesSubtitlePick(mode, plan, streamRow))
        }
    }
}
