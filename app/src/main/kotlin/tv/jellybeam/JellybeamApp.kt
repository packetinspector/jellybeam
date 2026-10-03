package tv.jellybeam

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.res.ResourcesCompat
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.data.RealCoreGateway
import tv.jellybeam.diag.CrashCapture
import tv.jellybeam.diag.DiagLog
import tv.jellybeam.diag.DiagSinkBridge
import tv.jellybeam.perf.PerfAccumulator
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.player.DeviceCapsProbe
import tv.jellybeam.player.OkHttpSheetFetcher
import tv.jellybeam.player.SheetFetcher
import tv.jellybeam.player.PlayerHolder
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import uniffi.jellybeam_core.DeviceCaps
import uniffi.jellybeam_core.JellybeamCore
import uniffi.jellybeam_core.JellybeamCoreInterface
import uniffi.jellybeam_core.installDiagSink

/**
 * App-wide dependency holder -- not a DI framework, just one object holding the process's one
 * [JellybeamCore] and its [CoreGateway] wrapper. [JellybeamApp.onCreate] initializes it once;
 * everything else reads [AppGraph.gateway].
 */
object AppGraph {
    val updateAvailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val updateOwner = lazy { tv.jellybeam.updates.UpdateCoordinator(appContext) }
    val updates: tv.jellybeam.updates.UpdateCoordinator get() = updateOwner.value
    fun updateOwnerIfStarted(): tv.jellybeam.updates.UpdateCoordinator? = if (updateOwner.isInitialized()) updateOwner.value else null
    fun beforePlayback(launch: () -> Unit) {
        val owner = updateOwnerIfStarted()
        if (owner == null) launch() else owner.launchPlayback(launch)
    }

    private lateinit var appContext: Context

    lateinit var gateway: CoreGateway
        private set

    /** Cold-start head start; `null` until [init], so code built without it runs the plain path. */
    var launchWarmup: LaunchWarmup? = null
        private set

    /** docs/21 §2.2: created before [gateway] so every early diag call, including tests that
     * never call [init], has somewhere to record into; [DiagLog.event] is a no-op while disabled.
     */
    val diag: DiagLog by lazy { DiagLog(dir = File(diagBaseDir(), "diag")) }

    /** docs/21 §1.3/§2.2: the crash-capture handler. Same lazy-with-fallback reasoning as [diag];
     * [init] is what actually calls [CrashCapture.install], so a test that never calls [init]
     * never wires up the process-wide exception handler. [persistedSettings] points at the Rust
     * core's settings file so an upgrade with no marker yet still honors a persisted opt-out. */
    val crash: CrashCapture by lazy {
        CrashCapture(
            dir = File(diagBaseDir(), "diag"),
            diag = diag,
            summary = ::crashSummary,
            persistedSettings = File(File(diagBaseDir(), "jellybeam"), "settings.json"),
        )
    }

    /** [diag]/[crash]'s storage root -- [appContext] once [init] has run, else a JVM temp
     * directory (unit tests never touch the filesystem through a disabled [DiagLog]/[CrashCapture]
     * anyway). */
    private fun diagBaseDir(): File =
        if (::appContext.isInitialized) appContext.filesDir else File(System.getProperty("java.io.tmpdir", "."))

    /** The one long-lived [PlayerHolder] for the process (one player per process,
     * never per Activity), built lazily on first access.
     */
    val playerHolder: PlayerHolder by lazy { PlayerHolder(appContext, httpClient) }

    /** Trickplay sheet transport (docs/12 §11): the player's OkHttp client plus a small file
     * cache under the app cache dir. */
    val trickplayFetcher: SheetFetcher by lazy { OkHttpSheetFetcher(httpClient, File(appContext.cacheDir, "trickplay")) }

    /**
     * Shared client for the player's Direct Play range requests ([PlayerHolder.buildPlayer]):
     * connection-pooled, unlike Media3's default `DefaultHttpDataSource`. Coil doesn't build
     * its own client, so nothing else needs this pool yet.
     *
     * - `readTimeout` finite (20s): an infinite read timeout let a half-dead TCP connection
     *   stall playback forever with no error, so no retry ever fired. A trip here is the load
     *   error [tv.jellybeam.player.LoadRetryPolicy] exists to absorb.
     * - `callTimeout` stays 0: a range GET can legitimately stream for minutes.
     * - `connectTimeout` finite: a dead server should fail fast on the handshake.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Process-scoped coroutine scope, never cancelled by Activity/ViewModel teardown --
     * [tv.jellybeam.player.PlaybackViewModel] fires its exactly-once stop/abandon report here so a
     * `viewModelScope.cancel()` racing `onCleared()` can never drop it.
     */
    val processScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** Completed by [MainActivity]'s first-frame pre-draw hook; the device-caps probe waits on it. */
    private val firstFrameSignal = CompletableDeferred<Unit>()

    /** Upper bound on that wait, so a process with no drawn Activity still probes. */
    private const val FIRST_FRAME_WAIT_MS = 3_000L

    /** [MainActivity] calls this once its first frame draws. Idempotent past the first call. */
    fun notifyFirstFrame() {
        firstFrameSignal.complete(Unit)
    }

