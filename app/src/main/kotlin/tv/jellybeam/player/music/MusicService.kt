@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // ForwardingPlayer, DefaultShuffleOrder, ResolvingDataSource, renderers

package tv.jellybeam.player.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import tv.jellybeam.AppGraph
import tv.jellybeam.MainActivity
import tv.jellybeam.player.AudioDecoderPreferences
import tv.jellybeam.player.CancelingDataSourceFactory
import tv.jellybeam.player.JellybeamRenderersFactory
import tv.jellybeam.player.MUSIC_COMMANDS
import tv.jellybeam.player.NetworkAwareLoadErrorHandlingPolicy
import tv.jellybeam.player.ReconnectPolicy
import tv.jellybeam.player.TrackingCallFactory
import tv.jellybeam.player.allowedCommands
import tv.jellybeam.player.grantedCommands
import uniffi.jellybeam_core.AudioStream
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

private const val EXTRA_TICKET = "tv.jellybeam.music.ticket"
private const val EXTRA_ORIGIN = "tv.jellybeam.music.origin"

/** A queue entry plays under this scheme until its plan resolves it, so a long queue asks the server only as it plays. */
private const val ITEM_SCHEME = "jellybeam-music"

/** Music brief §1.3: plans are asked for this many tracks ahead of the playing one. */
private const val PREFETCH_AHEAD = 20
private const val TICKS_PER_MS = 10_000L
private const val ARTWORK_WIDTH = 512u

/** Not the video session's default id, which a closing player may still hold. */
private const val SESSION_ID = "music"

/** Starts and stops [MusicService]; a plain start from the foreground app, which Media3 promotes to foreground once it plays. */
internal class AndroidMusicServiceHost(private val context: Context) : MusicServiceHost {
    override fun start(ticket: Long) {
        checkNotNull(context.startService(Intent(context, MusicService::class.java).putExtra(EXTRA_TICKET, ticket))) { "music service missing" }
    }

    override fun stop() {
        context.stopService(Intent(context, MusicService::class.java))
    }
}

/**
 * Music brief §1.3: plays a music queue in the background with its own player, apart from the video
 * player. Each instance attaches to the owner at its create as instance `g`; the owner decides every
 * transition and this only executes them and reports what the player did, tagged with the session it plays.
 */
class MusicService : MediaSessionService() {
    private var owner: MusicPlaybackOwner? = null
    private var g = 0L
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var sid = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Its spent-recovery latch is reset per track and per retry, as the video player resets it per item. */
    private val loadPolicy = NetworkAwareLoadErrorHandlingPolicy()

    /** Read on the loader thread by [resolve]. */
    @Volatile
    private var epoch = 0uL

    override fun onCreate() {
        super.onCreate()
        // An unused feature costs nothing: a controller binding before any music played finds no owner.
        val owner = AppGraph.musicOwnerIfStarted() ?: return stopSelf()
        this.owner = owner
        g = owner.attach(port)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val ticket = intent?.getLongExtra(EXTRA_TICKET, 0L) ?: 0L
        if (ticket > 0) owner?.send(MusicEvent.ServiceReady(g, ticket))
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        val ticks = (player?.currentPosition ?: 0L) * TICKS_PER_MS
        owner?.detached(g, ticks)
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        player?.release()
        player = null
    }

