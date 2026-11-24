package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.TransportOp
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.GeneralCommandFfi
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.PlayCommandFfi
import uniffi.jellybeam_core.PlaystateCommandFfi
import uniffi.jellybeam_core.RemoteCommandFfi
import uniffi.jellybeam_core.RemotePlay
import uniffi.jellybeam_core.RemoteItems
import uniffi.jellybeam_core.RemoteRepeatFfi
import uniffi.jellybeam_core.RemoteShuffleFfi
import uniffi.jellybeam_core.ReportFactsFfi
import uniffi.jellybeam_core.ReportListFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackKindFfi
import tv.jellybeam.player.music.EnqueueMode as MusicEnqueue

private const val E = 7uL
private const val OTHER = 8uL

/** docs/31 §4-§6 (state table Seam 1): the routing matrix, every command family against every column. */
class RemoteControlTest {
    /** The table's columns, with P split by surface and visibility and Opening by surface. */
    private enum class Col { A, B, C, C2, D, D2, PF, PMS, PMH, PQS, PQH, H, OF, OM }

    private fun live(col: Col): RemoteLive {
        val shown = PlayerFlow.Main.Shown
        val hidden = PlayerFlow.Main.Hidden
        fun video(surface: Surface, tenure: Tenure) = VideoLive.Player(surface, tenure)
        return when (col) {
            Col.A -> RemoteLive(E, VideoLive.None, MusicLive.None, shown)
            Col.B -> RemoteLive(E, video(Surface.Full, Tenure.Own), MusicLive.None, hidden)
            Col.C -> RemoteLive(E, video(Surface.Mini, Tenure.Own), MusicLive.None, shown)
            Col.C2 -> RemoteLive(E, video(Surface.Mini, Tenure.Own), MusicLive.None, hidden)
            Col.D -> RemoteLive(E, VideoLive.None, MusicLive.Own, shown)
            Col.D2 -> RemoteLive(E, VideoLive.None, MusicLive.Own, hidden)
            Col.PF -> RemoteLive(E, video(Surface.Full, Tenure.Parked), MusicLive.None, hidden)
            Col.PMS -> RemoteLive(E, video(Surface.Mini, Tenure.Parked), MusicLive.None, shown)
            Col.PMH -> RemoteLive(E, video(Surface.Mini, Tenure.Parked), MusicLive.None, hidden)
            Col.PQS -> RemoteLive(E, VideoLive.None, MusicLive.Parked, shown)
            Col.PQH -> RemoteLive(E, VideoLive.None, MusicLive.Parked, hidden)
            Col.H -> RemoteLive(E, VideoLive.None, MusicLive.None, hidden)
            Col.OF -> RemoteLive(E, video(Surface.Full, Tenure.Opening), MusicLive.None, hidden)
            Col.OM -> RemoteLive(E, video(Surface.Mini, Tenure.Opening), MusicLive.None, shown)
        }
    }

    private fun kind(action: RemoteAction): String = when (action) {
        is RemoteAction.Drop -> "drop:${action.why}"
        is RemoteAction.Arrive -> AR
        is RemoteAction.Video -> V
        is RemoteAction.Music -> M
        is RemoteAction.Volume -> VO
        is RemoteAction.Toast -> T
        is RemoteAction.Ui -> U
    }

    private fun play(items: RemoteItems, command: PlayCommandFfi, start: UInt = 1u, ticks: Long? = null, source: String? = null, audio: Int? = null, subtitle: Int? = null) =
        RemoteCommandFfi.Play(RemotePlay(items, start, ticks, command, source, audio, subtitle))

    private fun general(command: GeneralCommandFfi) = RemoteCommandFfi.General(command)
    private fun playstate(command: PlaystateCommandFfi) = RemoteCommandFfi.Playstate(command)
    private val reverse: (List<String>) -> List<String> = { it.reversed() }
    private fun route(command: RemoteCommandFfi, col: Col) = route(E, command, live(col), reverse)

