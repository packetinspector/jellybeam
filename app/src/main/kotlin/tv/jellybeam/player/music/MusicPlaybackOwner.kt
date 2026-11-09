package tv.jellybeam.player.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.player.ProgressReportGate
import tv.jellybeam.player.music.MusicFlow.Phase
import tv.jellybeam.visualizer.tap.PcmTap
import uniffi.jellybeam_core.AudioEnd
import uniffi.jellybeam_core.AudioStream
import uniffi.jellybeam_core.AudioTrackPlan
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.SongSource

/** What the owner asks of one attached service instance; Main-only. */
internal interface MusicPlayerPort {
    /** Builds the player for [session] and plays it from its start row. */
    fun build(session: MusicSession)
    fun replaceQueue(session: MusicSession)
    fun insert(mode: EnqueueMode, items: List<String>)
    fun play()
    fun pause()
    fun retryPrepare()
    fun skipNext()
    fun apply(op: TransportOp)

    /** The playing track's position now, in ticks. */
    fun positionTicks(): Long

    /** Releases the player and stops this service instance. */
    fun release()
}

/** Starts and stops the service component; [start] throws when the platform refuses the start. */
internal interface MusicServiceHost {
    fun start(ticket: Long)
    fun stop()
}

/** What Now Playing shows, read from the playing service's player; [queue] is in timeline order, [playOrder] its indices as they play. */
internal data class MusicNow(
    val queue: List<QueueEntry>,
    val playOrder: List<Int>,
    val current: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val net: MusicFlow.Net = MusicFlow.Net.Online,
    /** The user paused (play-when-ready off), unlike [isPlaying], which a buffering stall also clears. */
    val paused: Boolean = false,
)

/** docs/18 §2: the playing track's position reaches the core once a second while it plays, as video's does. */
internal const val POSITION_REPORT_MS = 1_000L

/** A started service that hasn't attached by now died before its first command, so the start failed. */
internal const val SERVICE_START_TIMEOUT_MS = 10_000L

/** Plans asked for ahead of the rest: the playing track and the next two, for time to first sound and a gapless next. */
internal const val FIRST_PLANS = 3

/** The most entries one source fetch queues. */
internal const val QUEUE_FETCH_LIMIT = 2_000u

/** An artist's songs as a queue: album by album in release order is the server's year sort. */
private val ARTIST_QUEUE_SORT = GridSort(GridSortField.YEAR, descending = false)

/**
 * Music brief §1.3: the process's one music owner, built on first use by `AppGraph.music` only, so an
 * unused feature costs nothing. [send] is Main-only and the only writer of [state]; effects run after the
 * new state is published, and an event an effect sends waits for the rest of them. Every track start and
 * end reaches the core through one serial report queue, in the order the reducer issued them.
 */
