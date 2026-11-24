package tv.jellybeam.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import tv.jellybeam.AppGraph
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.TransportOp
import uniffi.jellybeam_core.GeneralCommandFfi
import uniffi.jellybeam_core.PlayCommandFfi
import uniffi.jellybeam_core.PlaystateCommandFfi
import uniffi.jellybeam_core.RemoteCommandFfi
import uniffi.jellybeam_core.RemoteControlListener
import uniffi.jellybeam_core.RemotePlay
import uniffi.jellybeam_core.RemoteItems
import uniffi.jellybeam_core.RemoteRepeatFfi
import uniffi.jellybeam_core.RemoteShuffleFfi
import uniffi.jellybeam_core.ReportFactsFfi
import uniffi.jellybeam_core.ReportListFfi
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.VolumeFfi
import tv.jellybeam.player.music.EnqueueMode as MusicEnqueue

/** docs/31 §5, §6: what a remote command can reach, read once per command on Main. */
internal data class RemoteLive(val epoch: ULong, val video: VideoLive, val music: MusicLive, val main: PlayerFlow.Main)

internal sealed interface VideoLive {
    data object None : VideoLive
    data class Player(val surface: Surface, val tenure: Tenure) : VideoLive
}

internal enum class Surface { Full, Mini }

/** [Opening]: no session yet; [Parked]: the session plays on an account that is no longer installed (docs/18 §2.1). */
internal enum class Tenure { Opening, Own, Parked }

internal enum class MusicLive { None, Own, Parked }

/** docs/31 Seam 1: the slot player's surface, its session's tenure and the music phase; [music] `null` when no music owner exists. */
internal fun remoteLive(flow: PlayerFlow, session: SessionScope?, music: MusicFlow.Phase?, epoch: ULong): RemoteLive {
    val surface = when (flow.player) {
        PlayerFlow.Player.None, is PlayerFlow.Player.Launching, is PlayerFlow.Player.Closing -> null
        is PlayerFlow.Player.Fullscreen, is PlayerFlow.Player.Expanding -> Surface.Full
        is PlayerFlow.Player.Shrinking, is PlayerFlow.Player.Pip -> Surface.Mini
    }
    val tenure = when {
        session == null -> Tenure.Opening
        session.accountEpoch == epoch -> Tenure.Own
        else -> Tenure.Parked
    }
    val musicEpoch = when (music) {
        null, MusicFlow.Phase.Idle -> null
        is MusicFlow.Phase.Starting -> music.session.epoch
        is MusicFlow.Phase.Active -> music.session.epoch
    }
    val musicLive = when (musicEpoch) {
        null -> MusicLive.None
        epoch -> MusicLive.Own
        else -> MusicLive.Parked
    }
    return RemoteLive(epoch, surface?.let { VideoLive.Player(it, tenure) } ?: VideoLive.None, musicLive, flow.main)
}

/** Why a command did nothing; [tag] is the fixed diag word, since R8 renames enum constants. */
internal enum class DropWhy(val tag: String) {
    Stale("stale"),
    Background("background"),
    NothingLive("nothing_live"),
    NotReady("not_ready"),
    NotCommandable("not_commandable"),
    FullscreenPlayer("fullscreen_player"),
    NotApplicable("not_applicable"),

    /** docs/31 Seam 2 T4: the player flow let a remote request go without starting it. */
    NotStarted("not_started"),
}

/** A key a remote sends, as the window's key code; [Menu] is ToggleContextMenu's (docs/31 §4.5). */
internal enum class RemoteKey(val code: Int) {
    Up(KeyEvent.KEYCODE_DPAD_UP),
    Down(KeyEvent.KEYCODE_DPAD_DOWN),
    Left(KeyEvent.KEYCODE_DPAD_LEFT),
    Right(KeyEvent.KEYCODE_DPAD_RIGHT),
    Select(KeyEvent.KEYCODE_DPAD_CENTER),
    Back(KeyEvent.KEYCODE_BACK),
    PageUp(KeyEvent.KEYCODE_PAGE_UP),
    PageDown(KeyEvent.KEYCODE_PAGE_DOWN),
    Menu(KeyEvent.KEYCODE_MENU),
}

/** What the live video player is asked to do. */
internal sealed interface VideoOp {
    /** Run by the player Activity: its window and picture-in-picture. */
    sealed interface Window : VideoOp

