package tv.jellybeam.player.music

import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.player.music.MusicFlow.Net
import tv.jellybeam.player.music.MusicFlow.Pending
import tv.jellybeam.player.music.MusicFlow.Phase
import tv.jellybeam.player.music.MusicFlow.Refusal
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AudioEnd
import uniffi.jellybeam_core.AudioStream
import uniffi.jellybeam_core.AudioTrackPlan
import uniffi.jellybeam_core.PlaybackRequest

/** Music brief §1.3: the owner runs the reducer's effects in order against the service, the video close and the core's reports. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaybackOwnerTest {
    private val gateway = FakeCoreGateway().apply {
        accountEpoch = E1
        albumTracksByAlbum = mapOf(ALBUM to listOf(song("a"), song("b"), song("c")))
    }
    private val port = FakePort()
    private val host = FakeHost()
    private val log = mutableListOf<String>()
    private var holdsChanges = 0
    private var videoGate: CompletableDeferred<Unit>? = null

    private fun TestScope.owner(core: CoreGateway = gateway) = MusicPlaybackOwner(
        gateway = core,
        parent = backgroundScope,
        host = host,
        closeVideo = {
            log += "close video"
            videoGate?.await()
        },
        holdsPlaybackChanged = { holdsChanges++ },
        main = UnconfinedTestDispatcher(testScheduler),
    )

    /** Plays [ALBUM] from [start] and attaches the service the owner asked for; returns its instance id. */
    private fun MusicPlaybackOwner.playing(start: Int = 0): Long {
        play(MusicSource.Album(ALBUM), start)
        val g = attach(port)
        send(MusicEvent.ServiceReady(g, host.started.single()))
        return g
    }

    @Test
    fun `a track change ends the previous report in the next start, and Stop ends the last one`() = runTest {
        val owner = owner()
        val g = owner.playing()
        owner.send(MusicEvent.TrackChanged(1, "b", 30))
        port.position = 500
        owner.send(MusicEvent.Stop)
        assertEquals(listOf("start a", "start b ends ps-a@30", "stop ps-b@500"), gateway.reportLog)
        assertEquals(listOf("build 1 at 0", "release"), port.calls)
        assertEquals(Phase.Idle, owner.state.value.phase)
        assertNull(owner.now.value)
    }

    @Test
    fun `reports reach the core in order even while an earlier one is slow`() = runTest {
        val owner = owner()
        val held = CompletableDeferred<Unit>().also { gateway.startAudioTrackGate = it }
        owner.playing()
        owner.send(MusicEvent.TrackChanged(1, "b", 30))
        owner.send(MusicEvent.Stop)
        assertEquals(emptyList<String>(), gateway.reportLog)
        held.complete(Unit)
        assertEquals(listOf("start a", "start b ends ps-a@30", "stop ps-b@0"), gateway.reportLog)
    }

    @Test
    fun `a video start waits until the music reports before it reached the core`() = runTest {
        val owner = owner()
        val held = CompletableDeferred<Unit>().also { gateway.startAudioTrackGate = it }
        owner.playing()
        port.position = 70
        var launched = false
        owner.stopForVideo { launched = true }
        assertEquals(Phase.Idle, owner.state.value.phase)
        assertFalse("launched before the reports drained", launched)
        held.complete(Unit)
        assertTrue(launched)
        assertEquals(listOf("start a", "stop ps-a@70"), gateway.reportLog)
        assertEquals(listOf("build 1 at 0", "release"), port.calls)
    }

    @Test
    fun `the first plans are asked for on their own, the rest once they are in`() = runTest {
        val owner = owner()
        val held = CompletableDeferred<Unit>().also { gateway.prepareAudioQueueGate = it }
        val ahead = (1..20).map { "t$it" }
        owner.prefetch(E1, ahead)
        assertEquals(listOf(ahead.take(FIRST_PLANS)), gateway.prepareAudioQueueCalls)
        held.complete(Unit)
        assertEquals(listOf(ahead.take(FIRST_PLANS), ahead.drop(FIRST_PLANS)), gateway.prepareAudioQueueCalls)
        assertEquals("t20", owner.plan(E1, "t20").itemId)
    }

    @Test
    fun `a video start with no music playing launches at once`() = runTest {
        val owner = owner()
        var launched = false
        owner.stopForVideo { launched = true }
        assertTrue(launched)
    }

    @Test
    fun `an account change keeps music on the kept account and stops it once that account is removed`() = runTest {
        val owner = owner()
        owner.playing()
        gateway.setEpochs(E2, parked = E1)
        runCurrent()
        assertTrue(owner.state.value.phase is Phase.Active)
        assertEquals(listOf("build 1 at 0"), port.calls)
        // The current account stays; only the kept one goes.
        gateway.setEpochs(E2, parked = null)
        runCurrent()
        assertEquals(Phase.Idle, owner.state.value.phase)
        assertEquals(listOf("build 1 at 0", "release"), port.calls)
        assertEquals(listOf("start a", "stop ps-a@0"), gateway.reportLog)
    }

    @Test
    fun `the first track of a replaced queue reports the new queue`() = runTest {
        gateway.albumTracksByAlbum += "album-2" to listOf(song("x"), song("y"))
        val queues = mutableListOf<List<String>>()
        val core = object : CoreGateway by gateway {
            override suspend fun startAudioTrack(track: AudioTrackPlan, queueIds: List<String>, request: PlaybackRequest, ends: AudioEnd?): String {
                queues += queueIds
                return gateway.startAudioTrack(track, queueIds, request, ends)
            }
        }
        val owner = owner(core)
        val g = owner.playing()
        owner.publish(g, now())
        owner.play(MusicSource.Album("album-2"))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x", "y")), queues)
    }

    @Test
    fun `a playing video closes before the service starts`() = runTest {
        val owner = owner()
        val closing = CompletableDeferred<Unit>().also { videoGate = it }
        owner.play(MusicSource.Album(ALBUM))
        assertEquals(listOf("close video"), log)
        assertEquals(emptyList<Long>(), host.started)
        closing.complete(Unit)
        assertEquals(listOf(1L), host.started)
    }

    @Test
    fun `a stop while the video closes never starts the service`() = runTest {
        val owner = owner()
        val closing = CompletableDeferred<Unit>().also { videoGate = it }
        owner.play(MusicSource.Album(ALBUM))
        owner.send(MusicEvent.Stop)
        closing.complete(Unit)
        assertEquals(emptyList<Long>(), host.started)
        assertEquals(1, host.stops)
        assertEquals(Phase.Idle, owner.state.value.phase)
    }

    @Test
    fun `a refused service start goes back to Idle`() = runTest {
        host.refuses = true
        val owner = owner()
        owner.play(MusicSource.Album(ALBUM))
        assertEquals(Phase.Idle, owner.state.value.phase)
        assertFalse(owner.holdsPlayback)
        assertEquals(MusicNotice.StartFailed(1), owner.state.value.notice)
    }

    @Test
    fun `a service that never attaches fails its start once the wait runs out`() = runTest {
        val owner = owner()
        owner.play(MusicSource.Album(ALBUM))
        advanceTimeBy(SERVICE_START_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("still starting just before the timeout", owner.state.value.phase is Phase.Starting)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Phase.Idle, owner.state.value.phase)
        assertEquals(MusicNotice.StartFailed(1), owner.state.value.notice)
        val late = owner.attach(port)
        owner.send(MusicEvent.ServiceReady(late, 1))
        assertEquals("a late instance is released", listOf("release"), port.calls)
    }

    @Test
    fun `a service that attaches in time is never failed by the start wait`() = runTest {
        val owner = owner()
        owner.playing()
        advanceTimeBy(SERVICE_START_TIMEOUT_MS * 2)
        runCurrent()
        assertTrue(owner.state.value.phase is Phase.Active)
        assertNull(owner.state.value.notice)
    }

    @Test
    fun `a failed enqueue fetch leaves a notice until the bar shows it`() = runTest {
        gateway.albumTracksByAlbum += "unreadable" to null
        val owner = owner()
        owner.playing()
        val r = owner.enqueue(EnqueueMode.AddToQueue, MusicSource.Album("unreadable"), "Album U")
        assertEquals(MusicNotice.EnqueueFailed(r), owner.state.value.notice)
        assertEquals(listOf("build 1 at 0"), port.calls)
        owner.noticeShown(MusicNotice.EnqueueFailed(r + 1))
        assertEquals("a stale shown keeps the notice", MusicNotice.EnqueueFailed(r), owner.state.value.notice)
        owner.noticeShown(MusicNotice.EnqueueFailed(r))
        assertNull(owner.state.value.notice)
    }

    @Test
    fun `position and pause reports reach the playing track's session in order with its start and end`() = runTest {
        val owner = owner()
        val g = owner.playing()
        owner.publish(g, now())
        port.position = 10
        advanceTimeBy(POSITION_REPORT_MS)
        runCurrent()
        val held = CompletableDeferred<Unit>().also { gateway.startAudioTrackGate = it }
        owner.send(MusicEvent.TrackChanged(1, "b", 30))
        owner.publish(g, now().copy(isPlaying = false, paused = true))
        port.position = 20
        advanceTimeBy(POSITION_REPORT_MS)
        runCurrent()
        held.complete(Unit)
        owner.publish(g, now())
        advanceTimeBy(POSITION_REPORT_MS)
        runCurrent()
        owner.send(MusicEvent.Stop)
        assertEquals(
            listOf("start a", "position ps-a@10", "start b ends ps-a@30", "paused ps-b true", "paused ps-b false", "position ps-b@20", "stop ps-b@20"),
            gateway.reportLog,
        )
    }

    @Test
    fun `no position is reported while paused, buffering or after the session ends`() = runTest {
        val owner = owner()
        val g = owner.playing()
        owner.publish(g, now().copy(isPlaying = false))
        advanceTimeBy(POSITION_REPORT_MS * 3)
        runCurrent()
        owner.send(MusicEvent.Stop)
        advanceTimeBy(POSITION_REPORT_MS * 3)
        runCurrent()
        assertEquals("a buffering stall is no pause", listOf("start a", "stop ps-a@0"), gateway.reportLog)
    }

    @Test
    fun `normalisation turns a track's gain into a volume, never above full`() {
        assertEquals(1f, normalizationVolume(enabled = false, trackGainDb = -6.0, albumGainDb = null), 0f)
        assertEquals(1f, normalizationVolume(enabled = true, trackGainDb = null, albumGainDb = null), 0f)
        assertEquals("the song's own gain first", 0.5012f, normalizationVolume(enabled = true, trackGainDb = -6.0, albumGainDb = -20.0), 0.0001f)
        assertEquals("else its album's", 0.1f, normalizationVolume(enabled = true, trackGainDb = null, albumGainDb = -20.0), 0.0001f)
        assertEquals("a boost is capped at full volume", 1f, normalizationVolume(enabled = true, trackGainDb = 4.5, albumGainDb = null), 0f)
    }

    @Test
    fun `the update owner hears each change of whether music holds playback`() = runTest {
        val owner = owner()
        owner.play(MusicSource.Album(ALBUM))
        assertEquals(1, holdsChanges)
        assertTrue(owner.holdsPlayback)
        owner.send(MusicEvent.Stop)
        assertEquals(2, holdsChanges)
        assertFalse(owner.holdsPlayback)
    }

    @Test
    fun `nothing of music is built before the first music request`() {
        assertNull(AppGraph.musicOwnerIfStarted())
        val reads = mutableListOf<Any>()
        androidx.compose.runtime.snapshots.Snapshot.observe(readObserver = { reads += it }) { assertNull(AppGraph.musicStarted.value) }
        assertEquals("Compose reads the owner as snapshot state, so no collector runs while it is absent", 1, reads.size)
    }

    @Test
    fun `a source that can't be read or is empty is refused`() = runTest {
        gateway.albumTracksByAlbum = mapOf(ALBUM to null, "empty" to emptyList())
        val owner = owner()
        val failed = owner.play(MusicSource.Album(ALBUM))
        assertEquals(Pending.Refused(failed, Refusal.Failed, MusicSource.Album(ALBUM)), owner.state.value.pending)
        val empty = owner.play(MusicSource.Album("empty"))
        assertEquals(Pending.Refused(empty, Refusal.Empty, MusicSource.Album("empty")), owner.state.value.pending)
        assertEquals(emptyList<Long>(), host.started)
    }

    @Test
    fun `play from a row starts that row, kept inside the result`() = runTest {
        val owner = owner()
        owner.playing(start = 1)
        assertEquals(listOf("start b"), gateway.reportLog)
        port.position = 55
        owner.play(MusicSource.Album(ALBUM), startIndex = 9)
        assertEquals(listOf("build 1 at 1", "replace 2 at 2"), port.calls)
        assertEquals("the replaced track ends where it stood, not where the new queue starts", listOf("start b", "start c ends ps-b@55"), gateway.reportLog)
    }

    @Test
    fun `a song favourited while playing shows the new heart when its track comes back`() = runTest {
        val owner = owner()
        owner.playing()
        assertEquals(false, owner.card("a")?.isFavorite)
        owner.favoriteChanged("a", true)
        owner.send(MusicEvent.TrackChanged(1, "b", 30))
        owner.send(MusicEvent.TrackChanged(1, "a", 30))
        assertEquals(true, owner.card("a")?.isFavorite)
        assertEquals("only that song", false, owner.card("b")?.isFavorite)
        owner.favoriteChanged("a", false)
        owner.favoriteChanged("not-queued", true)
        assertEquals(false, owner.card("a")?.isFavorite)
        assertNull(owner.card("not-queued"))
    }

    @Test
    fun `a destroyed service ends its track where it stopped and releases nothing`() = runTest {
        val owner = owner()
        val g = owner.playing()
        owner.detached(g, 40)
        assertEquals(listOf("start a", "stop ps-a@40"), gateway.reportLog)
        assertEquals(listOf("build 1 at 0"), port.calls)
        assertEquals(Phase.Idle, owner.state.value.phase)
    }

    @Test
    fun `an unplayable track is never started and still ends the one before it`() = runTest {
        val playable = gateway.audioPlan
        gateway.audioPlan = { if (it == "b") AudioTrackPlan(it, it, "ps-b", AudioStream.Unplayable("gone")) else playable(it) }
        val owner = owner()
        owner.playing()
        owner.send(MusicEvent.TrackChanged(1, "b", 30))
        owner.send(MusicEvent.Stop)
        assertEquals(listOf("start a", "stop ps-a@30"), gateway.reportLog)
    }

    @Test
    fun `Now Playing shows only the playing instance, with the network state`() = runTest {
        val owner = owner()
        val g = owner.playing()
        owner.publish(g + 1, now())
        assertNull(owner.now.value)
        owner.publish(g, now())
        assertEquals(now(), owner.now.value)
        owner.send(MusicEvent.NetworkLost(1))
        assertEquals(now().copy(net = Net.Lost), owner.now.value)
        assertEquals(listOf("build 1 at 0", "pause"), port.calls)
    }

    @Test
    fun `an enqueue fetches in the session and inserts into the playing queue`() = runTest {
        val owner = owner()
        owner.playing()
        val r = owner.enqueue(EnqueueMode.PlayNext, MusicSource.Single(song("u")), "Song U")
        assertEquals(listOf("build 1 at 0", "insert PlayNext [u]"), port.calls)
        assertEquals(MusicNotice.Queued(r, EnqueueMode.PlayNext, "Song U"), owner.state.value.notice)
    }

    @Test
    fun `an enqueue with nothing playing plays the item and confirms nothing`() = runTest {
        val owner = owner()
        owner.playlistAdded("List A", again = false)
        assertNull("no bar, so no confirmation", owner.state.value.notice)
        owner.enqueue(EnqueueMode.AddToQueue, MusicSource.Single(song("u")), "Song U")
        assertTrue(owner.state.value.holdsPlayback)
        assertNull(owner.state.value.notice)
    }

    @Test
    fun `a broken connection waits for the network, anything else skips the track`() {
        assertTrue(waitsForNetwork(IOException(ConnectException("refused"))))
        assertTrue(waitsForNetwork(IOException(PlanUnavailable(RuntimeException("core")))))
        assertFalse(waitsForNetwork(UnplayableTrack()))
        assertFalse(waitsForNetwork(IOException("bad data")))
    }

    @Test
    fun `a load that failed on an HLS transcode is told apart from any other failure`() {
        val hls = HlsTranscode("a", "http://example.test/a/master.m3u8")
        assertEquals(hls, hlsTranscodeOf(IOException(hls)))
        assertNull(hlsTranscodeOf(IOException(PlanUnavailable(null))))
        assertNull(hlsTranscodeOf(UnplayableTrack()))
    }

    @Test
    fun `a new track report starts on a move to the next entry or a seek to another`() {
        assertTrue(startsNextTrack(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 2, 3))
        assertTrue("repeat one", startsNextTrack(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 2, 2))
        assertTrue(startsNextTrack(Player.DISCONTINUITY_REASON_SEEK, 2, 5))
        assertFalse("a seek inside the track", startsNextTrack(Player.DISCONTINUITY_REASON_SEEK, 2, 2))
        assertFalse("a queue replaced", startsNextTrack(Player.DISCONTINUITY_REASON_REMOVE, 2, 0))
    }

    @Test
    fun `an entry's origin round-trips through its tag`() {
        QueueOrigin.entries.forEach { assertEquals(it, originOf(it.tag)) }
        assertEquals(QueueOrigin.Source, originOf(null))
    }

    private fun now() = MusicNow(listOf(QueueEntry("a", QueueOrigin.Source)), listOf(0), 0, true, 0, 1_000, false, RepeatMode.Off)

    private class FakePort : MusicPlayerPort {
        val calls = mutableListOf<String>()
        var position = 0L
        override fun build(session: MusicSession) { calls += "build ${session.sid} at ${session.start}" }
        override fun replaceQueue(session: MusicSession) {
            calls += "replace ${session.sid} at ${session.start}"
            position = 0
        }
        override fun insert(mode: EnqueueMode, items: List<String>) { calls += "insert $mode $items" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun retryPrepare() { calls += "retry" }
        override fun skipNext() { calls += "skip" }
        override fun apply(op: TransportOp) { calls += "apply $op" }
        override fun positionTicks(): Long = position
        override fun release() { calls += "release" }
    }

    private class FakeHost : MusicServiceHost {
        val started = mutableListOf<Long>()
        var stops = 0
        var refuses = false
        override fun start(ticket: Long) {
            if (refuses) throw IllegalStateException("not allowed to start service")
            started += ticket
        }
        override fun stop() { stops++ }
    }

    private companion object {
        const val ALBUM = "album-1"
        const val E1 = 1uL
        const val E2 = 2uL

        fun song(id: String) = testCard(id = id, itemType = "Audio", name = "Song $id")
    }
}