    private val playstates = listOf(
        PlaystateCommandFfi.Stop, PlaystateCommandFfi.Pause, PlaystateCommandFfi.Unpause, PlaystateCommandFfi.NextTrack, PlaystateCommandFfi.PreviousTrack,
        PlaystateCommandFfi.Seek(10_000_000L), PlaystateCommandFfi.Rewind, PlaystateCommandFfi.FastForward, PlaystateCommandFfi.PlayPause,
    ).map(::playstate)

    private val keys = listOf(
        GeneralCommandFfi.MoveUp, GeneralCommandFfi.MoveDown, GeneralCommandFfi.MoveLeft, GeneralCommandFfi.MoveRight,
        GeneralCommandFfi.Select, GeneralCommandFfi.Back, GeneralCommandFfi.PageUp, GeneralCommandFfi.PageDown,
    ).map(::general)

    private val gos = listOf(
        GeneralCommandFfi.GoHome, GeneralCommandFfi.GoToSearch, GeneralCommandFfi.GoToSettings,
        GeneralCommandFfi.PreviousLetter, GeneralCommandFfi.NextLetter, GeneralCommandFfi.ToggleContextMenu,
    ).map(::general)

    private val volumes = listOf(
        GeneralCommandFfi.SetVolume(40u), GeneralCommandFfi.VolumeUp, GeneralCommandFfi.VolumeDown,
        GeneralCommandFfi.Mute, GeneralCommandFfi.Unmute, GeneralCommandFfi.ToggleMute,
    ).map(::general)

    private fun row(vararg expected: String) = expected.toList().also { check(it.size == Col.entries.size) }

    // Columns:                              A    B    C    C2   D    D2   PF   PMS  PMH  PQS  PQH  H    OF   OM
    private val matrix: List<Pair<List<RemoteCommandFfi>, List<String>>> = listOf(
        listOf(play(VIDEO, PlayCommandFfi.PLAY_NOW), play(VIDEO, PlayCommandFfi.PLAY_SHUFFLE)) to
            row(AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR),
        listOf(play(VIDEO, PlayCommandFfi.PLAY_NEXT), play(VIDEO, PlayCommandFfi.PLAY_LAST)) to
            row(AR, V, V, V, AR, AR, AR, AR, AR, AR, AR, AR, NR, NR),
        listOf(play(VIDEO, PlayCommandFfi.PLAY_INSTANT_MIX)) to
            row(NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA),
        (PlayCommandFfi.entries.map { play(AUDIO, it) } + play(MIX, PlayCommandFfi.PLAY_INSTANT_MIX)) to
            row(M, M, M, M, M, M, M, M, M, M, M, BG, M, M),
        listOf(("item-1" to "Movie"), ("lib-1" to "CollectionFolder"), ("view-1" to "UserView")).map { (id, type) -> general(GeneralCommandFfi.DisplayContent(id, type)) } to
            row(AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR, AR),
        playstates to
            row(NL, V, V, V, M, M, NC, NC, NC, NC, NC, NL, NR, NR),
        listOf(general(GeneralCommandFfi.SetAudioStreamIndex(1)), general(GeneralCommandFfi.SetSubtitleStreamIndex(-1))) to
            row(NL, V, V, V, NA, NA, NC, NC, NC, NC, NC, NL, NR, NR),
        volumes to
            row(VO, VO, VO, VO, VO, VO, VO, VO, VO, VO, VO, BG, VO, VO),
        listOf(general(GeneralCommandFfi.DisplayMessage("Header", "Text"))) to
            row(T, T, T, T, T, T, T, T, T, T, T, BG, T, T),
        listOf(general(GeneralCommandFfi.SendString("typed"))) to
            row(U, T, U, T, U, T, T, U, T, U, T, BG, T, U),
        keys to
            row(U, V, U, BG, U, BG, NC, U, BG, U, BG, BG, V, U),
        gos to
            row(U, FP, U, BG, U, BG, FP, U, BG, U, BG, BG, FP, U),
        listOf(GeneralCommandFfi.ToggleOsd, GeneralCommandFfi.ToggleOsdMenu, GeneralCommandFfi.ToggleStats).map(::general) to
            row(NL, V, NA, NA, NL, NL, NC, NC, NC, NC, NC, NL, NR, NR),
        listOf(general(GeneralCommandFfi.ToggleFullscreen)) to
            row(NL, V, V, V, NL, NL, NC, NC, NC, NC, NC, NL, NR, NR),
        listOf(general(GeneralCommandFfi.SetRepeatMode(RemoteRepeatFfi.ALL)), general(GeneralCommandFfi.SetShuffle(RemoteShuffleFfi.SHUFFLED))) to
            row(NL, NA, NA, NA, M, M, NC, NC, NC, NC, NC, NL, NA, NA),
    )