    /** Run by the player screen's OSD (docs/12 §9), which owns its visibility. */
    sealed interface Osd : Window

    /** Run by the view model against its live session. */
    sealed interface Session : VideoOp

    data class Key(val key: RemoteKey) : Window
    data object EnterMini : Window
    data object Expand : Window

    data class Playstate(val command: PlaystateCommandFfi) : Session

    /** Server stream [index] of [kind]; a subtitle index of -1 is off. */
    data class Track(val kind: TrackKindFfi, val index: Int) : Session
    data class Enqueue(val ids: List<String>, val mode: EnqueueMode) : Session
    data object ToggleOsd : Osd
    data object ToggleOsdMenu : Osd
    data object ToggleStats : Session

    /** docs/31 §7: only the immediate report every applied command sends. */
    data object Report : Session
}

/** What the music owner is asked to do; [Play] and [Enqueue] are play requests, so they may build it. */
internal sealed interface MusicCall {
    data class Play(val source: MusicSource, val start: Int, val shuffled: Boolean) : MusicCall
    data class Enqueue(val mode: MusicEnqueue, val source: MusicSource) : MusicCall
    data class Playstate(val command: PlaystateCommandFfi) : MusicCall
    data class Transport(val op: TransportOp) : MusicCall

    /** docs/31 §7: only the immediate report every applied command sends. */
    data object Report : MusicCall
}

internal sealed interface VolumeOp {
    /** [percent] is 0-100. */
    data class Set(val percent: Int) : VolumeOp
    data object Up : VolumeOp
    data object Down : VolumeOp
    data object Mute : VolumeOp
    data object Unmute : VolumeOp
    data object ToggleMute : VolumeOp
}

/** docs/31 §4.5: what MainActivity's browsing UI is asked to do. */
internal sealed interface UiOp {
    data class Key(val key: RemoteKey) : UiOp
    data class Text(val text: String) : UiOp
    data object Home : UiOp
    data object Search : UiOp
    data object Settings : UiOp
    data object PreviousLetter : UiOp
    data object NextLetter : UiOp
    data object ContextMenu : UiOp
}

/** docs/31 §4.5: MainActivity's port for [UiOp]s; [apply] answers false when nothing took the op. */
internal fun interface RemoteUi {
    fun apply(op: UiOp): Boolean

    companion object {
        /** Until MainActivity attaches its port, every op is untaken. */
        val Absent = RemoteUi { false }
    }
}

/** docs/31 Seam 1: exactly one action per command. */
internal sealed interface RemoteAction {
    data class Drop(val why: DropWhy) : RemoteAction
    data class Arrive(val kind: Req.Kind) : RemoteAction
    data class Video(val op: VideoOp) : RemoteAction
    data class Music(val call: MusicCall) : RemoteAction
    /** docs/31 §7: [report] is the live session's report the change then sends, `null` with none of ours. */
    data class Volume(val op: VolumeOp, val report: RemoteAction? = null) : RemoteAction
    data class Toast(val text: String) : RemoteAction
    data class Ui(val op: UiOp) : RemoteAction
}

private enum class Target { Nothing, Video, Music, Parked }

/** Where a command lands: a live video player wins over live music (docs/31 §4.2). */
private class Where(live: RemoteLive) {
    private val video = live.video as? VideoLive.Player
    val target = when {
        video != null -> if (video.tenure == Tenure.Parked) Target.Parked else Target.Video
        live.music == MusicLive.Own -> Target.Music
        live.music == MusicLive.Parked -> Target.Parked
        else -> Target.Nothing
    }
    val full = video?.surface == Surface.Full
    val opening = video?.tenure == Tenure.Opening
    private val shown = live.main == PlayerFlow.Main.Shown

    /** docs/31 §5: nothing plays and no window of ours is visible. */
    val hidden = target == Target.Nothing && !shown
    val onTop = shown && !full
}

/**
 * docs/31 Seam 1: the action for [command], which came for account [epoch]; [shuffle] orders a
 * video PlayShuffle's list. Video Play and DisplayContent always reach the tracker, which owns
 * presence (R4).
 */
