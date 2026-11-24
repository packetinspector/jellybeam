package tv.jellybeam.player

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaystateCommandFfi
import uniffi.jellybeam_core.ReportFactsFfi
import uniffi.jellybeam_core.ReportListFfi
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi

private fun listPlan(
    itemId: String,
    itemType: String = "Movie",
    seriesId: String? = null,
    audioStreams: List<AudioStreamFfi> = emptyList(),
    embeddedSubtitles: List<EmbeddedSubtitleFfi> = emptyList(),
    playMethod: PlayMethodFfi = PlayMethodFfi.DIRECT_PLAY,
    deliveredAudioIndex: Int? = null,
    playSessionId: String = "ps-$itemId",
    transcodeFallbackAllowed: Boolean = false,
) = PlaybackPlan(
    itemId = itemId,
    itemName = itemId,
    url = "http://example.test/Videos/$itemId/stream?static=true",
    mediaSourceId = "src-$itemId",
    playSessionId = playSessionId,
    startPositionTicks = 0L,
    runtimeTicks = 6_000_000_000L,
    container = "mkv",
    itemType = itemType,
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    seriesId = seriesId,
    trackMemorySubject = seriesId ?: itemId,
    externalSubtitles = emptyList(),
    embeddedSubtitles = embeddedSubtitles,
    burnedSubtitleIndex = null,
    audioStreams = audioStreams,
    deliveredAudioIndex = deliveredAudioIndex,
    playMethod = playMethod,
    transcodeReason = null,
    serverVerdict = null,
    transcodeFallbackAllowed = transcodeFallbackAllowed,
).withCoreRows()