    /**
     * Idempotent. [JellybeamCore]'s constructor does real work (JNA `System.loadLibrary`, opening
     * the on-disk SQLite mirror), so it runs on [processScope] via [async] rather than blocking
     * [JellybeamApp.onCreate]; [gateway] gets the resulting [Deferred], and [RealCoreGateway] awaits
     * it internally on every call, so every call site just suspends a little longer.
     */
    fun init(context: Context) {
        if (::gateway.isInitialized) return

        appContext = context.applicationContext

        // docs/21 §2.2: diag/crash must exist before core construction so a crash there has
        // somewhere to land; this first touch builds them against the real app-private directory.
        crash.install()
        PerfLog.sink = diag::perf
        diag.event("app.start") {
            tag("version", readVersionName())
            bool("cold", true)
        }
        registerActivityLifecycleCallbacks()

        val dataDir = File(context.filesDir, "jellybeam").apply { mkdirs() }
        val coreDeferred: Deferred<JellybeamCoreInterface> = processScope.async(Dispatchers.IO) {
            val core = JellybeamCore(dataDir.absolutePath)
            PerfLog.markStartup("core.ready") // marks the end of the blocking JNA load + mirror open above
            core
        }
        val deviceCapsReady = CompletableDeferred<Unit>()
        gateway = RealCoreGateway(coreDeferred, processScope, deviceCapsReady)
        launchWarmup = LaunchWarmup(gateway, processScope)

        // Coil's ImageLoader is otherwise built lazily on the main thread by the first card
        // request, and its disk-cache journal parses under a lock on that same first access;
        // JellybeamTheme's 600/700/800 Archivo faces likewise parse on the main thread the first
        // time Home's content frame uses them. Both are touched here instead, off-main, fired
        // after core construction above rather than gating or preceding it.
        processScope.launch(Dispatchers.IO) {
            runCatching {
                Coil.imageLoader(appContext).diskCache?.openSnapshot("warm")?.close()
            }
            JellybeamTheme.fontResourceIds.forEach { id -> runCatching { ResourcesCompat.getFont(appContext, id) } }
        }

        // docs/21 §2.1/§2.2: once the core exists, wire the Rust WARN/ERROR forwarder and apply
        // this account's persisted logging/crash-reporting toggles -- read once here; the
        // Troubleshooting rows (SettingsViewModel) keep diag/crash live after that.
        processScope.launch(Dispatchers.IO) {
            coreDeferred.await()
            installDiagSink(DiagSinkBridge(diag))
            runCatching { gateway.getSettings() }.getOrNull()?.let { settings ->
                diag.setEnabled(settings.diagnosticLoggingEnabled)
                crash.enabled = settings.crashReportsEnabled
            }
        }

        // Device-caps probe (MediaCodecList walk, binder calls) runs after the first frame, or
        // after FIRST_FRAME_WAIT_MS if no Activity ever draws, so it stops competing with the
        // startup window; deviceCapsReady is the same Deferred its waiters already await.
        // runCatching keeps a JellybeamCore construction failure from killing processScope.
        processScope.launch(Dispatchers.Default) {
            try {
                withTimeoutOrNull(FIRST_FRAME_WAIT_MS) { firstFrameSignal.await() }
                PerfLog.timed("deviceCaps.probe") { runCatching { pushDeviceCaps(gateway) } }
            } finally {
                // First playback waits for this decision to be final rather than racing the probe.
                deviceCapsReady.complete(Unit)
            }
        }

        // Pre-warm the shared ExoPlayer (renderer/decoder enumeration) during idle time instead
        // of at the first real play. Waits for coreDeferred first, then posts to the main Looper
        // (required for ExoPlayer construction); playerHolder/player are both `by lazy`, so a
        // PlaybackActivity racing ahead of this is a harmless no-op, never a second construction.
        processScope.launch(Dispatchers.Default) {
            coreDeferred.await()
            Handler(Looper.getMainLooper()).postDelayed(
                { playerHolder.prewarm() },
                PLAYER_PREWARM_DELAY_MS,
            )
        }
    }