internal fun route(epoch: ULong, command: RemoteCommandFfi, live: RemoteLive, shuffle: (List<String>) -> List<String> = { it.shuffled() }): RemoteAction {
    if (epoch != live.epoch) return RemoteAction.Drop(DropWhy.Stale)
    val at = Where(live)
    return when (command) {
        is RemoteCommandFfi.Play -> routePlay(command.play, at, shuffle)
        is RemoteCommandFfi.Playstate -> transport(at, VideoOp.Playstate(command.command), MusicCall.Playstate(command.command))
        is RemoteCommandFfi.General -> routeGeneral(command.command, at)
    }
}

private fun drop(why: DropWhy) = RemoteAction.Drop(why)

private fun routePlay(play: RemotePlay, at: Where, shuffle: (List<String>) -> List<String>): RemoteAction = when (val items = play.items) {
    is RemoteItems.Video -> routeVideo(play, items.ids, at, shuffle)
    is RemoteItems.Songs -> routeMusic(at, songsCall(play.playCommand, MusicSource.Items(items.songs), play.startIndex.toInt()))
    is RemoteItems.Mix -> routeMusic(at, MusicCall.Play(MusicSource.Mix(items.seed), 0, shuffled = false))
}

private fun routeMusic(at: Where, call: MusicCall): RemoteAction = if (at.hidden) drop(DropWhy.Background) else RemoteAction.Music(call)

/** docs/31 §4.1: Rust sends Mix for an instant mix, so a Songs one plays plain. */
private fun songsCall(command: PlayCommandFfi, source: MusicSource, start: Int): MusicCall = when (command) {
    PlayCommandFfi.PLAY_NOW, PlayCommandFfi.PLAY_INSTANT_MIX -> MusicCall.Play(source, start, shuffled = false)
    PlayCommandFfi.PLAY_SHUFFLE -> MusicCall.Play(source, start, shuffled = true)
    PlayCommandFfi.PLAY_NEXT -> MusicCall.Enqueue(MusicEnqueue.PlayNext, source)
    PlayCommandFfi.PLAY_LAST -> MusicCall.Enqueue(MusicEnqueue.AddToQueue, source)
}

private fun routeVideo(play: RemotePlay, ids: List<String>, at: Where, shuffle: (List<String>) -> List<String>): RemoteAction {
    if (ids.isEmpty()) return drop(DropWhy.NotApplicable)
    val start = play.startIndex.toInt().coerceIn(ids.indices)
    val mode = when (play.playCommand) {
        PlayCommandFfi.PLAY_INSTANT_MIX -> return drop(DropWhy.NotApplicable)
        PlayCommandFfi.PLAY_SHUFFLE -> return RemoteAction.Arrive(shuffled(shuffle(ids)))
        PlayCommandFfi.PLAY_NOW -> return RemoteAction.Arrive(videoPlay(play, ids, start))
        PlayCommandFfi.PLAY_NEXT -> EnqueueMode.Next
        PlayCommandFfi.PLAY_LAST -> EnqueueMode.Last
    }
    // docs/31 §4.1: Next and Last join the own player's list; over anything else they play now.
    if (at.target != Target.Video) return RemoteAction.Arrive(videoPlay(play, ids, start))
    return if (at.opening) drop(DropWhy.NotReady) else RemoteAction.Video(VideoOp.Enqueue(ids, mode))
}

/** docs/31 §4.1: a shuffle plays [order] from the top; the sent position and hints name the start item, which it need not play first. */
private fun shuffled(order: List<String>) = Req.Kind.Play(order[0], Req.Start.Resume, null, RemoteQueue(order, 0).takeIf { order.size > 1 })

/** docs/31 §4.1: the sent item [start], at the sent position or the resume rule, with its track hints and list. */
private fun videoPlay(play: RemotePlay, order: List<String>, start: Int): Req.Kind.Play {
    val at = play.startPositionTicks?.let { Req.Start.At(PlaybackTicks.ticksToMs(it)) } ?: Req.Start.Resume
    val hinted = play.mediaSourceId != null || play.audioStreamIndex != null || play.subtitleStreamIndex != null
    val tracks = TrackRequest(play.mediaSourceId, play.audioStreamIndex, play.subtitleStreamIndex).takeIf { hinted }
    val queue = RemoteQueue(order, start).takeIf { order.size > 1 }
    return Req.Kind.Play(order[start], at, tracks, queue)
}