/** docs/31 §4.1, §4.1.1 (Seam 3): the remote list and track hints inside the playback session owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelRemoteListTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val built = mutableListOf<PlaybackViewModel>()

    @After
    fun tearDown() {
        val store = ViewModelStore()
        built.forEachIndexed { i, vm -> store.put("vm-$i", vm) }
        store.clear()
    }

    private fun gateway(vararg plans: PlaybackPlan, settings: Settings = defaultTestSettings(), next: Map<String, String> = emptyMap()) = FakeCoreGateway(
        preparePlaybackResultsByItemId = plans.associate { it.itemId to Result.success(it) },
        nextEpisodeByItemId = next.mapValues { (_, id) -> testCard(id = id, itemType = "Episode") },
        settings = settings,
    ).apply { plans.forEach { cardsByItemId[it.itemId] = testCard(id = it.itemId, itemType = it.itemType) } }

    /** Every drop line the view model logged through the bridge, as `cmd why`. */
    private val dropped = mutableListOf<String>()

    private fun build(gateway: CoreGateway, player: PlaybackPlayer, itemId: String, queue: RemoteQueue? = null, tracks: TrackRequest? = null): PlaybackViewModel {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        return PlaybackViewModel(
            gateway,
            player,
            itemId,
            strings = ResourceUiStrings.default,
            reportScope = scope,
            settingsStore = SettingsStore(gateway, scope),
            monotonicClock = Clock { 0L },
            remoteQueue = queue,
            trackRequest = tracks,
            remoteDropped = { cmd, why -> dropped += "$cmd ${why.tag}" },
        ).also { built += it }
    }

    private inline fun TestScope.session(vm: PlaybackViewModel, body: () -> Unit) {
        try {
            runCurrent()
            body()
        } finally {
            vm.stopPlaybackOnce()
        }
    }

    /** The list the newest immediate report states, as (ids, at). */
    private fun FakeCoreGateway.reportedList(): ReportListFfi? = (reportNowFacts.last() as ReportFactsFfi.Video).list

    /** The list [session]'s newest facts state; a new session under no list hears it this way, not in an immediate report. */
    private fun FakeCoreGateway.factsList(session: String): ReportListFfi? = (reportFactsCalls.last { it.first == session }.second as ReportFactsFfi.Video).list

    @Test
    fun `next and previous walk the remote list, then the episode neighbour takes over and ends the list`() = runTest(timeout = 10.seconds) {
        val plans = arrayOf(listPlan("e1", "Episode", "s"), listPlan("e2", "Episode", "s"), listPlan("e9", "Episode", "s"))
        val gateway = gateway(*plans, next = mapOf("e1" to "eX", "e2" to "e9"))
        val vm = build(gateway, FakePlaybackPlayer(), "e1", RemoteQueue(listOf("e1", "e2"), 0))
        session(vm) {
            assertTrue("the list's next shows the next button", vm.state.value.hasNextEpisode)
            assertFalse(vm.state.value.hasPreviousEpisode)

            vm.playNextEpisode()
            runCurrent()
            vm.playPrevious()
            runCurrent()
            vm.playNextEpisode()
            runCurrent()
            assertEquals(ReportListFfi(listOf("e1", "e2"), 1u), gateway.reportedList())
            vm.playNextEpisode()
            runCurrent()

            assertEquals("the list wins over e1's own next episode", listOf("e1", "e2", "e1", "e2", "e9"), gateway.preparePlaybackCalls)
            assertNull("the neighbour ends the list", gateway.factsList("ps-e9"))
        }
    }

    @Test
    fun `PlayNext inserts after the playing item and PlayLast appends, each reported at once`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            assertEquals(null, vm.remote(VideoOp.Enqueue(listOf("c"), EnqueueMode.Next)))
            assertEquals(ReportListFfi(listOf("a", "c", "b"), 0u), gateway.reportedList())
            assertEquals(null, vm.remote(VideoOp.Enqueue(listOf("d", "e"), EnqueueMode.Last)))
            runCurrent()
            assertEquals(ReportListFfi(listOf("a", "c", "b", "d", "e"), 0u), gateway.reportedList())
        }
    }

    /** docs/31 §4.1 (approved Q4). */
    @Test
    fun `PlayNext over a video with no list starts one with the playing item first`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a")
        session(vm) {
            assertFalse(vm.state.value.hasNextEpisode)

            vm.remote(VideoOp.Enqueue(listOf("b"), EnqueueMode.Next))
            runCurrent()

            assertEquals(ReportListFfi(listOf("a", "b"), 0u), gateway.reportedList())
            assertTrue(vm.state.value.hasNextEpisode)
        }
    }

    /** docs/31 §4.1.1 (approved Q2). */
    @Test
    fun `at a natural end the next list item plays even with autoplay off`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"), settings = defaultTestSettings().copy(autoplayEnabled = false))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            player.positionTicks = 6_000_000_000L
            player.firePlaybackStateChanged(Player.STATE_ENDED)
            runCurrent()

            assertEquals(listOf("a", "b"), gateway.preparePlaybackCalls)
            assertEquals(ReportListFfi(listOf("a", "b"), 1u), gateway.reportedList())
        }
    }

    @Test
    fun `at the list's last item a natural end finishes as any other item`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"), settings = defaultTestSettings().copy(autoplayEnabled = false))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "b", RemoteQueue(listOf("a", "b"), 1))
        session(vm) {
            player.positionTicks = 6_000_000_000L
            player.firePlaybackStateChanged(Player.STATE_ENDED)
            runCurrent()

            assertEquals(listOf("b"), gateway.preparePlaybackCalls)
            assertEquals(PlaybackEvent.Finish, vm.events.replayCache.firstOrNull())
        }
    }

    @Test
    fun `an in-app play of another item clears the list`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"), listPlan("x"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            vm.replaceItem("x")
            runCurrent()

            assertNull(gateway.factsList("ps-x"))
            assertFalse(vm.state.value.hasNextEpisode)
            vm.playNextEpisode()
            runCurrent()
            assertEquals(listOf("a", "x"), gateway.preparePlaybackCalls)
        }
    }

    /** docs/31 Seam 3 L3: a request without a list ends it, even one for the item it plays. */
    @Test
    fun `an in-app restart of the playing item clears the list`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            vm.replaceItem("a", startFromBeginning = true)
            runCurrent()

            assertNull(gateway.factsList("ps-a"))
            assertFalse(vm.state.value.hasNextEpisode)
        }
    }

    @Test
    fun `a same-item remote play installs its list without a reload`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a")
        session(vm) {
            vm.remoteExtras(RemoteQueue(listOf("a", "b"), 0), null)
            runCurrent()

            assertEquals(listOf("a"), gateway.preparePlaybackCalls)
            assertEquals(ReportListFfi(listOf("a", "b"), 0u), gateway.reportedList())
            assertTrue(vm.state.value.hasNextEpisode)
        }
    }

    /** docs/31 §4.1.1: a reload that keeps the same list keeps its edges, so the Next button stays. */
    @Test
    fun `a reload of the playing item with the same list keeps the next button`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            assertTrue(vm.state.value.hasNextEpisode)

            vm.replaceItem("a", startMs = 60_000L, queue = RemoteQueue(listOf("a", "b"), 0))
            runCurrent()

            assertEquals(listOf("a", "a"), gateway.preparePlaybackCalls)
            assertTrue("the list's next still shows", vm.state.value.hasNextEpisode)
        }
    }

    /** docs/12 §8: the edges belong to the session on screen, so a movie after an episode drops the episode's Next. */
    @Test
    fun `a movie after an episode with a next drops the next button`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("e1", "Episode", "s"), listPlan("m"), next = mapOf("e1" to "e2"))
        val vm = build(gateway, FakePlaybackPlayer(), "e1")
        session(vm) {
            assertTrue(vm.state.value.hasNextEpisode)

            vm.replaceItem("m")
            runCurrent()

            assertEquals(listOf("e1", "m"), gateway.preparePlaybackCalls)
            assertFalse(vm.state.value.hasNextEpisode)
        }
    }

    /** docs/31 §4.1: a controller's hint is a first pick for this play, never the viewer's remembered choice. */
    @Test
    fun `a remote play's track hints are not remembered`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a", audioStreams = streams))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", tracks = TrackRequest(null, audioStreamIndex = 2, subtitleStreamIndex = -1))
        session(vm) {
            player.fireTracksChanged(tracks())
            runCurrent()

            assertTrue("the hint was applied", player.applyTrackDecisionCalls.any { it.first.subtitleAction == SubtitleActionFfi.OFF })
            assertEquals(emptyList<Any>(), gateway.rememberTrackMemoryCalls)
        }
    }

    /** docs/31 §7: an in-app change rides the next report; only a remote command reports at once. */
    @Test
    fun `an automatic track change updates the facts without an immediate report`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a", audioStreams = streams))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a")
        session(vm) {
            player.fireTracksChanged(tracks())
            runCurrent()

            assertTrue("the plan's tracks set the facts", gateway.reportFactsCalls.isNotEmpty())
            assertEquals(emptyList<String>(), gateway.reportNowLog)
            vm.remote(VideoOp.Playstate(PlaystateCommandFfi.Pause))
            runCurrent()
            assertEquals(listOf("now ps-a@0"), gateway.reportNowLog)
        }
    }

    /** docs/31 §7 (B1): a remote Seek's immediate report queues behind the tick's older position, so the server never ends on the stale one. */
    @Test
    fun `a remote seek's report lands after the position report sent before it`() = runTest(timeout = 10.seconds) {
        val gateway = HeldGateway(gateway(listPlan("a")))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a")
        session(vm) {
            gateway.positionGate = CompletableDeferred()
            player.isPlaying = true
            player.positionTicks = T_OLD
            advanceTimeBy(1_000L)
            runCurrent()
            // The fake's seek never moves the playhead; the real one lands where it was asked.
            player.positionTicks = T_NEW
            vm.remote(VideoOp.Playstate(PlaystateCommandFfi.Seek(T_NEW)))
            runCurrent()
            gateway.positionGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf("position ps-a@$T_OLD", "now ps-a@$T_NEW at null"), gateway.log.filter { it.startsWith("position") || it.startsWith("now") })
        }
    }

    /** docs/31 §7, §4.1.1 (I8): a list step's new session hears its list once, at its new position, never the old one. */
    @Test
    fun `a list step's new session reports once at its new position`() = runTest(timeout = 10.seconds) {
        val gateway = HeldGateway(gateway(listPlan("a"), listPlan("b")))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            gateway.log.clear()
            gateway.factsGate = CompletableDeferred()
            vm.playNextEpisode()
            runCurrent()
            gateway.factsGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf("now ps-b@0 at 1"), gateway.log.filter { it.startsWith("facts") || it.startsWith("now") })
        }
    }

    /** Three episodes of one series, e1 then e2, plus a movie m a remote can enqueue. */
    private fun episodes(decisions: List<StillWatchingDecision> = emptyList()) = gateway(
        listPlan("e1", "Episode", "s"),
        listPlan("e2", "Episode", "s"),
        listPlan("m"),
        next = mapOf("e1" to "e2"),
    ).apply { noteEpisodeFinishedDecisions.addAll(decisions) }

    /** One tick inside e1's next-up zone: 30 s before its 600 s end, at 590 s. */
    private fun TestScope.tickInZone(player: FakePlaybackPlayer) {
        player.positionTicks = 5_900_000_000L
        advanceTimeBy(1_000L)
        runCurrent()
    }

    private fun TestScope.naturalEnd(player: FakePlaybackPlayer) {
        player.positionTicks = 6_000_000_000L
        player.firePlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()
    }

    /** Table V1 (#6): a PlayNext over a counting next-up card replaces it, and the end plays the list's item. */
    @Test
    fun `an enqueue over a counting next-up card clears it and the end plays the enqueued item`() = runTest(timeout = 10.seconds) {
        val gateway = episodes()
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "e1")
        session(vm) {
            tickInZone(player)
            assertNotNull("the card counts down", vm.state.value.nextUp)

            assertNull(vm.remote(VideoOp.Enqueue(listOf("m"), EnqueueMode.Next)))
            assertNull("the list's next replaces the card", vm.state.value.nextUp)
            assertTrue(vm.state.value.hasNextEpisode)
            naturalEnd(player)

            assertEquals(listOf("e1", "m"), gateway.preparePlaybackCalls)
            assertEquals(ReportListFfi(listOf("e1", "m"), 1u), gateway.reportedList())
        }
    }

    /** Table V2 (#6): a decision that lands after a PlayNext shows no card, and the end plays the list's item. */
    @Test
    fun `a still-watching decision landing after an enqueue shows no card`() = runTest(timeout = 10.seconds) {
        val fake = episodes()
        val gateway = HeldGateway(fake)
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "e1")
        session(vm) {
            gateway.decisionGate = CompletableDeferred()
            tickInZone(player)
            vm.remote(VideoOp.Enqueue(listOf("m"), EnqueueMode.Next))
            gateway.decisionGate!!.complete(Unit)
            runCurrent()

            assertNull("the decision is moot under a list", vm.state.value.nextUp)
            assertNull(vm.state.value.stillWatching)
            naturalEnd(player)
            assertEquals(listOf("e1", "m"), fake.preparePlaybackCalls)
        }
    }

    /** Table V2b (#6): the file ended while deciding, then a PlayNext came; the decision plays the list's item. */
    @Test
    fun `an end while deciding then an enqueue plays the enqueued item once the decision lands`() = runTest(timeout = 10.seconds) {
        val fake = episodes()
        val gateway = HeldGateway(fake)
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "e1")
        session(vm) {
            gateway.decisionGate = CompletableDeferred()
            tickInZone(player)
            naturalEnd(player)
            vm.remote(VideoOp.Enqueue(listOf("m"), EnqueueMode.Next))
            gateway.decisionGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf("e1", "m"), fake.preparePlaybackCalls)
        }
    }

    /** Table 4B Pn2 x natural end: a list's next plays at the end before any pending decision is awaited. */
    @Test
    fun `a natural end while deciding under a list plays the list's item at once`() = runTest(timeout = 10.seconds) {
        val fake = episodes()
        val gateway = HeldGateway(fake)
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "e1")
        session(vm) {
            gateway.decisionGate = CompletableDeferred()
            tickInZone(player)
            vm.remote(VideoOp.Enqueue(listOf("m"), EnqueueMode.Next))
            naturalEnd(player)

            assertEquals("the decision is still out", listOf("e1", "m"), fake.preparePlaybackCalls)
            gateway.decisionGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("e1", "m"), fake.preparePlaybackCalls)
        }
    }

    /** Table V3 (#8): a same-item remote Play without a list ends the list, so the end finishes. */
    @Test
    fun `a same-item remote play without a list ends the list`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            vm.remoteExtras(null, null)
            runCurrent()

            assertNull(gateway.reportedList())
            assertFalse(vm.state.value.hasNextEpisode)
            naturalEnd(player)
            assertEquals(listOf("a"), gateway.preparePlaybackCalls)
            assertEquals(PlaybackEvent.Finish, vm.events.replayCache.firstOrNull())
        }
    }

    /** Table V4 (#10): a reload with the same list keeps Next and plays the list's item by id, with no card lookup. */
    @Test
    fun `a reload with the same list keeps next and steps by id without a card lookup`() = runTest(timeout = 10.seconds) {
        val fake = gateway(listPlan("a"), listPlan("b"))
        val gateway = HeldGateway(fake)
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            vm.replaceItem("a", startMs = 60_000L, queue = RemoteQueue(listOf("a", "b"), 0))
            runCurrent()
            assertTrue(vm.state.value.hasNextEpisode)

            vm.playNextEpisode()
            runCurrent()

            assertEquals(listOf("a", "a", "b"), fake.preparePlaybackCalls)
            assertEquals("the list plays by id", 0, gateway.cardsByIdsCalls)
        }
    }

    /** Table I8 (device row R-L4): a reload under an unchanged list is a new session, which reports its list at once rather than on the next tick. */
    @Test
    fun `a reload under the same list reports the new session's list at once`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("a"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            val before = gateway.reportNowLog.size
            vm.replaceItem("a", startMs = 60_000L, queue = RemoteQueue(listOf("a", "b"), 0))
            runCurrent()

            assertEquals("one immediate report for the reloaded session", before + 1, gateway.reportNowLog.size)
            assertEquals(0u, gateway.reportedList()?.at)
        }
    }

    /** docs/31 §4.1.1 (I8): a session a fallback installs under a playing list is new to the server, so it hears its list at once, not on the next tick. */
    @Test
    fun `a renegotiated session under a playing list reports its list at once`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a", audioStreams = streams, transcodeFallbackAllowed = true)).apply {
            prepareTranscodeFallbackResult = Result.success(listPlan("a", audioStreams = streams, playMethod = PlayMethodFfi.TRANSCODE, playSessionId = "landed"))
        }
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            player.fireTracksChanged(tracks())
            runCurrent()
            player.fireError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            runCurrent()

            assertEquals(listOf("landed"), gateway.commitTranscodeCalls)
            assertEquals("now landed", gateway.reportNowLog.last().substringBefore('@'))
            assertEquals(ReportListFfi(listOf("a", "b"), 0u), gateway.reportedList())
        }
    }

    /** docs/31 §7: an in-app play over a list ends it; the new session's empty list rides its facts, with no immediate report. */
    @Test
    fun `an in-app play over a list reports nothing at once`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            val before = gateway.reportNowLog.size
            vm.replaceItem("b")
            runCurrent()

            assertEquals(emptyList<String>(), gateway.reportNowLog.drop(before))
            val (session, facts) = gateway.reportFactsCalls.last()
            assertEquals("ps-b", session)
            assertNull((facts as ReportFactsFfi.Video).list)
        }
    }

    /** docs/31 §4.1, §4.1.1: a remote Play's new list over a playing one reaches the new session in one immediate report. */
    @Test
    fun `a remote play with a new list over a playing list reports once with the new list`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("c"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            val before = gateway.reportNowLog.size
            vm.replaceItem("c", queue = RemoteQueue(listOf("c", "d"), 0))
            runCurrent()

            assertEquals(listOf("now ps-c@0"), gateway.reportNowLog.drop(before))
            assertEquals(ReportListFfi(listOf("c", "d"), 0u), gateway.reportedList())
        }
    }

    /** docs/31 §7: with no list before or after, a new session's facts ride the next report. */
    @Test
    fun `a plain play with no list reports nothing at once`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a")
        session(vm) {
            vm.replaceItem("b")
            runCurrent()

            assertEquals(emptyList<String>(), gateway.reportNowLog)
        }
    }

    /** docs/31 §4.1.1: the start's list step precedes the load, so a track callback the load raises never reports the ended list. */
    @Test
    fun `a track callback during load doesn't report the previous list`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, LoadAnnouncesTracksPlayer(FakePlaybackPlayer()), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            val before = gateway.reportNowLog.size
            vm.replaceItem("b")
            runCurrent()

            assertEquals(emptyList<String>(), gateway.reportNowLog.drop(before))
        }
    }

    /** Table V5 (#4): a group's queue decides what follows: entering ends the list, and a remote list or enqueue is refused with one drop line. */
    @Test
    fun `in a group a remote list is refused without a report and an enqueue is not applicable`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"), listPlan("b"))
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            val exec = SyncPlayExecutor(gateway, gateway.accountEpoch, {}, backgroundScope, StandardTestDispatcher(testScheduler)) { 0L }
            vm.mode = PlayerMode.Group(exec, loadSeq = 0u)
            assertNull("entering the group ends the list", gateway.reportedList())
            val reports = gateway.reportNowLog.size

            vm.remoteExtras(RemoteQueue(listOf("a", "b"), 0), null)
            runCurrent()

            assertEquals("a refused list reports nothing", reports, gateway.reportNowLog.size)
            assertFalse(vm.state.value.hasNextEpisode)
            assertEquals(listOf("play_video not_applicable"), dropped)
            assertEquals(DropWhy.NotApplicable, vm.remote(VideoOp.Enqueue(listOf("b"), EnqueueMode.Next)))
            vm.mode = PlayerMode.Solo
        }
    }

    /** Table v2: a step with no successor is not applicable, so the remote path logs it rather than dropping it silently. */
    @Test
    fun `a step with nothing to play is not applicable`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a"))
        val vm = build(gateway, FakePlaybackPlayer(), "a")
        session(vm) {
            assertFalse(vm.state.value.hasNextEpisode)
            assertFalse(vm.state.value.hasPreviousEpisode)
            assertEquals(DropWhy.NotApplicable, vm.playNextEpisode())
            assertEquals(DropWhy.NotApplicable, vm.playPrevious())
            assertEquals(listOf("a"), gateway.preparePlaybackCalls)
        }
    }

    /** Table V6 (approved Q1 a): a PlayNext under a still-watching card keeps it; Continue plays the list's item. */
    @Test
    fun `an enqueue under a still-watching card keeps it and continue plays the enqueued item`() = runTest(timeout = 10.seconds) {
        val gateway = episodes(listOf(StillWatchingDecision.ASK_STILL_WATCHING))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "e1")
        session(vm) {
            tickInZone(player)
            assertNotNull(vm.state.value.stillWatching)

            vm.remote(VideoOp.Enqueue(listOf("m"), EnqueueMode.Next))
            assertNotNull("the card still asks", vm.state.value.stillWatching)
            vm.stillWatchingContinue()
            runCurrent()

            assertEquals(listOf("e1", "m"), gateway.preparePlaybackCalls)
        }
    }

    /** Table V7 (F15): a list item that no longer plays finishes as an in-app Play of it would. */
    @Test
    fun `a list item that fails to prepare finishes with its message`() = runTest(timeout = 10.seconds) {
        val gateway = FakeCoreGateway(
            preparePlaybackResultsByItemId = mapOf("a" to Result.success(listPlan("a")), "b" to Result.failure(CoreException.Api(detail = "gone"))),
        )
        val vm = build(gateway, FakePlaybackPlayer(), "a", RemoteQueue(listOf("a", "b"), 0))
        session(vm) {
            vm.playNextEpisode()
            runCurrent()

            assertEquals(listOf("a", "b"), gateway.preparePlaybackCalls)
            assertTrue(vm.events.replayCache.single() is PlaybackEvent.FinishWithMessage)
        }
    }

    private val streams = listOf(
        AudioStreamFfi(index = 1, displayTitle = "English", title = null, language = "eng", codec = "aac", channels = 2, isDefault = true),
        AudioStreamFfi(index = 2, displayTitle = "Japanese", title = null, language = "jpn", codec = "aac", channels = 2, isDefault = false),
    )

    private fun tracks(): Tracks {
        fun group(format: Format, selected: Boolean) = Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        val en = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLanguage("eng").build()
        val ja = Format.Builder().setSampleMimeType("audio/mp4a-latm").setLanguage("jpn").build()
        return Tracks(listOf(group(en, true), group(ja, false)))
    }

    /** docs/31 §4.1: the version reaches the core; the streams are picked as the viewer's first picks. */
    @Test
    fun `a remote play's audio and subtitle hints become the first picks and its version is asked for`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a", audioStreams = streams))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", tracks = TrackRequest("src-9", audioStreamIndex = 2, subtitleStreamIndex = -1))
        session(vm) {
            val tracks = tracks()
            player.fireTracksChanged(tracks)
            runCurrent()

            assertEquals(listOf<String?>("src-9"), gateway.preparePlaybackMediaSources)
            val japanese = TrackMapping.toTrackInfos(tracks)[1].id // the second group, Japanese
            val decisions = player.applyTrackDecisionCalls.map { it.first }
            assertEquals("the hinted audio is the first audio applied", japanese, decisions.firstNotNullOf { it.audioTrackId })
            assertTrue("subtitle -1 turns text off", decisions.any { it.subtitleAction == SubtitleActionFfi.OFF })
        }
    }

    /** docs/31 §4.1: the hint's streams reach the first negotiation, so a transcode that delivers them is never renegotiated for them. */
    @Test
    fun `a remote play's streams ride the first negotiation and a transcode delivering them plays on`() = runTest(timeout = 10.seconds) {
        val gateway = gateway(listPlan("a", audioStreams = streams, playMethod = PlayMethodFfi.TRANSCODE, deliveredAudioIndex = 2))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", tracks = TrackRequest(null, audioStreamIndex = 2, subtitleStreamIndex = null))
        session(vm) {
            player.fireTracksChanged(tracks())
            runCurrent()

            assertEquals(listOf<Pair<Int?, Int?>>(2 to null), gateway.preparePlaybackStreams)
            assertEquals(emptyList<Any>(), gateway.prepareTranscodeFallbackCalls)
        }
    }

    /** docs/31 §4.1: Direct Play is the default; a stream Media3 doesn't list is no pick, never a negotiation. */
    @Test
    fun `a hint naming a stream only a transcode could show forces no transcode`() = runTest(timeout = 10.seconds) {
        val image = EmbeddedSubtitleFfi(index = 5, language = "eng", title = "PGS", forced = false, default = false, codec = "pgssub", displayTitle = "PGS", delivery = SubtitleDeliveryFfi.EMBED)
        val gateway = gateway(listPlan("a", audioStreams = streams, embeddedSubtitles = listOf(image)))
        val player = FakePlaybackPlayer()
        val vm = build(gateway, player, "a", tracks = TrackRequest(null, audioStreamIndex = null, subtitleStreamIndex = 5))
        session(vm) {
            player.fireTracksChanged(tracks())
            runCurrent()

            assertEquals(emptyList<Any>(), gateway.prepareTranscodeFallbackCalls)
            assertEquals(listOf("a"), gateway.preparePlaybackCalls)
            assertFalse(vm.state.value.trackPickRefused)
        }
    }

    private companion object {
        const val T_OLD = 100_000_000L
        const val T_NEW = 900_000_000L
    }
}