    @Test
    fun `every command family lands where the table puts it in every column`() {
        var cells = 0
        for ((commands, expected) in matrix) {
            for (command in commands) {
                Col.entries.forEachIndexed { i, col ->
                    assertEquals("${diagName(command)} in $col", expected[i], kind(route(command, col)))
                    cells++
                }
            }
        }
        assertEquals("53 commands, every playstate and general variant among them", 53 * Col.entries.size, cells)
    }

    /** R1: a command for another account never acts, whatever is live. */
    @Test
    fun `a command from an older epoch drops as stale in every column`() {
        for ((commands, _) in matrix) {
            for (command in commands) {
                for (col in Col.entries) {
                    assertEquals("${diagName(command)} in $col", "drop:${DropWhy.Stale}", kind(route(OTHER, command, live(col), reverse)))
                }
            }
        }
    }

    /** docs/31 §4.2: a live video player wins over a live music session. */
    @Test
    fun `a live video player wins over live music`() {
        val both = RemoteLive(E, VideoLive.Player(Surface.Full, Tenure.Own), MusicLive.Own, PlayerFlow.Main.Hidden)
        assertEquals(RemoteAction.Video(VideoOp.Playstate(PlaystateCommandFfi.Pause)), route(E, playstate(PlaystateCommandFfi.Pause), both, reverse))
    }

    @Test
    fun `a video play arrives as the start item with its position tracks and list`() {
        val action = route(play(VIDEO, PlayCommandFfi.PLAY_NOW, ticks = 50_000_000L, source = "src-1", audio = 2, subtitle = -1), Col.A)
        val kind = Req.Kind.Play("v2", Req.Start.At(5_000L), TrackRequest("src-1", 2, -1), RemoteQueue(listOf("v1", "v2", "v3"), 1))
        assertEquals(RemoteAction.Arrive(kind), action)
    }

    @Test
    fun `a single video with no position or hints resumes with no list`() {
        val action = route(play(RemoteItems.Video(listOf("v1")), PlayCommandFfi.PLAY_NOW, start = 0u), Col.H)
        assertEquals(RemoteAction.Arrive(Req.Kind.Play("v1", Req.Start.Resume, null, null)), action)
    }

    /** docs/31 §4.1: the sent position, version and streams name the start item, which a shuffle need not play first. */
    @Test
    fun `a video shuffle plays the shuffled list from its first item, without the start item's position or hints`() {
        val action = route(play(VIDEO, PlayCommandFfi.PLAY_SHUFFLE, ticks = 50_000_000L, source = "src-1", audio = 2, subtitle = -1), Col.A)
        assertEquals(RemoteAction.Arrive(Req.Kind.Play("v3", Req.Start.Resume, null, RemoteQueue(listOf("v3", "v2", "v1"), 0))), action)
    }