/** Playstate and track commands: the own player, else own music, never a parked session (R5). [music] `null`: music has no such command. */
private fun transport(at: Where, video: VideoOp.Session, music: MusicCall?): RemoteAction = when (at.target) {
    Target.Nothing -> drop(DropWhy.NothingLive)
    Target.Video -> if (at.opening) drop(DropWhy.NotReady) else RemoteAction.Video(video)
    Target.Music -> music?.let { RemoteAction.Music(it) } ?: drop(DropWhy.NotApplicable)
    Target.Parked -> drop(DropWhy.NotCommandable)
}

/** OSD, stats and fullscreen toggles: only an own player, [mini] what it does in PiP (`null`: nothing to toggle there). */
private fun playerToggle(at: Where, full: VideoOp, mini: VideoOp?): RemoteAction = when (at.target) {
    Target.Nothing, Target.Music -> drop(DropWhy.NothingLive)
    Target.Parked -> drop(DropWhy.NotCommandable)
    Target.Video -> when {
        at.opening -> drop(DropWhy.NotReady)
        at.full -> RemoteAction.Video(full)
        else -> mini?.let { RemoteAction.Video(it) } ?: drop(DropWhy.NotApplicable)
    }
}

/** docs/31 §4.5: browsing ops need our window on top; under a full-screen player they are ignored. */
private fun browse(at: Where, op: UiOp): RemoteAction = when {
    at.hidden -> drop(DropWhy.Background)
    at.full -> drop(DropWhy.FullscreenPlayer)
    at.onTop -> RemoteAction.Ui(op)
    else -> drop(DropWhy.Background)
}

/** docs/31 §4.5: in a full-screen player the player owns the keys. */
private fun key(at: Where, key: RemoteKey): RemoteAction = when {
    at.hidden -> drop(DropWhy.Background)
    at.full -> if (at.target == Target.Video) RemoteAction.Video(VideoOp.Key(key)) else drop(DropWhy.NotCommandable)
    at.onTop -> RemoteAction.Ui(UiOp.Key(key))
    else -> drop(DropWhy.Background)
}

private fun unlessHidden(at: Where, action: RemoteAction): RemoteAction = if (at.hidden) drop(DropWhy.Background) else action

/** docs/31 §7: a volume change, then an immediate report from the own session it is heard in. */
private fun volume(at: Where, op: VolumeOp): RemoteAction {
    val report = when {
        at.opening -> null
        at.target == Target.Video -> RemoteAction.Video(VideoOp.Report)
        at.target == Target.Music -> RemoteAction.Music(MusicCall.Report)
        else -> null
    }
    return unlessHidden(at, RemoteAction.Volume(op, report))
}