internal class MusicPlaybackOwner(
    private val gateway: CoreGateway,
    parent: CoroutineScope,
    private val host: MusicServiceHost,
    /** Closes a playing video and returns once its final stop reached the core. */
    private val closeVideo: suspend () -> Unit,
    /** Called on Main each time [MusicFlow.holdsPlayback] flips. */
    private val holdsPlaybackChanged: () -> Unit,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext.job) + main)
    private val mainDispatcher = main

    /** docs/30 §2: the music output's PCM tap; idle (one volatile read per buffer) until a visualizer attaches. */
    val tap = PcmTap()

    private val _state = MutableStateFlow(MusicFlow())
    val state: StateFlow<MusicFlow> = _state.asStateFlow()

    private val _now = MutableStateFlow<MusicNow?>(null)

    /** `null` unless a session plays; the UI reads this, never the player. */
    val now: StateFlow<MusicNow?> = _now.asStateFlow()

    val holdsPlayback: Boolean get() = _state.value.holdsPlayback

    private val inbox = ArrayDeque<MusicEvent>()
    private var dispatching = false
    private var lastR = 0L
    private var lastG = 0L
    private val ports = mutableMapOf<Long, MusicPlayerPort>()
    private val fetches = mutableMapOf<Long, Job>()
    private val sessions = mutableMapOf<Long, CoroutineScope>()

    /** A service start waiting on a video to close; StopService cancels it. */
    private var starting: Job? = null

    /** Song metadata from the fetches, for the player's MediaMetadata; dropped when the session ends. */
    private val cards = mutableMapOf<String, Card>()

    /** Stream plans by account epoch and item; dropped when the session ends. */
    private val plans = mutableMapOf<Pair<ULong, String>, Deferred<AudioTrackPlan>>()

    /** The core's play session per started track, removed when its end is reported. */
    private val playSessions = mutableMapOf<TrackRef, String>()
    private var snapshot: MusicNow? = null

    private val reports = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The position ticker; runs only while a session is Active. */
    private var positionReports: Job? = null

    /** The pause state the playing track's report last heard; the core starts each track unpaused. */
    private var lastPaused = false

    /** Started by the first report, so an owner that never played sends nothing. */
    private val reportConsumer by lazy {
        scope.launch {
            for (report in reports) report()
        }
    }

    init {
        // docs/18 §2.1: every account change reaches the owner, a kept account's removal included; the owner exists only once music was asked for.
        scope.launch { gateway.accountEpochs.collect { send(MusicEvent.EpochsChanged(it.epoch, it.parked)) } }
    }

    /** Mints request `r` for [source] played from row [startIndex], or from a random row in shuffle when [shuffled]; returns it so a screen can cancel the wait. */
    fun play(source: MusicSource, startIndex: Int = 0, shuffled: Boolean = false): Long {
        val r = ++lastR
        send(MusicEvent.Play(r, source, gateway.accountEpochs.value.epoch, startIndex, shuffled))
        return r
    }

    /** Play next or Add to queue; docs/29 §8.4: the bar confirms [title] when it joins the playing session. */
    fun enqueue(mode: EnqueueMode, source: MusicSource, title: String): Long {
        val r = ++lastR
        send(MusicEvent.Enqueue(r, mode, source, gateway.accountEpochs.value.epoch, title))
        return r
    }

    /** docs/29 §8.4: the bar confirms a song added to [playlist]; [again] when it was already there. */
    fun playlistAdded(playlist: String, again: Boolean) = send(MusicEvent.Confirm(MusicNotice.AddedToPlaylist(++lastR, playlist, again)))

    fun playlistFailed(playlist: String) = send(MusicEvent.Confirm(MusicNotice.PlaylistFailed(++lastR, playlist)))

    /** Main-only. */
    fun send(event: MusicEvent) {
        inbox.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (true) {
                val next = inbox.removeFirstOrNull() ?: break
                val before = _state.value
                val step = reduce(before, next)
                _state.value = step.state
                if (step.state.phase == Phase.Idle && before.phase != Phase.Idle) forgetSession()
                if (step.state.phase is Phase.Active && before.phase !is Phase.Active) startPositionReports()
                republish()
                if (before.holdsPlayback != step.state.holdsPlayback) holdsPlaybackChanged()
                run(step.effects, (before.phase as? Phase.Active)?.g)
            }
        } finally {
            dispatching = false
        }
    }

    /**
     * docs/17 §3: a video start stops music first, then launches once every report sent before it reached
     * the core, so the video's claim never races a music track's start or end.
     */
    fun stopForVideo(launch: () -> Unit) {
        send(MusicEvent.VideoStarting)
        val drained = CompletableDeferred<Unit>()
        report { drained.complete(Unit) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drained.await()
            launch()
        }
    }

    /** A service instance attaches at its create; returns its instance id `g`. */
    fun attach(port: MusicPlayerPort): Long {
        val g = ++lastG
        ports[g] = port
        return g
    }

    /** Instance [g] is destroyed, its track at [ticks]. */
    fun detached(g: Long, ticks: Long) {
        ports.remove(g)
        send(MusicEvent.ServiceDestroyed(g, ticks))
    }

    /** Instance [g]'s player state; ignored unless [g] plays the session. */
    fun publish(g: Long, now: MusicNow) {
        val active = _state.value.phase as? Phase.Active
        if (active == null || active.g != g) return
        snapshot = now
        republish()
        if (ProgressReportGate.shouldReportPaused(lastPaused, now.paused)) {
            lastPaused = now.paused
            val ref = active.ref
            report { playSessions[ref]?.let { gateway.reportPaused(it, now.paused) } }
        }
    }

    fun card(item: String): Card? = cards[item]

    /** A favourite write on [item] succeeded: its card follows, so the song shows the new heart when it comes back. Main-only. */
    fun favoriteChanged(item: String, isFavorite: Boolean) {
        cards[item]?.let { cards[item] = it.copy(isFavorite = isFavorite) }
    }

    /** The bar showed [notice]; a newer one stays. */
    fun noticeShown(notice: MusicNotice) = send(MusicEvent.NoticeShown(notice))

    /** [item]'s stream plan on account [epoch], fetched once; safe from any thread. */
    suspend fun plan(epoch: ULong, item: String): AudioTrackPlan = withContext(mainDispatcher) { planFor(epoch, item) }.await()

    /**
     * Resolves the plans for [items] not yet asked for: the first [FIRST_PLANS] in one core call, the rest in a
     * second once it answered, so the playing track's plan never waits on the whole look-ahead.
     */
    fun prefetch(epoch: ULong, items: List<String>) {
        val missing = items.distinct().filter { (epoch to it) !in plans }
        if (missing.isEmpty()) return
        val head = missing.take(FIRST_PLANS)
        val tail = missing.drop(FIRST_PLANS)
        val first = scope.async { gateway.prepareAudioQueue(head, epoch) }
        expect(epoch, head, first)
        if (tail.isNotEmpty()) {
            expect(epoch, tail, scope.async {
                first.join()
                gateway.prepareAudioQueue(tail, epoch)
            })
        }
    }

    /** Each of [items] takes its plan from [batch], which answers them in order. */
    private fun expect(epoch: ULong, items: List<String>, batch: Deferred<List<AudioTrackPlan>>) = items.forEachIndexed { i, item ->
        plans[epoch to item] = scope.async { batch.await().getOrNull(i) ?: error("no plan") }
    }

    private fun planFor(epoch: ULong, item: String): Deferred<AudioTrackPlan> {
        prefetch(epoch, listOf(item))
        return plans.getValue(epoch to item)
    }

    private fun republish() {
        val active = _state.value.phase as? Phase.Active
        _now.value = active?.let { snapshot?.copy(net = it.net) }
    }

    private fun forgetSession() {
        positionReports?.cancel()
        positionReports = null
        lastPaused = false
        snapshot = null
        cards.clear()
        plans.clear()
    }

    /** Runs [effects] in order; a video close holds the service start behind it. [g] is the instance that played before the step. */
    private fun run(effects: List<MusicEffect>, g: Long?) {
        val atStep = if (effects.any { it.endsAtPlayer() }) g?.let { ports[it] }?.positionTicks() ?: 0L else 0L
        effects.forEachIndexed { i, effect ->
            if (effect == MusicEffect.CloseVideo) {
                val rest = effects.drop(i + 1)
                starting = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    closeVideo()
                    rest.forEach { execute(it, atStep) }
                }
                return
            }
            execute(effect, atStep)
        }
    }

    private fun execute(effect: MusicEffect, atStep: Long) {
        when (effect) {
            is MusicEffect.LaunchFetch -> {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val result = runCatchingCancellable { fetchQueue(effect.source) }
                    fetches.remove(effect.r)
                    send(fetchedEvent(effect.r, result.getOrNull(), effect.startIndex, effect.shuffled))
                }
                fetches[effect.r] = job
                job.start()
            }
            is MusicEffect.CancelFetch -> fetches.remove(effect.r)?.cancel()
            is MusicEffect.LaunchEnqueue -> sessionScope(effect.sid).launch {
                val songs = runCatchingCancellable { fetchQueue(effect.source) }.getOrNull()
                when {
                    songs == null -> send(MusicEvent.EnqueueFailed(effect.sid, effect.r))
                    songs.isNotEmpty() -> {
                        remember(songs)
                        send(MusicEvent.EnqueueFetched(effect.sid, effect.mode, songs.map { it.id }))
                    }
                }
            }
            is MusicEffect.CancelSession -> sessions.remove(effect.sid)?.cancel()
            MusicEffect.CloseVideo -> Unit
            is MusicEffect.StartService -> if (runCatchingCancellable { host.start(effect.ticket) }.isFailure) {
                send(MusicEvent.ServiceStartFailed(effect.ticket))
            } else {
                // An instance that dies before its first command never attaches; the reducer drops this once one has.
                scope.launch {
                    delay(SERVICE_START_TIMEOUT_MS)
                    send(MusicEvent.ServiceStartFailed(effect.ticket))
                }
            }
            MusicEffect.StopService -> {
                starting?.cancel()
                host.stop()
            }
            is MusicEffect.Release -> ports.remove(effect.g)?.release()
            is MusicEffect.BuildPlayer -> ports[effect.g]?.build(effect.session)
            is MusicEffect.ReplaceQueue -> ports[effect.g]?.replaceQueue(effect.session)
            is MusicEffect.Insert -> ports[effect.g]?.insert(effect.mode, effect.items)
            is MusicEffect.StartTrack -> startTrack(effect, atStep)
            is MusicEffect.End -> {
                val ticks = effect.end.at.ticks(atStep)
                report { playSessions.remove(effect.end.ref)?.let { gateway.stopPlayback(it, ticks) } }
            }
            is MusicEffect.Pause -> ports[effect.g]?.pause()
            is MusicEffect.Play -> ports[effect.g]?.play()
            is MusicEffect.RetryPrepare -> {
                // A plan that failed while the network was down is asked for again.
                plans.values.removeAll { it.isCompleted && it.getCompletionExceptionOrNull() != null }
                ports[effect.g]?.retryPrepare()
            }
            is MusicEffect.SkipNext -> ports[effect.g]?.skipNext()
            is MusicEffect.Apply -> ports[effect.g]?.apply(effect.op)
        }
    }

    /** docs/18 §2.1: the request is minted on Main as the effect runs; the report then ends the previous track in the same core call. */
    private fun startTrack(effect: MusicEffect.StartTrack, atStep: Long) {
        val session = currentSession() ?: return
        val request = gateway.mintPlaybackRequest(session.epoch)
        // A new session's first track: the snapshot still holds the queue it replaced.
        val queueIds = (snapshot?.queue?.takeIf { effect.ref.n > 0 } ?: session.items).map { it.item }
        val endTicks = effect.ends?.at?.ticks(atStep) ?: 0L
        val plan = planFor(session.epoch, effect.item)
        lastPaused = false
        report {
            val ended = effect.ends?.let { end -> playSessions.remove(end.ref)?.let { AudioEnd(it, endTicks) } }
            val track = runCatchingCancellable { plan.await() }.getOrNull()?.takeIf { it.stream.reportable }
            val started = track?.let { runCatchingCancellable { gateway.startAudioTrack(it, queueIds, request, ended) }.getOrNull() }
            if (started != null) {
                playSessions[effect.ref] = started
            } else if (ended != null) {
                // The core refuses an unplayable track before it ends the previous one.
                gateway.stopPlayback(ended.playSessionId, ended.ticks)
            }
        }
    }

    /** docs/18 §2: while a track plays, its position reaches its report once a second, queued behind its start. */
    private fun startPositionReports() {
        positionReports = scope.launch {
            while (true) {
                delay(POSITION_REPORT_MS)
                val active = _state.value.phase as? Phase.Active ?: continue
                if (!ProgressReportGate.shouldReportPosition(snapshot?.isPlaying == true)) continue
                val ticks = ports[active.g]?.positionTicks() ?: continue
                val ref = active.ref
                report { playSessions[ref]?.let { gateway.reportPosition(it, ticks) } }
            }
        }
    }

    private fun report(work: suspend () -> Unit) {
        reportConsumer
        reports.trySend { runCatchingCancellable { work() } }
    }

    private fun currentSession(): MusicSession? = when (val phase = _state.value.phase) {
        Phase.Idle -> null
        is Phase.Starting -> phase.session
        is Phase.Active -> phase.session
    }

    private fun sessionScope(sid: Long): CoroutineScope =
        sessions.getOrPut(sid) { CoroutineScope(SupervisorJob(scope.coroutineContext.job) + mainDispatcher) }

    private fun remember(songs: List<Card>) = songs.forEach { cards[it.id] = it }

    private fun fetchedEvent(r: Long, songs: List<Card>?, startIndex: Int, shuffled: Boolean): MusicEvent = when {
        songs == null -> MusicEvent.FetchFailed(r)
        songs.isEmpty() -> MusicEvent.FetchEmpty(r)
        else -> {
            remember(songs)
            MusicEvent.Fetched(r, songs.map { it.id }, fetchStartRow(startIndex, songs.size, shuffled, Random.Default))
        }
    }

    /** `null` when the source can't be read now (the mirror isn't open, or the call failed). */
    private suspend fun fetchQueue(source: MusicSource): List<Card>? = when (source) {
        is MusicSource.Album -> gateway.albumTracks(source.id, 0u, QUEUE_FETCH_LIMIT)
        is MusicSource.Artist -> gateway.musicSongs(SongSource.Artist(source.id, ARTIST_QUEUE_SORT), 0u, QUEUE_FETCH_LIMIT)
        is MusicSource.Songs -> gateway.musicSongs(source.source, 0u, QUEUE_FETCH_LIMIT)
        is MusicSource.Playlist -> gateway.playlistItems(source.id, 0u, QUEUE_FETCH_LIMIT).map { it.card }
        is MusicSource.Mix -> gateway.instantMix(source.seed)
        is MusicSource.Single -> listOf(source.song)
    }
}

private fun MusicEffect.endsAtPlayer(): Boolean = when (this) {
    is MusicEffect.End -> end.at == EndAt.PlayerPosition
    is MusicEffect.StartTrack -> ends?.at == EndAt.PlayerPosition
    else -> false
}

/** [EndAt.PlayerPosition] is where the player stood as the step began, before a replaced queue moved it. */
private fun EndAt.ticks(atStep: Long): Long = when (this) {
    is EndAt.Ticks -> ticks
    EndAt.PlayerPosition -> atStep
}

/** A plan the core reports a start for; the player skips the rest. */
internal val AudioStream.reportable: Boolean
    get() = when (this) {
        is AudioStream.DirectPlay, is AudioStream.Transcode -> true
        is AudioStream.WouldTranscode, is AudioStream.Unplayable -> false
    }