    /** docs/31 §7: a volume change is reported at once by the session it is heard in, never a parked or opening one. */
    @Test
    fun `a volume change reports through the own video or music session`() {
        val up = general(GeneralCommandFfi.VolumeUp)
        assertEquals(RemoteAction.Volume(VolumeOp.Up, RemoteAction.Video(VideoOp.Report)), route(up, Col.B))
        assertEquals(RemoteAction.Volume(VolumeOp.Up, RemoteAction.Video(VideoOp.Report)), route(up, Col.C2))
        assertEquals(RemoteAction.Volume(VolumeOp.Up, RemoteAction.Music(MusicCall.Report)), route(up, Col.D2))
        for (col in listOf(Col.A, Col.PF, Col.PQS, Col.OF)) assertEquals("$col", RemoteAction.Volume(VolumeOp.Up), route(up, col))
    }

    @Test
    fun `play next and last over an own player enqueue, and over nothing play now`() {
        assertEquals(RemoteAction.Video(VideoOp.Enqueue(listOf("v1", "v2", "v3"), EnqueueMode.Next)), route(play(VIDEO, PlayCommandFfi.PLAY_NEXT), Col.B))
        assertEquals(RemoteAction.Video(VideoOp.Enqueue(listOf("v1", "v2", "v3"), EnqueueMode.Last)), route(play(VIDEO, PlayCommandFfi.PLAY_LAST), Col.C2))
        assertEquals(RemoteAction.Arrive(Req.Kind.Play("v2", Req.Start.Resume, null, RemoteQueue(listOf("v1", "v2", "v3"), 1))), route(play(VIDEO, PlayCommandFfi.PLAY_NEXT), Col.D))
    }

    @Test
    fun `song plays map to the music owner's play and enqueue over the sent songs`() {
        val songs = listOf(testCard(id = "s1", itemType = "Audio"), testCard(id = "s2", itemType = "Audio"))
        fun audio(command: PlayCommandFfi) = route(play(RemoteItems.Songs(songs), command, start = 1u), Col.A)
        val source = MusicSource.Items(songs)
        assertEquals(RemoteAction.Music(MusicCall.Play(source, 1, shuffled = false)), audio(PlayCommandFfi.PLAY_NOW))
        assertEquals(RemoteAction.Music(MusicCall.Play(source, 1, shuffled = true)), audio(PlayCommandFfi.PLAY_SHUFFLE))
        assertEquals(RemoteAction.Music(MusicCall.Enqueue(MusicEnqueue.PlayNext, source)), audio(PlayCommandFfi.PLAY_NEXT))
        assertEquals(RemoteAction.Music(MusicCall.Enqueue(MusicEnqueue.AddToQueue, source)), audio(PlayCommandFfi.PLAY_LAST))
    }

    @Test
    fun `a mix plays the music owner's instant mix from its seed and names the audio diag`() {
        val seed = MixSeedFfi.Album("al1")
        val mix = play(RemoteItems.Mix(seed), PlayCommandFfi.PLAY_INSTANT_MIX, start = 0u)
        assertEquals(RemoteAction.Music(MusicCall.Play(MusicSource.Mix(seed), 0, shuffled = false)), route(mix, Col.A))
        assertEquals("play_audio", diagName(mix))
        assertEquals("play_audio", diagName(play(AUDIO, PlayCommandFfi.PLAY_NOW)))
        assertEquals(PLAY_VIDEO_DIAG, diagName(play(VIDEO, PlayCommandFfi.PLAY_NOW)))
    }

    @Test
    fun `display content always arrives as an open, flagged as a library by Rust's item type`() {
        assertEquals(RemoteAction.Arrive(Req.Kind.OpenDetail("item-1")), route(general(GeneralCommandFfi.DisplayContent("item-1", "Episode")), Col.C))
        for (type in listOf("CollectionFolder", "UserView")) {
            assertEquals(type, RemoteAction.Arrive(Req.Kind.OpenDetail("lib-1", library = true)), route(general(GeneralCommandFfi.DisplayContent("lib-1", type)), Col.A))
        }
    }