private fun routeGeneral(command: GeneralCommandFfi, at: Where): RemoteAction = when (command) {
    is GeneralCommandFfi.SetAudioStreamIndex -> transport(at, VideoOp.Track(TrackKindFfi.AUDIO, command.index), null)
    is GeneralCommandFfi.SetSubtitleStreamIndex -> transport(at, VideoOp.Track(TrackKindFfi.SUBTITLE, command.index), null)
    is GeneralCommandFfi.DisplayContent -> RemoteAction.Arrive(Req.Kind.OpenDetail(command.itemId, library = command.itemType in LIBRARY_TYPES))
    is GeneralCommandFfi.DisplayMessage -> unlessHidden(at, RemoteAction.Toast(toastText(command.header, command.text)))
    // docs/31 §4.5: typed into a field on top, else shown as a message.
    is GeneralCommandFfi.SendString -> when {
        at.hidden -> drop(DropWhy.Background)
        at.onTop -> RemoteAction.Ui(UiOp.Text(command.text))
        else -> RemoteAction.Toast(toastText(null, command.text))
    }
    is GeneralCommandFfi.SetVolume -> volume(at, VolumeOp.Set(command.level.toInt()))
    GeneralCommandFfi.VolumeUp -> volume(at, VolumeOp.Up)
    GeneralCommandFfi.VolumeDown -> volume(at, VolumeOp.Down)
    GeneralCommandFfi.Mute -> volume(at, VolumeOp.Mute)
    GeneralCommandFfi.Unmute -> volume(at, VolumeOp.Unmute)
    GeneralCommandFfi.ToggleMute -> volume(at, VolumeOp.ToggleMute)
    GeneralCommandFfi.MoveUp -> key(at, RemoteKey.Up)
    GeneralCommandFfi.MoveDown -> key(at, RemoteKey.Down)
    GeneralCommandFfi.MoveLeft -> key(at, RemoteKey.Left)
    GeneralCommandFfi.MoveRight -> key(at, RemoteKey.Right)
    GeneralCommandFfi.Select -> key(at, RemoteKey.Select)
    GeneralCommandFfi.Back -> key(at, RemoteKey.Back)
    GeneralCommandFfi.PageUp -> key(at, RemoteKey.PageUp)
    GeneralCommandFfi.PageDown -> key(at, RemoteKey.PageDown)
    GeneralCommandFfi.PreviousLetter -> browse(at, UiOp.PreviousLetter)
    GeneralCommandFfi.NextLetter -> browse(at, UiOp.NextLetter)
    GeneralCommandFfi.GoHome -> browse(at, UiOp.Home)
    GeneralCommandFfi.GoToSearch -> browse(at, UiOp.Search)
    GeneralCommandFfi.GoToSettings -> browse(at, UiOp.Settings)
    GeneralCommandFfi.ToggleContextMenu -> browse(at, UiOp.ContextMenu)
    GeneralCommandFfi.ToggleOsd -> playerToggle(at, VideoOp.ToggleOsd, null)
    GeneralCommandFfi.ToggleOsdMenu -> playerToggle(at, VideoOp.ToggleOsdMenu, null)
    GeneralCommandFfi.ToggleStats -> playerToggle(at, VideoOp.ToggleStats, null)
    GeneralCommandFfi.ToggleFullscreen -> playerToggle(at, VideoOp.EnterMini, VideoOp.Expand)
    is GeneralCommandFfi.SetRepeatMode -> musicMode(at, TransportOp.Repeat(repeatOf(command.mode)))
    is GeneralCommandFfi.SetShuffle -> musicMode(at, TransportOp.Shuffle(command.mode == RemoteShuffleFfi.SHUFFLED))
}

/** docs/31 §4.5: repeat and shuffle are music's; a video player ignores them. */
private fun musicMode(at: Where, op: TransportOp): RemoteAction = when (at.target) {
    Target.Nothing -> drop(DropWhy.NothingLive)
    Target.Video -> drop(DropWhy.NotApplicable)
    Target.Music -> RemoteAction.Music(MusicCall.Transport(op))
    Target.Parked -> drop(DropWhy.NotCommandable)
}

private fun repeatOf(mode: RemoteRepeatFfi): RepeatMode = when (mode) {
    RemoteRepeatFfi.OFF -> RepeatMode.Off
    RemoteRepeatFfi.ALL -> RepeatMode.All
    RemoteRepeatFfi.ONE -> RepeatMode.One
}

/** docs/31 §4.3: DisplayContent types that open a library rather than a detail page. */
private val LIBRARY_TYPES = setOf("CollectionFolder", "UserView")

private const val TOAST_MAX_CHARS = 200

/** docs/31 §4.3: `Header: Text`, verbatim, cut at 200 characters. */
internal fun toastText(header: String?, text: String): String = (if (header.isNullOrEmpty()) text else "$header: $text").take(TOAST_MAX_CHARS)

/** docs/31 §4.2: music's seek step for Rewind and FastForward. */
private const val MUSIC_SKIP_MS = 10_000L

/** docs/31 §4.2: the music event for [command] against [now]; `null` when a relative seek has no position to start from. */
internal fun musicEventFor(command: PlaystateCommandFfi, now: MusicNow?): MusicEvent? = when (command) {
    PlaystateCommandFfi.Pause -> MusicEvent.Transport(TransportOp.Pause)
    PlaystateCommandFfi.Unpause -> MusicEvent.UserPlay
    PlaystateCommandFfi.PlayPause -> if (now?.paused == true) MusicEvent.UserPlay else MusicEvent.Transport(TransportOp.Pause)
    is PlaystateCommandFfi.Seek -> MusicEvent.Transport(TransportOp.SeekTo(PlaybackTicks.ticksToMs(command.positionTicks)))
    PlaystateCommandFfi.NextTrack -> MusicEvent.Transport(TransportOp.Next)
    PlaystateCommandFfi.PreviousTrack -> MusicEvent.Transport(TransportOp.Previous)
    PlaystateCommandFfi.Rewind -> now?.let { MusicEvent.Transport(TransportOp.SeekTo((it.positionMs - MUSIC_SKIP_MS).coerceAtLeast(0L))) }
    PlaystateCommandFfi.FastForward -> now?.let { MusicEvent.Transport(TransportOp.SeekTo((it.positionMs + MUSIC_SKIP_MS).coerceAtMost(it.durationMs))) }
    PlaystateCommandFfi.Stop -> MusicEvent.Stop
}