    /** [PackageManager.getPackageInfo]'s `versionName`, same read [tv.jellybeam.ui.settings
     * .AboutSectionContent] uses -- `"unknown"` on the defensive-only failure path, since
     * `app.start`'s `version` tag is a fixed-charset field, never absent. */
    private fun readVersionName(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull() ?: "unknown"

    /** [CrashCapture]'s summary provider (docs/21 §1.3): app version and hardware only, built
     * from [Build] and [android.content.pm.PackageManager] -- deliberately no [gateway] access,
     * since a crash handler must not depend on anything that could itself be mid-failure. */
    private fun crashSummary(): List<Pair<String, String>> = listOf(
        "App version" to readVersionName(),
        "Hardware" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    /** docs/21 §2.1: `app.background`/`app.foreground` from a started-Activity counter; 0->1 is
     * foregrounded, 1->0 is backgrounded -- the point [diag.flush] is guaranteed a last chance
     * before the process can be killed. */
    private fun registerActivityLifecycleCallbacks() {
        var startedActivityCount = 0
        (appContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount == 0) diag.event("app.foreground")
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount == 0) {
                    diag.event("app.background")
                    diag.flush()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** [AppGraph.init]'s pre-warm delay -- long enough to land well clear of Home's own initial
 * load/paint, short enough that it's done long before any plausible first "press Play".
 */
private const val PLAYER_PREWARM_DELAY_MS = 3_000L

/** Probes this device's decoder capabilities and pushes them to [gateway]; extracted out of
 * [AppGraph.init] so it's unit-testable against [tv.jellybeam.data.FakeCoreGateway].
 */
suspend fun pushDeviceCaps(gateway: CoreGateway, probe: () -> DeviceCaps = DeviceCapsProbe::probe) {
    gateway.setDeviceCaps(probe())
}

/** Coil's disk cache subdirectory (under [Context.getCacheDir]) and cap -- kept modest since a TV's
 * internal storage is shared with the mirror DB and sideloaded APKs.
 */
private const val IMAGE_DISK_CACHE_DIR_NAME = "images"
private const val IMAGE_DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

/** Fraction of the process' available memory Coil's in-memory bitmap cache may hold -- kept low
 * (2 GB-RAM device class) so art thumbnails don't crowd out the mirror/player.
 */
private const val IMAGE_MEMORY_CACHE_MAX_PERCENT = 0.20

/** docs/07 §6's "Image fade 150ms" -- promised by `CardArt.kt`'s own doc comment but never actually
 * wired into a Coil config until this one.
 */
private const val IMAGE_CROSSFADE_MS = 150

class JellybeamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        // AppGraph.init returns immediately (real construction is fired onto processScope), so
        // this bracket is deliberately small; "core.ready" captures the real startup cost.
        PerfLog.markStartup("application.onCreate.start")
        // docs/10-perf-logging.md: the process's own start time, on the same atMs clock, so
        // process.start -> application.onCreate.start is readable. API 24+ only; there's no
        // equivalent call below that level.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PerfLog.markStartupAt("process.start", Process.getStartElapsedRealtime())
        }
        super.onCreate()
        AppGraph.init(this)
        PerfLog.markStartup("application.onCreate.end")
    }

    /**
     * Every art request is an opaque poster/backdrop/thumb -- [Bitmap.Config.RGB_565] halves
     * decode memory with no visible banding at TV distance. `respectCacheHeaders` is off since
     * the server's image endpoint sends no cache-friendly headers for an already-resized,
     * server-cached rendition. [perfImageEventListener] is attached unconditionally; it no-ops
     * cheaply itself rather than being gated at attachment.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(IMAGE_CROSSFADE_MS)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(IMAGE_MEMORY_CACHE_MAX_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_DISK_CACHE_DIR_NAME))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .eventListener(perfImageEventListener)
            .build()
}

/**
 * Coil [EventListener] that turns every card-art fetch into [imageLoadPerf]'s aggregated
 * summary, never a per-image log line. Pairs [onStart] with its terminal callback via
 * reference-equality [IdentityHashMap]; [ImageRequest.data] (the URL, which carries the server
 * hostname) is never logged -- only a duration/success/byte count is recorded, per this app's
 * "never log server addresses, titles, or URLs" privacy rule. The one exception is
 * [PerfLog.notifyImageUrlSucceeded] below, which reads the URL only to compare it in memory
 * against [tv.jellybeam.ui.detail.DetailScreen]'s `detail.poster`/`detail.backdrop` registrations
 * (docs/10) -- the match result, never the URL itself, is what reaches Logcat.
 */
private object PerfImageEventListener : EventListener {
    private val firstSuccess = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startTimesMs = Collections.synchronizedMap(IdentityHashMap<ImageRequest, Long>())
    private val imageLoadPerf = PerfAccumulator(label = "image.load")

    override fun onStart(request: ImageRequest) {
        if (!PerfLog.enabled) return
        startTimesMs[request] = SystemClock.elapsedRealtime()
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val startMs = startTimesMs.remove(request) ?: return
        if (PerfLog.enabled && firstSuccess.compareAndSet(false, true)) {
            PerfLog.markStartup("artwork.firstDecoded")
        }
        (request.data as? String)?.let(PerfLog::notifyImageUrlSucceeded)
        val ms = (SystemClock.elapsedRealtime() - startMs).toDouble()
        // byteCount is already computed at decode time -- a field read, not a recomputation.
        val bytes = (result.drawable as? BitmapDrawable)?.bitmap?.byteCount?.toLong() ?: 0L
        imageLoadPerf.record(durationMs = ms, success = true, sizeBytes = bytes)
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val startMs = startTimesMs.remove(request) ?: return
        val ms = (SystemClock.elapsedRealtime() - startMs).toDouble()
        imageLoadPerf.record(durationMs = ms, success = false)
    }

    override fun onCancel(request: ImageRequest) {
        startTimesMs.remove(request)
    }
}

/** [JellybeamApp.newImageLoader]'s single shared listener instance -- one [PerfAccumulator], one
 * summary stream, for every image this process ever loads.
 */
private val perfImageEventListener: EventListener = PerfImageEventListener