    @Test
    fun `messages show header and text verbatim, cut at 200 characters`() {
        assertEquals(RemoteAction.Toast("Header: Text"), route(general(GeneralCommandFfi.DisplayMessage("Header", "Text")), Col.B))
        assertEquals(RemoteAction.Toast("Text"), route(general(GeneralCommandFfi.DisplayMessage(null, "Text")), Col.B))
        assertEquals(RemoteAction.Toast("typed"), route(general(GeneralCommandFfi.SendString("typed")), Col.B))
        assertEquals(RemoteAction.Ui(UiOp.Text("typed")), route(general(GeneralCommandFfi.SendString("typed")), Col.A))
        assertEquals("x".repeat(200), toastText(null, "x".repeat(250)))
    }

    @Test
    fun `general commands carry their arguments to the owner`() {
        assertEquals(RemoteAction.Volume(VolumeOp.Set(40)), route(general(GeneralCommandFfi.SetVolume(40u)), Col.A))
        assertEquals(RemoteAction.Video(VideoOp.Key(RemoteKey.Up)), route(general(GeneralCommandFfi.MoveUp), Col.B))
        assertEquals(RemoteAction.Ui(UiOp.Key(RemoteKey.Back)), route(general(GeneralCommandFfi.Back), Col.A))
        assertEquals(RemoteAction.Ui(UiOp.Home), route(general(GeneralCommandFfi.GoHome), Col.C))
        assertEquals(RemoteAction.Video(VideoOp.EnterMini), route(general(GeneralCommandFfi.ToggleFullscreen), Col.B))
        assertEquals(RemoteAction.Video(VideoOp.Expand), route(general(GeneralCommandFfi.ToggleFullscreen), Col.C2))
        assertEquals(RemoteAction.Video(VideoOp.ToggleStats), route(general(GeneralCommandFfi.ToggleStats), Col.B))
        assertEquals(RemoteAction.Video(VideoOp.Track(TrackKindFfi.SUBTITLE, -1)), route(general(GeneralCommandFfi.SetSubtitleStreamIndex(-1)), Col.C))
        assertEquals(RemoteAction.Music(MusicCall.Transport(TransportOp.Repeat(RepeatMode.All))), route(general(GeneralCommandFfi.SetRepeatMode(RemoteRepeatFfi.ALL)), Col.D))
        assertEquals(RemoteAction.Music(MusicCall.Transport(TransportOp.Shuffle(true))), route(general(GeneralCommandFfi.SetShuffle(RemoteShuffleFfi.SHUFFLED)), Col.D2))
        assertEquals(RemoteAction.Music(MusicCall.Playstate(PlaystateCommandFfi.Seek(10_000_000L))), route(playstate(PlaystateCommandFfi.Seek(10_000_000L)), Col.D))
    }