/**
 * docs/31 §7, §4.1.1: the server streams [choice] shows, as a report states them (a pick's stream,
 * else what the plan delivers or burns; `null` where unknown, subtitle -1 for off), and [list].
 */
internal fun remoteFacts(choice: TrackChoice, list: RemoteList): ReportFactsFfi {
    val reported = (list as? RemoteList.Playing)?.let { ReportListFfi(it.ids, it.at.toUInt()) }
    val p = choice.plan ?: return ReportFactsFfi.Video(null, null, reported)
    val audio = when (val pick = choice.audio) {
        null -> p.delivered
        AudioPick.NoAudio -> null
        is AudioPick.Track -> audioIndex(p, pick)
    }
    val subtitle = when (val pick = choice.text) {
        null, TextPick.Leave -> p.burned
        TextPick.Off -> NO_SUBTITLE_STREAM
        is TextPick.Sidecar -> pick.index
        is TextPick.Track -> subtitleIndex(p, pick)
    }
    return ReportFactsFfi.Video(audio, subtitle, reported)
}

/** docs/31 §4.1.1, §7: a session new to the core hears [facts] at once only when it plays under a list; [sent] is the last facts sent and their session. */
internal fun reportsAtOnce(sent: Pair<String, ReportFactsFfi>?, session: String, facts: ReportFactsFfi): Boolean =
    sent?.first != session && (facts as? ReportFactsFfi.Video)?.list != null

/** docs/31 §4.2-§4.5: [op] against the live session; returns why it dropped, `null` once applied and reported. */
internal fun PlaybackViewModel.remote(op: VideoOp.Session): DropWhy? {
    when (op) {
        is VideoOp.Playstate -> playstate(op.command)?.let { return it }
        // docs/31 §4.3: the pick goes through the picker's own gates.
        is VideoOp.Track -> {
            val row = remoteRowFor(op.kind, op.index) ?: return DropWhy.NotApplicable
            if (op.kind == TrackKindFfi.AUDIO) chooseAudio(row) else chooseSubtitle(row)
        }
        // docs/31 §4.1.1: the group's queue is not a remote list; a list change reports itself.
        is VideoOp.Enqueue -> return sendList(RemoteListEvent.Enqueue(op.ids, op.mode, liveSession?.itemId))
        VideoOp.ToggleStats -> if (state.value.statsSheetLive != null) closeSurface() else openInfoOverlay()
        VideoOp.Report -> Unit
    }
    reportRemote()
    return null
}

/** docs/31 §4.2: the same view-model calls the media keys make; why it dropped, `null` once applied. */
private fun PlaybackViewModel.playstate(command: PlaystateCommandFfi): DropWhy? {
    when (command) {
        PlaystateCommandFfi.Pause -> pause()
        PlaystateCommandFfi.Unpause -> unpause()
        PlaystateCommandFfi.PlayPause -> togglePlayPause()
        is PlaystateCommandFfi.Seek -> seekToPositionMs(PlaybackTicks.ticksToMs(command.positionTicks))
        PlaystateCommandFfi.Rewind -> seek(-state.value.skipBackMs, resolveTrickplay = false)
        PlaystateCommandFfi.FastForward -> seek(state.value.skipForwardMs, resolveTrickplay = false)
        PlaystateCommandFfi.NextTrack -> return playNextEpisode()
        PlaystateCommandFfi.PreviousTrack -> return playPrevious()
        PlaystateCommandFfi.Stop -> stopFromRemote()
    }
    return null
}

/** The drop diag word of a video Play, shared with the view model's refused list. */
internal const val PLAY_VIDEO_DIAG = "play_video"
internal const val DISPLAY_CONTENT_DIAG = "display_content"