    private val port = object : MusicPlayerPort {
        override fun build(session: MusicSession) {
            val owner = owner ?: return
            val exo = buildMusicPlayer()
            exo.addListener(listener)
            player = exo
            load(exo, session)
            val media = MediaSession.Builder(this@MusicService, MusicRoutedPlayer(exo, owner::send))
                .setId(SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(this@MusicService, 0, Intent(this@MusicService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                            grantedCommands(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build(), MUSIC_COMMANDS)
                    },
                )
                .build()
            addSession(media)
            this@MusicService.session = media
        }

        override fun replaceQueue(session: MusicSession) {
            load(player ?: return, session)
        }

        override fun insert(mode: EnqueueMode, items: List<String>) {
            val exo = player ?: return
            val at = insertIndex(mode, exo.currentMediaItemIndex, exo.origins())
            exo.addMediaItems(at, items.map { mediaItem(QueueEntry(it, mode.origin)) })
            keepUserGroupNext(exo)
            prefetchAhead(exo)
        }

        override fun play() {
            player?.play()
        }

        override fun pause() {
            player?.pause()
        }

        override fun retryPrepare() {
            val exo = player ?: return
            loadPolicy.resetForNewPlayback()
            exo.prepare()
            exo.play()
        }

        override fun skipNext() {
            val exo = player ?: return
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        }

        override fun apply(op: TransportOp) {
            val exo = player ?: return
            val current = exo.currentMediaItemIndex
            when (op) {
                TransportOp.Pause -> exo.pause()
                TransportOp.Next -> exo.seekToNext()
                TransportOp.Previous -> exo.seekToPrevious()
                is TransportOp.SeekTo -> exo.seekTo(op.ms)
                is TransportOp.PlayAt -> exo.seekTo(op.index, 0L)
                is TransportOp.Shuffle -> shuffle(exo, op.on)
                is TransportOp.Repeat -> exo.repeatMode = op.mode.playerMode
                is TransportOp.Move -> queueReorder(exo.origins(), exo.playOrder(), current, exo.shuffleModeEnabled, op.from, op.to)?.let { reorder(exo, it) }
                is TransportOp.PlayNext -> queuePlayNext(exo.playOrder(), current, exo.shuffleModeEnabled, op.index)?.let { reorder(exo, it) }
                is TransportOp.Remove -> if (op.index != current && op.index in 0 until exo.mediaItemCount) exo.removeMediaItem(op.index)
                TransportOp.Clear -> clearAfterCurrent(exo.playOrder(), current).forEach(exo::removeMediaItem)
            }
        }

        override fun positionTicks(): Long = (player?.currentPosition ?: 0L) * TICKS_PER_MS

        // Posted: a Stop from the session's own controller arrives inside a session callback.
        override fun release() {
            mainHandler.post {
                releasePlayer()
                stopSelf()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val exo = player ?: return
            if (!startsNextTrack(reason, oldPosition.mediaItemIndex, newPosition.mediaItemIndex)) return
            val item = newPosition.mediaItem?.mediaId ?: return
            originOnceCurrent(exo.getMediaItemAt(newPosition.mediaItemIndex).origin)?.let { tag(exo, newPosition.mediaItemIndex, it) }
            owner?.send(MusicEvent.TrackChanged(sid, item, oldPosition.positionMs * TICKS_PER_MS))
            prefetchAhead(exo)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            if (playbackState == Player.STATE_ENDED) owner?.send(MusicEvent.QueueEnded(sid, exo.duration.coerceAtLeast(0L) * TICKS_PER_MS))
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            hlsTranscodeOf(error)?.let { return useHls(exo, it) }
            owner?.send(if (waitsForNetwork(error)) MusicEvent.NetworkLost(sid) else MusicEvent.PlayerFailed(sid, isLast = !exo.hasNextMediaItem()))
        }

        // Handoff §9: Media3 applies no served gain, so each track sets the player's volume as it becomes current.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadPolicy.resetForNewPlayback()
            val card = mediaItem?.mediaId?.let { owner?.card(it) }
            val enabled = AppGraph.settingsStore.settings.value?.normalizationEnabled == true
            player?.volume = normalizationVolume(enabled, card?.normalizationGain, card?.albumNormalizationGain)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            owner?.publish(
                g,
                MusicNow(
                    queue = player.entries(),
                    playOrder = player.playOrder(),
                    current = player.currentMediaItemIndex,
                    isPlaying = player.isPlaying,
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0L),
                    shuffle = player.shuffleModeEnabled,
                    repeat = repeatModeOf(player.repeatMode),
                    paused = !player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED,
                ),
            )
        }
    }

    private fun load(exo: ExoPlayer, session: MusicSession) {
        sid = session.sid
        epoch = session.epoch
        exo.setMediaItems(session.items.map(::mediaItem), session.start, 0L)
        // docs/29 §9 B/C: Shuffle starts a queue shuffled from its start row, Play in order.
        shuffle(exo, session.shuffled)
        exo.prepare()
        exo.play()
        prefetchAhead(exo)
    }

    /** Shuffle on draws a fresh order that plays the current entry first, with the user group right after it. */
    private fun shuffle(exo: ExoPlayer, on: Boolean) {
        if (on) {
            val current = exo.currentMediaItemIndex
            val base = listOf(current) + (0 until exo.mediaItemCount).filter { it != current }.shuffled()
            exo.setShuffleOrder(shuffleOrder(shuffledOrder(base, current, exo.origins())))
        }
        exo.shuffleModeEnabled = on
    }

    /** Media3's shuffle randomises inserted entries; the user group is lifted back to right after the playing one. */
    private fun keepUserGroupNext(exo: ExoPlayer) {
        if (exo.shuffleModeEnabled) exo.setShuffleOrder(shuffleOrder(shuffledOrder(exo.playOrder(), exo.currentMediaItemIndex, exo.origins())))
    }