    @Test
    fun `remote keys are the matching d-pad keys`() {
        assertEquals(
            listOf(19, 20, 21, 22, 23, 4, 92, 93),
            listOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Select, RemoteKey.Back, RemoteKey.PageUp, RemoteKey.PageDown).map { it.code },
        )
    }

    /** docs/31 §4.5: a synthetic KEYCODE_BACK never reaches predictive back, so Back goes to the back dispatcher and nothing else. */
    @Test
    fun `a remote Back goes to the back dispatcher, never as a key event`() {
        val events = mutableListOf<Int>()
        var backs = 0
        assertEquals(true, pressKey(RemoteKey.Back, { events += it.keyCode; true }, { backs++ }))
        assertEquals(1, backs)
        assertEquals(emptyList<Int>(), events)
        pressKey(RemoteKey.Up, { events += it.action; false }, { backs++ })
        assertEquals("a d-pad key is a press and a release", 2, events.size)
        assertEquals(1, backs)
    }

    /** docs/31 §4.5: a remote Back while ambient draws only ends ambient, as the physical key does. */
    @Test
    fun `a remote Back consumed by ambient never reaches the back dispatcher`() {
        var backs = 0
        var offered = 0
        pressKey(RemoteKey.Back, { true }, { backs++ }, ambient = { offered++ == 0 })
        assertEquals("down and up are both offered to ambient", 2, offered)
        assertEquals(0, backs)
        offered = 0
        pressKey(RemoteKey.Back, { true }, { backs++ }, ambient = { offered++ == 1 })
        assertEquals("a consumed release alone also keeps Back from navigating", 0, backs)
        offered = 0
        pressKey(RemoteKey.Back, { true }, { backs++ }, ambient = { offered++; false })
        assertEquals("ambient not drawing: Back goes to the dispatcher", 1, backs)
    }

    /** docs/31 §4.5: every other remote op ends a drawing ambient first, so the op itself is not swallowed. */
    @Test
    fun `every remote op but Back first offers a press and release to ambient`() {
        val ops = listOf(UiOp.Key(RemoteKey.Up), UiOp.Text("a"), UiOp.Home, UiOp.Search, UiOp.Settings, UiOp.PreviousLetter, UiOp.NextLetter, UiOp.ContextMenu)
        for (op in ops) {
            var offered = 0
            endAmbientBefore(op) { offered++; true }
            assertEquals("$op", 2, offered)
        }
        var offered = 0
        endAmbientBefore(UiOp.Key(RemoteKey.Back)) { offered++; true }
        assertEquals("Back offers itself in pressKey", 0, offered)
    }

    // -- runVideo ----------------------------------------------------------

    private fun handle(remote: (VideoOp) -> DropWhy?) = PlayerHandle(finishAndRemoveTask = {}, liveItemId = { null }, remote = remote)

    /** docs/31 §4.2: an applied op answers null, which must not read as an absent handle. */
    @Test
    fun `a video op the live handle applied drops nothing and an absent handle is nothing live`() {
        assertNull(runVideo(handle { null }, VideoOp.Report))
        assertEquals(DropWhy.NotReady, runVideo(handle { DropWhy.NotReady }, VideoOp.Report))
        assertEquals(DropWhy.NothingLive, runVideo(null, VideoOp.Report))
    }

    // -- remoteLive --------------------------------------------------------

    private fun scope(epoch: ULong) = SessionScope(generation = 1L, itemId = "v1", accountEpoch = epoch)
    private fun session(epoch: ULong) = MusicSession(sid = 1L, epoch = epoch, source = MusicSource.Items(listOf(testCard(id = "s1", itemType = "Audio"))), items = emptyList())

    @Test
    fun `live reads the slot's surface, the session's tenure and the music phase`() {
        fun video(player: PlayerFlow.Player, session: SessionScope? = scope(E)) = remoteLive(PlayerFlow(player = player), session, null, E).video
        val expanding = PlayerFlow.Player.Expanding(3L, Req(1L, Req.Kind.Play("v1"), Req.Origin.Remote))
        assertEquals(VideoLive.None, video(PlayerFlow.Player.None))
        assertEquals(VideoLive.None, video(PlayerFlow.Player.Launching(1L)))
        assertEquals(VideoLive.None, video(PlayerFlow.Player.Closing(3L)))
        assertEquals(VideoLive.Player(Surface.Full, Tenure.Own), video(PlayerFlow.Player.Fullscreen(3L)))
        assertEquals(VideoLive.Player(Surface.Full, Tenure.Own), video(expanding))
        assertEquals(VideoLive.Player(Surface.Mini, Tenure.Own), video(PlayerFlow.Player.Shrinking(3L)))
        assertEquals(VideoLive.Player(Surface.Mini, Tenure.Parked), video(PlayerFlow.Player.Pip(3L), scope(OTHER)))
        assertEquals(VideoLive.Player(Surface.Full, Tenure.Opening), video(PlayerFlow.Player.Fullscreen(3L), null))

        fun music(phase: MusicFlow.Phase?) = remoteLive(PlayerFlow(), null, phase, E).music
        assertEquals(MusicLive.None, music(null))
        assertEquals(MusicLive.None, music(MusicFlow.Phase.Idle))
        assertEquals(MusicLive.Own, music(MusicFlow.Phase.Starting(session(E), ticket = 1L)))
        assertEquals(MusicLive.Own, music(MusicFlow.Phase.Active(session(E), g = 1L, n = 0, net = MusicFlow.Net.Online)))
        assertEquals(MusicLive.Parked, music(MusicFlow.Phase.Active(session(OTHER), g = 1L, n = 0, net = MusicFlow.Net.Online)))

        val live = remoteLive(PlayerFlow(main = PlayerFlow.Main.Shown), null, null, E)
        assertEquals(RemoteLive(E, VideoLive.None, MusicLive.None, PlayerFlow.Main.Shown), live)
    }

    // -- music mapping -----------------------------------------------------

    private fun now(paused: Boolean = false, positionMs: Long = 30_000L, durationMs: Long = 200_000L) = MusicNow(
        queue = emptyList(), playOrder = emptyList(), current = 0, isPlaying = !paused, positionMs = positionMs,
        durationMs = durationMs, shuffle = false, repeat = RepeatMode.Off, paused = paused,
    )

    @Test
    fun `music playstate maps per the table`() {
        assertEquals(MusicEvent.Transport(TransportOp.Pause), musicEventFor(PlaystateCommandFfi.Pause, now()))
        assertEquals(MusicEvent.UserPlay, musicEventFor(PlaystateCommandFfi.Unpause, now(paused = true)))
        assertEquals(MusicEvent.Transport(TransportOp.Pause), musicEventFor(PlaystateCommandFfi.PlayPause, now(paused = false)))
        assertEquals(MusicEvent.UserPlay, musicEventFor(PlaystateCommandFfi.PlayPause, now(paused = true)))
        assertEquals(MusicEvent.Transport(TransportOp.SeekTo(12_000L)), musicEventFor(PlaystateCommandFfi.Seek(120_000_000L), now()))
        assertEquals(MusicEvent.Transport(TransportOp.Next), musicEventFor(PlaystateCommandFfi.NextTrack, now()))
        assertEquals(MusicEvent.Transport(TransportOp.Previous), musicEventFor(PlaystateCommandFfi.PreviousTrack, now()))
        assertEquals(MusicEvent.Transport(TransportOp.SeekTo(20_000L)), musicEventFor(PlaystateCommandFfi.Rewind, now()))
        assertEquals(MusicEvent.Transport(TransportOp.SeekTo(0L)), musicEventFor(PlaystateCommandFfi.Rewind, now(positionMs = 4_000L)))
        assertEquals(MusicEvent.Transport(TransportOp.SeekTo(40_000L)), musicEventFor(PlaystateCommandFfi.FastForward, now()))
        assertEquals(MusicEvent.Transport(TransportOp.SeekTo(200_000L)), musicEventFor(PlaystateCommandFfi.FastForward, now(positionMs = 195_000L)))
        assertEquals(MusicEvent.Stop, musicEventFor(PlaystateCommandFfi.Stop, now()))
        assertNull("a relative seek needs a position", musicEventFor(PlaystateCommandFfi.Rewind, null))
    }

    // -- report facts ------------------------------------------------------

    private val signs = EmbeddedSubtitleFfi(index = 2, language = "eng", title = "Signs", forced = false, default = true, codec = "ass", displayTitle = "Signs", delivery = SubtitleDeliveryFfi.EMBED)
    private val full = EmbeddedSubtitleFfi(index = 3, language = "eng", title = "Full", forced = false, default = false, codec = "subrip", displayTitle = "Full", delivery = SubtitleDeliveryFfi.EMBED)
    private val main = AudioStreamFfi(index = 0, displayTitle = "Main", title = "Main", language = "eng", codec = "aac", channels = 2, isDefault = true)
    private val original = AudioStreamFfi(index = 1, displayTitle = "Original", title = "Original", language = "jpn", codec = "aac", channels = 2, isDefault = false)

    private fun facts(delivered: Int? = null, burned: Int? = null) = PlanFacts(
        session = "ps-1", mediaSourceId = "src-1", transcode = false, embedded = listOf(signs, full), audioStreams = listOf(main, original),
        sidecars = emptyList(), delivered = delivered, burned = burned, policy = null, rows = emptyList(),
    )

    private fun video(audio: Int?, subtitle: Int?) = ReportFactsFfi.Video(audio, subtitle, null)

    @Test
    fun `report facts name the chosen server streams`() {
        val p = facts(delivered = 0)
        val originalPick = AudioPick.Track(streamAudioChoice(original), id = 101L)
        assertEquals(video(null, null), remoteFacts(TrackChoice.Idle, RemoteList.None))
        assertEquals(video(1, -1), remoteFacts(TrackChoice.Decided(p, TextPick.Off, originalPick), RemoteList.None))
        assertEquals(video(1, 3), remoteFacts(TrackChoice.Decided(p, TextPick.Track(full.identity(), 3, TextPick.On.Shown(103L)), originalPick), RemoteList.None))
        assertEquals(video(null, 6), remoteFacts(TrackChoice.Decided(p, TextPick.Sidecar(6, full.identity(), TextPick.Load.Shown, byUser = true), AudioPick.NoAudio), RemoteList.None))
        assertEquals("no pick yet: what the plan delivers and burns", video(0, 2), remoteFacts(TrackChoice.Awaiting(facts(delivered = 0, burned = 2)), RemoteList.None))
        assertEquals("the player's own default subtitle is unknown", video(0, null), remoteFacts(TrackChoice.Decided(p, TextPick.Leave, AudioPick.Track(streamAudioChoice(main), id = null)), RemoteList.None))
        assertEquals(
            "a remote list rides along",
            ReportFactsFfi.Video(null, null, uniffi.jellybeam_core.ReportListFfi(listOf("a", "b"), 1u)),
            remoteFacts(TrackChoice.Idle, RemoteList.Playing(listOf("a", "b"), 1)),
        )
    }

    private companion object {
        val VIDEO = RemoteItems.Video(listOf("v1", "v2", "v3"))
        val AUDIO = RemoteItems.Songs(listOf("s1", "s2", "s3").map { testCard(id = it, itemType = "Audio") })
        val MIX = RemoteItems.Mix(MixSeedFfi.Song("s1"))
        const val AR = "arrive"
        const val V = "video"
        const val M = "music"
        const val VO = "volume"
        const val T = "toast"
        const val U = "ui"
        val NL = "drop:${DropWhy.NothingLive}"
        val NA = "drop:${DropWhy.NotApplicable}"
        val NC = "drop:${DropWhy.NotCommandable}"
        val NR = "drop:${DropWhy.NotReady}"
        val BG = "drop:${DropWhy.Background}"
        val FP = "drop:${DropWhy.FullscreenPlayer}"
    }

    /** docs/31 §4.1.1, §7: only a session new to the core that plays under a list reports at once. */
    @Test
    fun `a session reports at once only when it is new to the core and plays under a list`() {
        val listed = ReportFactsFfi.Video(null, null, ReportListFfi(listOf("a", "b"), 0u))
        val plain = ReportFactsFfi.Video(null, null, null)
        val rows = listOf(
            Triple(null, listed, true),
            Triple(null, plain, false),
            Triple("s0" to plain, listed, true),
            Triple("s0" to listed, plain, false),
            Triple("s1" to plain, listed, false),
            Triple("s1" to listed, listed, false),
            Triple(null, ReportFactsFfi.Audio(RemoteRepeatFfi.OFF, RemoteShuffleFfi.SORTED), false),
        )
        for ((sent, facts, atOnce) in rows) assertEquals("$sent then s1 with $facts", atOnce, reportsAtOnce(sent, "s1", facts))
    }
}