/** A short fixed name per command for the drop diag line, never its arguments (docs/21 §2.3). */
internal fun diagName(command: RemoteCommandFfi): String = when (command) {
    is RemoteCommandFfi.Play -> if (command.play.items is RemoteItems.Video) PLAY_VIDEO_DIAG else "play_audio"
    is RemoteCommandFfi.Playstate -> when (command.command) {
        PlaystateCommandFfi.Stop -> "stop"
        PlaystateCommandFfi.Pause -> "pause"
        PlaystateCommandFfi.Unpause -> "unpause"
        PlaystateCommandFfi.PlayPause -> "play_pause"
        is PlaystateCommandFfi.Seek -> "seek"
        PlaystateCommandFfi.Rewind -> "rewind"
        PlaystateCommandFfi.FastForward -> "fast_forward"
        PlaystateCommandFfi.NextTrack -> "next_track"
        PlaystateCommandFfi.PreviousTrack -> "previous_track"
    }
    is RemoteCommandFfi.General -> when (command.command) {
        is GeneralCommandFfi.SetAudioStreamIndex -> "set_audio"
        is GeneralCommandFfi.SetSubtitleStreamIndex -> "set_subtitle"
        is GeneralCommandFfi.DisplayContent -> DISPLAY_CONTENT_DIAG
        is GeneralCommandFfi.DisplayMessage -> "display_message"
        is GeneralCommandFfi.SendString -> "send_string"
        is GeneralCommandFfi.SetVolume, GeneralCommandFfi.VolumeUp, GeneralCommandFfi.VolumeDown,
        GeneralCommandFfi.Mute, GeneralCommandFfi.Unmute, GeneralCommandFfi.ToggleMute -> "volume"
        GeneralCommandFfi.MoveUp, GeneralCommandFfi.MoveDown, GeneralCommandFfi.MoveLeft, GeneralCommandFfi.MoveRight,
        GeneralCommandFfi.Select, GeneralCommandFfi.Back, GeneralCommandFfi.PageUp, GeneralCommandFfi.PageDown -> "key"
        GeneralCommandFfi.PreviousLetter, GeneralCommandFfi.NextLetter -> "letter"
        GeneralCommandFfi.GoHome, GeneralCommandFfi.GoToSearch, GeneralCommandFfi.GoToSettings -> "go"
        GeneralCommandFfi.ToggleContextMenu -> "context_menu"
        GeneralCommandFfi.ToggleOsd, GeneralCommandFfi.ToggleOsdMenu -> "toggle_osd"
        GeneralCommandFfi.ToggleStats -> "toggle_stats"
        GeneralCommandFfi.ToggleFullscreen -> "toggle_fullscreen"
        is GeneralCommandFfi.SetRepeatMode -> "repeat"
        is GeneralCommandFfi.SetShuffle -> "shuffle"
    }
}

/**
 * docs/31 §9: the core's one remote listener and the executor behind it. [onCommand] runs on a core
 * runtime thread, so it only posts; each command then runs in one Main turn (snapshot, route, act),
 * and nothing runs until a command arrives (§2).
 */
internal class RemoteControlBridge(private val context: Context) : RemoteControlListener {
    private val main = Handler(Looper.getMainLooper())
    /** The stream volume remote commands move; its [SystemVolume.adjustable] also decides whether they are advertised (docs/31 §4.4). */
    val volume by lazy { SystemVolume(context) }

    /** Main-only: MainActivity's port (docs/31 §4.5); [RemoteUi.Absent] until it attaches one. */
    var ui: RemoteUi = RemoteUi.Absent

    override fun onCommand(epoch: ULong, command: RemoteCommandFfi) {
        main.post { dispatch(epoch, command) }
    }

    /** docs/31 §4.4: read on a core thread as each report is built. */
    override fun volume(): VolumeFfi = volume.read()

    private fun dispatch(epoch: ULong, command: RemoteCommandFfi) {
        val tracker = playbackActivityTracker
        val live = remoteLive(
            tracker.state.value,
            tracker.liveHandle()?.session?.invoke(),
            AppGraph.musicOwnerIfStarted()?.state?.value?.phase,
            AppGraph.gateway.accountEpochs.value.epoch,
        )
        dropped(diagName(command), act(route(epoch, command, live), tracker) ?: return)
    }