    private fun prefetchAhead(exo: ExoPlayer) {
        val order = exo.playOrder()
        val at = order.indexOf(exo.currentMediaItemIndex).coerceAtLeast(0)
        owner?.prefetch(epoch, order.drop(at).take(PREFETCH_AHEAD).map { exo.getMediaItemAt(it).mediaId })
    }

    /**
     * docs/18 §1: a transcode served as HLS replaces its placeholder entries, keeping their metadata and tags,
     * then the player prepares again; the swap reorders a shuffled queue, so its play order is put back.
     */
    private fun useHls(exo: ExoPlayer, hls: HlsTranscode) {
        val order = exo.playOrder()
        for (i in 0 until exo.mediaItemCount) {
            val item = exo.getMediaItemAt(i)
            if (item.mediaId != hls.item || item.localConfiguration?.uri?.scheme != ITEM_SCHEME) continue
            exo.replaceMediaItem(i, item.buildUpon().setUri(hls.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        }
        if (exo.shuffleModeEnabled) exo.setShuffleOrder(shuffleOrder(order))
        exo.prepare()
    }

    private fun reorder(exo: ExoPlayer, move: QueueReorder) {
        exo.moveMediaItem(move.from, move.to)
        tag(exo, move.to, move.origin)
        move.order?.let { exo.setShuffleOrder(shuffleOrder(it)) }
    }

    /** Same uri, so Media3 updates the entry in place without touching its playback. */
    private fun tag(exo: ExoPlayer, index: Int, origin: QueueOrigin) {
        val item = exo.getMediaItemAt(index)
        if (item.origin == origin) return
        exo.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(originExtras(origin)).build()).build())
    }

    private fun mediaItem(entry: QueueEntry): MediaItem {
        val card = owner?.card(entry.item)
        return MediaItem.Builder()
            .setMediaId(entry.item)
            .setUri(Uri.fromParts(ITEM_SCHEME, entry.item, null))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(card?.name)
                    .setArtist(card?.artists?.joinToString(", ")?.ifEmpty { null } ?: card?.albumArtist)
                    .setAlbumTitle(card?.album)
                    .setArtworkUri(card?.let(::artworkUri))
                    .setExtras(originExtras(entry.origin))
                    .build(),
            )
            .build()
    }

    /** The song's own cover, else its album's. */
    private fun artworkUri(card: Card): Uri? {
        val primary = card.primaryTag
        val albumId = card.albumId
        val albumTag = card.albumPrimaryTag
        val (id, tag) = when {
            primary != null -> card.id to primary
            albumId != null && albumTag != null -> albumId to albumTag
            else -> return null
        }
        return AppGraph.gateway.imageUrl(id, ImageKind.PRIMARY, tag, ARTWORK_WIDTH, epoch)?.let(Uri::parse)
    }

    /** Runs on the loader thread: a placeholder entry becomes its plan's stream url, asked for now if prefetch hasn't. */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != ITEM_SCHEME) return spec
        val owner = owner ?: throw PlanUnavailable(null)
        val plan = try {
            runBlocking { owner.plan(epoch, spec.uri.schemeSpecificPart) }
        } catch (e: InterruptedException) {
            throw InterruptedIOException().apply { initCause(e) }
        } catch (e: Exception) {
            throw PlanUnavailable(e)
        }
        val url = when (val stream = plan.stream) {
            is AudioStream.DirectPlay -> stream.url
            // The placeholder's progressive source can't read a playlist; the player swaps in an HLS entry.
            is AudioStream.Transcode -> if (stream.mime == MimeTypes.APPLICATION_M3U8) throw HlsTranscode(spec.uri.schemeSpecificPart, stream.url) else stream.url
            is AudioStream.WouldTranscode, is AudioStream.Unplayable -> throw UnplayableTrack()
        }
        return spec.withUri(Uri.parse(url))
    }

    /** The video player's renderers, audio clock guard, OkHttp client and retry policy; music attributes, gapless (no pause between items), the visualizer tap (docs/30 §2). */
    private fun buildMusicPlayer(): ExoPlayer {
        val settings = AppGraph.settingsStore.settings.value
        val preferences = AudioDecoderPreferences(
            preferFfmpegTrueHd = settings?.preferFfmpegTrueHd ?: false,
            preferFfmpegDts = settings?.preferFfmpegDts ?: false,
            preferFfmpegDtsHd = settings?.preferFfmpegDtsHd ?: false,
        )
        val renderers = JellybeamRenderersFactory(this, audioDecoderPreferences = { preferences }, musicTap = owner?.tap).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        val pendingCall = ThreadLocal<Call?>()
        val http = CancelingDataSourceFactory(OkHttpDataSource.Factory(TrackingCallFactory(AppGraph.playerCallFactory, pendingCall)), pendingCall)
        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(ResolvingDataSource.Factory(http, ::resolve))
            .setLoadErrorHandlingPolicy(loadPolicy)
        return ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // The screen may sleep while music plays; the CPU and Wi-Fi stay up for the stream.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}

/**
 * Music brief §1.3: what a remote or the Now Playing card asks of the session reaches the owner as an
 * event, so play, pause, stop and next or previous track go through its reducer, not straight to the player.
 */
internal class MusicRoutedPlayer(player: Player, private val send: (MusicEvent) -> Unit) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = allowedCommands(super.getAvailableCommands(), MUSIC_COMMANDS)

    override fun isCommandAvailable(command: Int): Boolean = command in MUSIC_COMMANDS && super.isCommandAvailable(command)

    override fun play() = send(MusicEvent.UserPlay)

    override fun pause() = send(MusicEvent.Transport(TransportOp.Pause))

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun stop() = send(MusicEvent.Stop)

    override fun seekToNext() = send(MusicEvent.Transport(TransportOp.Next))

    override fun seekToNextMediaItem() = send(MusicEvent.Transport(TransportOp.Next))

    override fun seekToPrevious() = send(MusicEvent.Transport(TransportOp.Previous))

    override fun seekToPreviousMediaItem() = send(MusicEvent.Transport(TransportOp.Previous))

    override fun seekTo(positionMs: Long) = send(MusicEvent.Transport(TransportOp.SeekTo(positionMs)))

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = send(MusicEvent.Transport(TransportOp.SeekTo(positionMs)))

    override fun prepare() = Unit
}