/** As ExoPlayer, whose media item change flushes its listener events, an emptied track list among them, before [load] returns. */
private class LoadAnnouncesTracksPlayer(private val fake: FakePlaybackPlayer) : PlaybackPlayer by fake {
    override fun load(plan: PlaybackPlan, tolerateMislabeledLevels: Boolean, audioDecoderPreferences: AudioDecoderPreferences, fullAssStyling: Boolean, start: LoadStart) {
        fake.load(plan, tolerateMislabeledLevels, audioDecoderPreferences, fullAssStyling, start)
        fake.fireTracksChanged(Tracks.EMPTY)
    }
}

/** Every report the view model sends, in one log as it lands; a gate holds a position report, a facts report or the still-watching decision until a test releases it. */
private class HeldGateway(private val fake: FakeCoreGateway) : CoreGateway by fake {
    val log = mutableListOf<String>()
    var positionGate: CompletableDeferred<Unit>? = null
    var factsGate: CompletableDeferred<Unit>? = null
    var decisionGate: CompletableDeferred<Unit>? = null
    var cardsByIdsCalls = 0

    override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision {
        decisionGate?.await()
        return fake.noteEpisodeFinished(nowMs)
    }

    override suspend fun cardsByIds(itemIds: List<String>): List<Card> {
        cardsByIdsCalls++
        return fake.cardsByIds(itemIds)
    }

    override suspend fun reportPosition(playSessionId: String, ticks: Long) {
        positionGate?.await()
        log += "position $playSessionId@$ticks"
    }

    override suspend fun reportFacts(playSessionId: String, facts: ReportFactsFfi) {
        factsGate?.await()
        log += "facts $playSessionId at ${listAt(facts)}"
    }

    override suspend fun reportNow(playSessionId: String, ticks: Long, facts: ReportFactsFfi) {
        log += "now $playSessionId@$ticks at ${listAt(facts)}"
    }

    override suspend fun stopPlayback(playSessionId: String, positionTicks: Long) {
        log += "stop $playSessionId"
        fake.stopPlayback(playSessionId, positionTicks)
    }

    private fun listAt(facts: ReportFactsFfi) = (facts as ReportFactsFfi.Video).list?.at
}