    /** docs/31 Seam 1 R3: the one diag line a dropped command leaves, [cmd] a fixed [diagName] word. */
    fun dropped(cmd: String, why: DropWhy) = AppGraph.diag.event("remote.drop") {
        tag("cmd", cmd)
        tag("why", why.tag)
    }

    /** Runs [action]; returns why it dropped, `null` once applied. */
    private fun act(action: RemoteAction, tracker: PlaybackActivityTracker): DropWhy? {
        when (action) {
            is RemoteAction.Drop -> return action.why
            is RemoteAction.Arrive -> tracker.arrive(action.kind, Req.Origin.Remote)
            is RemoteAction.Video -> return runVideo(tracker.liveHandle(), action.op)
            is RemoteAction.Music -> return music(action.call)
            is RemoteAction.Volume -> {
                if (!volume.adjustable) return DropWhy.NotApplicable
                volume.apply(action.op)
                action.report?.let { act(it, tracker) }
            }
            is RemoteAction.Toast -> toast(action.text)
            is RemoteAction.Ui -> if (!ui.apply(action.op)) {
                // docs/31 §4.5: with no field to type into, a sent string is shown as a message.
                val text = (action.op as? UiOp.Text)?.text ?: return DropWhy.NotApplicable
                toast(toastText(null, text))
            }
        }
        return null
    }

    /** docs/31 §2: only a play request builds the music owner; transport needs one already running. */
    private fun music(call: MusicCall): DropWhy? {
        when (call) {
            is MusicCall.Play -> AppGraph.music.play(call.source, call.start, call.shuffled)
            is MusicCall.Enqueue -> AppGraph.music.enqueue(call.mode, call.source, title = null)
            is MusicCall.Playstate -> {
                val owner = AppGraph.musicOwnerIfStarted() ?: return DropWhy.NothingLive
                owner.remote(musicEventFor(call.command, owner.now.value) ?: return DropWhy.NotReady)
            }
            is MusicCall.Transport -> (AppGraph.musicOwnerIfStarted() ?: return DropWhy.NothingLive).remote(MusicEvent.Transport(call.op))
            MusicCall.Report -> (AppGraph.musicOwnerIfStarted() ?: return DropWhy.NothingLive).reportRemote()
        }
        return null
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/**
 * docs/31 §4.5: [key] as a press and release on [dispatch], our own window's key path, so focus rules
 * apply unchanged; true when either half was consumed. Back goes to [back], the window's back dispatcher,
 * since a synthetic KEYCODE_BACK never reaches predictive back (targetSdk 36).
 */
internal fun pressKey(key: RemoteKey, dispatch: (KeyEvent) -> Boolean, back: () -> Unit, ambient: (KeyEvent) -> Boolean = { false }): Boolean {
    // docs/29 §6: a Back that ambient takes only ends ambient, as the physical key does.
    if (key == RemoteKey.Back) return true.also { if (!offerPress(key.code, ambient)) back() }
    return offerPress(key.code, dispatch)
}

/** docs/31 §4.5, docs/29 §6: every remote op but Back (offered in [pressKey]) first ends a drawing ambient, so the op itself acts. */
internal fun endAmbientBefore(op: UiOp, ambient: (KeyEvent) -> Boolean) {
    if (op != UiOp.Key(RemoteKey.Back)) endAmbient(ambient)
}

/** docs/29 §6: a key no screen maps, offered to [ambient], ends a drawing ambient and changes nothing else. */
internal fun endAmbient(ambient: (KeyEvent) -> Boolean) {
    offerPress(KeyEvent.KEYCODE_UNKNOWN, ambient)
}

/** A press and release of [code] offered to [to]; true when it consumed either half. */
private fun offerPress(code: Int, to: (KeyEvent) -> Boolean): Boolean {
    val now = SystemClock.uptimeMillis()
    val down = to(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
    val up = to(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    return down || up
}

/** docs/31 §4.2: the live handle's answer, `null` once applied; no handle is nothing live. */
internal fun runVideo(handle: PlayerHandle?, op: VideoOp): DropWhy? {
    val remote = handle?.remote ?: return DropWhy.NothingLive
    return remote(op)
}