/** The track's plan could not be fetched, so it waits for the network rather than being skipped. */
internal class PlanUnavailable(cause: Throwable?) : IOException(cause)

/** The server has no stream the Quality setting allows for this track, so it is skipped. */
internal class UnplayableTrack : IOException()

/** [item]'s plan is a transcode served as the HLS playlist at [url]. */
internal class HlsTranscode(val item: String, val url: String) : IOException()

/** The HLS transcode a load failed on, if that is why it failed. */
internal fun hlsTranscodeOf(error: Throwable?): HlsTranscode? =
    generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(16).filterIsInstance<HlsTranscode>().firstOrNull()

/** A failure that pauses for the network (Play retries) rather than skipping the track. */
internal fun waitsForNetwork(error: Throwable?): Boolean =
    ReconnectPolicy.isRecoverable(error) || generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(16).any { it is PlanUnavailable }

/**
 * Handoff §9: the volume for a track's served gain in dB, the song's own before its album's. A positive gain is
 * capped at full volume (1.0, 0 dB): the player can't amplify, and boosting would clip.
 */
internal fun normalizationVolume(enabled: Boolean, trackGainDb: Double?, albumGainDb: Double?): Float {
    val db = (trackGainDb ?: albumGainDb)?.takeIf { enabled } ?: return 1f
    return 10.0.pow(db / 20).toFloat().coerceIn(0f, 1f)
}

/** A new track report: playback moved on by itself (repeat-one included) or a seek landed on another entry. */
internal fun startsNextTrack(reason: Int, oldIndex: Int, newIndex: Int): Boolean =
    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || (reason == Player.DISCONTINUITY_REASON_SEEK && oldIndex != newIndex)

internal fun originOf(tag: String?): QueueOrigin = QueueOrigin.entries.firstOrNull { it.tag == tag } ?: QueueOrigin.Source

private fun originExtras(origin: QueueOrigin) = Bundle().apply { putString(EXTRA_ORIGIN, origin.tag) }

private val MediaItem.origin: QueueOrigin get() = originOf(mediaMetadata.extras?.getString(EXTRA_ORIGIN))

private fun Player.entries(): List<QueueEntry> = (0 until mediaItemCount).map { getMediaItemAt(it).let { item -> QueueEntry(item.mediaId, item.origin) } }

private fun Player.origins(): List<QueueOrigin> = (0 until mediaItemCount).map { getMediaItemAt(it).origin }

/** The timeline's indices in the order they play, shuffle included. */
private fun Player.playOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val order = mutableListOf<Int>()
    var i = timeline.getFirstWindowIndex(shuffleModeEnabled)
    while (i != C.INDEX_UNSET) {
        order += i
        i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return order
}

private fun shuffleOrder(order: List<Int>): ShuffleOrder = ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime())

private val RepeatMode.playerMode: Int
    get() = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

private fun repeatModeOf(mode: Int): RepeatMode = when (mode) {
    Player.REPEAT_MODE_ALL -> RepeatMode.All
    Player.REPEAT_MODE_ONE -> RepeatMode.One
    else -> RepeatMode.Off
}
