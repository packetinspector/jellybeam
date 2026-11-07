package tv.jellybeam.ui.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.player.music.EnqueueMode
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicPlayerPort
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.MusicServiceHost
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.TransportOp
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.ItemDetail

/** docs/18 §2.1: Now Playing reads a session's metadata on the session's own account, parked or not. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingAccountTest {

    @get:Rule val main = MainDispatcherRule()

    private val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf(ALBUM to Result.success(albumDetail()))).apply {
        accountEpoch = E1
        albumTracksByAlbum = mapOf(ALBUM to listOf(testCard(id = "a", itemType = "Audio", name = "Song a")))
        cardsByItemId[ALBUM] = testCard(id = ALBUM, itemType = "MusicAlbum", name = "Album from the mirror")
    }

    /** Plays [ALBUM] on [E1], moves the accounts to [current], and runs [check] on an active Now Playing. */
    private fun TestScope.nowPlaying(current: ULong, parked: ULong?, check: (NowPlayingViewModel, MusicPlaybackOwner) -> Unit) {
        val host = NpHost()
        val owner = MusicPlaybackOwner(gateway, backgroundScope, host, closeVideo = {}, holdsPlaybackChanged = {}, main = UnconfinedTestDispatcher(testScheduler))
        owner.play(MusicSource.Album(ALBUM))
        owner.send(MusicEvent.ServiceReady(owner.attach(NpPort()), host.started.single()))
        gateway.setEpochs(current, parked)
        runCurrent()
        val vm = NowPlayingViewModel(gateway, owner, directLine = { "direct" }, transcodeLine = { "transcode" })
        vm.setActive(true)
        runCurrent()
        try {
            check(vm, owner)
        } finally {
            vm.setActive(false)
        }
    }

    private fun reads() = gateway.accountBoundCalls.filter { it.first == "audioFacts" || it.first == "getItemDetail" }

    @Test
    fun `a parked session's spec line and source name are read on its own account`() = runTest {
        nowPlaying(current = E2, parked = E1) { vm, _ ->
            assertEquals(listOf("getItemDetail" to E1, "audioFacts" to E1), reads())
            assertEquals("the mirror is the current account's", emptyList<String>(), gateway.cardByIdCalls)
            assertEquals(SourceName.Named(SourceKind.Album, "Album on its own account"), vm.state.value.source)
            assertEquals(SpecLine.Direct("direct"), vm.state.value.spec)
        }
    }

    @Test
    fun `a session on the current account names its source from the mirror`() = runTest {
        nowPlaying(current = E1, parked = null) { vm, _ ->
            assertEquals(listOf("audioFacts" to E1), reads())
            assertEquals(listOf(ALBUM), gateway.cardByIdCalls)
            assertEquals(SourceName.Named(SourceKind.Album, "Album from the mirror"), vm.state.value.source)
        }
    }

    @Test
    fun `a favourite flipped on Now Playing sticks to the song's card, and a failed write leaves it`() = runTest {
        nowPlaying(current = E1, parked = null) { vm, owner ->
            vm.toggleFavorite("a", isFavorite = false)
            runCurrent()
            assertEquals(listOf(FakeCoreGateway.SetFavoriteCall("a", true)), gateway.setFavoriteCalls)
            assertEquals(true, owner.card("a")?.isFavorite)
            gateway.setFavoriteError = java.io.IOException("offline")
            vm.toggleFavorite("a", isFavorite = true)
            runCurrent()
            assertEquals("the failed unfavourite is not the card's truth", true, owner.card("a")?.isFavorite)
        }
    }

    private companion object {
        const val ALBUM = "album-1"
        const val E1 = 1uL
        const val E2 = 2uL

        fun albumDetail() = ItemDetail(
            id = ALBUM,
            name = "Album on its own account",
            itemType = "MusicAlbum",
            seriesName = null,
            parentIndexNumber = null,
            indexNumber = null,
            premiereDate = "2020-01-01",
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
            overview = "A synthetic overview.",
            runTimeTicks = 72_000_000_000L,
            container = null,
            people = emptyList(),
            mediaStreams = emptyList(),
            chapters = emptyList(),
            dateCreated = null,
            sizeBytes = null,
            recursiveItemCount = null,
            childCount = null,
            directors = emptyList(),
            writers = emptyList(),
        )
    }
}

/** A service host that only records its starts. */
internal class NpHost : MusicServiceHost {
    val started = mutableListOf<Long>()
    override fun start(ticket: Long) { started += ticket }
    override fun stop() = Unit
}

/** A player that does nothing; tests publish its state through [MusicPlaybackOwner.publish]. */
internal class NpPort : MusicPlayerPort {
    override fun build(session: MusicSession) = Unit
    override fun replaceQueue(session: MusicSession) = Unit
    override fun insert(mode: EnqueueMode, items: List<String>) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun retryPrepare() = Unit
    override fun skipNext() = Unit
    override fun apply(op: TransportOp) = Unit
    override fun positionTicks(): Long = 0L
    override fun release() = Unit
}
