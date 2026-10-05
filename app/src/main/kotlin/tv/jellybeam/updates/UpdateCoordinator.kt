package tv.jellybeam.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.jellybeam.AppGraph
import uniffi.jellybeam_core.AppUpdater
import uniffi.jellybeam_core.UpdateSnapshot

class UpdateCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> Handler(Looper.getMainLooper()).post { markUnavailable() } })
    private val ready = CompletableDeferred<Unit>()
    private val updater = scope.async(Dispatchers.IO) { AppGraph.gateway.updater(installedFacts(context)) }
    private val installer = AndroidUpdateInstaller(context)
    private val mutable = MutableStateFlow<UpdateSnapshot?>(null)
    val state = mutable.asStateFlow()
    private var automaticPreference = AutomaticPreference()
    private val automatic = MutableStateFlow(automaticPreference.enabled)
    val automaticChecks = automatic.asStateFlow()
    private var activity = WeakReference<Activity>(null)
    private val unavailableFlag = MutableStateFlow(false)
    val isUnavailable = unavailableFlag.asStateFlow()
    private var unavailable: Boolean
        get() = unavailableFlag.value
        set(value) { unavailableFlag.value = value }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var resumed = false
    private var pageVisible = false
    private var playback = tv.jellybeam.player.playbackActivityTracker.isActive
    private var playbackStarting = false
    private var authorization: ULong? = null
    private var permissionReturn = false
    private val permission = MutableStateFlow(false)
    val permissionRequired = permission.asStateFlow()
    private var permissionNeeded: Boolean
        get() = permission.value
        set(value) { permission.value = value }
    private var work: Job? = null
    private var verifying: ULong? = null
    private var staging: ULong? = null
    private var pendingConfirmation: Intent? = null
    private var autoCheck: Job? = null

    init {
        scope.launch {
            try {
                val loadRevision = automaticPreference.revision
                val stored = withContext(Dispatchers.IO) { AppGraph.gateway.getSettings().automaticUpdateChecks }
                automaticPreference = automaticPreference.loaded(stored, loadRevision)
                automatic.value = automaticPreference.enabled
                val core = updater.await()
                reconcile(core)
                ready.complete(Unit)
                poll(core)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                markUnavailable()
            }
        }
    }
    private fun nudge() { wake.trySend(Unit) }
    // docs/26 §5: fast tick only while work is visible; events wake the slow tick.
    private suspend fun poll(core: AppUpdater) {
        var seenGeneration: ULong? = null
        var seenRevision: ULong? = null
        while (currentCoroutineContext().isActive && !unavailable) {
            val snapshot = withContext(Dispatchers.IO) { core.snapshot() }
            if (unavailable) return
            if (shouldPublish(seenGeneration, seenRevision, snapshot.generation, snapshot.revision)) {
                seenGeneration = snapshot.generation
                seenRevision = snapshot.revision
                mutable.value = snapshot
                if (AppGraph.updateAvailable.value != snapshot.availableNotice) AppGraph.updateAvailable.value = snapshot.availableNotice
            }
            drive(snapshot, core)
            withTimeoutOrNull(pollIntervalMs(snapshot.phase, resumed, playback)) { wake.receive() }
        }
    }
    private fun drive(snapshot: UpdateSnapshot, core: AppUpdater) {
        when (snapshot.phase) {
            "Verifying" -> if (verifying != snapshot.generation && work?.isActive != true) {
                verifying = snapshot.generation
                work = scope.launch {
                    val archive = withContext(Dispatchers.IO) {
                        runCatching { verifyArchive(context, File(snapshot.apkPath), snapshot.profilePath?.let(::File)) }.getOrNull()
                    }
                    withContext(Dispatchers.IO) {
                        core.verified(snapshot.generation, uniffi.jellybeam_core.VerifiedUpdateFacts(
                            archive?.packageName ?: "", archive?.code?.toULong() ?: 0u,
                            archive?.minSdk?.toUInt() ?: 0u, archive?.signer ?: "", archive?.abis ?: emptyList(), archive != null))
                    }
                    nudge()
                }
            }
            "Ready" -> if (authorization == snapshot.generation && eligible() && !permissionNeeded) continueInstall(snapshot)
            "Staging" -> if (staging != snapshot.generation && work?.isActive != true) {
                staging = snapshot.generation
                stage(snapshot, core)
            }
        }
    }
    private fun markUnavailable() {
        unavailable = true
        authorization = null
        playbackStarting = false
        pendingConfirmation = null
        work?.cancel()
        AppGraph.updateAvailable.value = false
        ready.complete(Unit)
        mutable.value = UpdateSnapshot(0u, 0u, "Error", null, 0u, "", "", 0u, 0u, "", null, false, 0u, "")
    }
    private fun eligible(): Boolean = !unavailable && updateForeground(resumed, pageVisible, playback, playbackStarting)
    fun resume(activity: Activity) {
        this.activity = WeakReference(activity)
        resumed = true
        if (permissionReturn) {
            permissionReturn = false
            permissionNeeded = !installer.allowed()
            if (permissionNeeded) authorization = null
        }
        if (eligible()) launchConfirmation()
        nudge()
    }
    fun pause() {
        resumed = false
        autoCheck?.cancel()
        val plan = pausePlan(permissionReturn, installer.committedGeneration != null, mutable.value?.phase.orEmpty())
        if (plan.dropAuthorization) authorization = null
        if (plan.cancelInstallPreparation) cancel()
    }
    fun pageVisible(visible: Boolean) {
        pageVisible = visible
        if (!visible) authorization = null else nudge()
    }
    fun browsingReady(quietDelayMs: Long = 10_000) {
        autoCheck?.cancel()
        autoCheck = scope.launch {
            delay(quietDelayMs)
            ready.await()
            if (!unavailable && resumed && !playback && !playbackStarting && automatic.value) {
                withContext(Dispatchers.IO) { updater.await().check(false) }
                nudge()
            }
        }
    }
    fun check() {
        if (unavailable || !resumed || playback || playbackStarting) return
        scope.launch { withContext(Dispatchers.IO) { updater.await().check(true) }; nudge() }
    }
    /** The Settings view model owns persistence; this only mirrors the choice for scheduling. */
    fun setAutomatic(value: Boolean) {
        automaticPreference = automaticPreference.edit(value)
        automatic.value = automaticPreference.enabled
        if (!value) autoCheck?.cancel()
    }
    fun update() {
        val snapshot = mutable.value ?: return
        if (!eligible()) return
        authorization = snapshot.generation
        permissionNeeded = false
        if (installer.committedGeneration == snapshot.generation) {
            if (pendingConfirmation != null) launchConfirmation()
            else scope.launch {
                if (!withContext(Dispatchers.IO) { installer.repeatConfirmation(snapshot.generation) }) failCommitted(snapshot.generation)
            }
            return
        }
        when (snapshot.phase) {
            "Ready" -> continueInstall(snapshot)
            "Available", "Error" -> {
                verifying = null; staging = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val core = updater.await()
                        if (snapshot.phase == "Error") core.retry(snapshot.generation, context.filesDir.usableSpace.toULong())
                        else core.download(snapshot.generation, context.filesDir.usableSpace.toULong())
                    }
                    nudge()
                }
            }
        }
    }
    fun later(onDone: () -> Unit) {
        if (installer.committedGeneration != null) return
        authorization = null
        scope.launch {
            work?.cancelAndJoin(); work = null
            withContext(Dispatchers.IO) {
                installer.abandonStaging()
                val core = updater.await(); core.cancel(); core.snooze()
            }
            nudge()
            onDone()
        }
    }
    fun openPermission() {
        if (!eligible()) return
        val snapshot = mutable.value ?: return
        authorization = snapshot.generation
        permissionReturn = true
        permissionNeeded = false
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            else Intent(Settings.ACTION_SECURITY_SETTINGS)
        runCatching { activity.get()?.startActivity(intent) }.onFailure { permissionReturn = false; permissionNeeded = true }
    }
    private fun continueInstall(snapshot: UpdateSnapshot) {
        if (!mayContinueInstall(work?.isActive == true, mutable.value?.generation, snapshot.generation)) return
        if (!installer.allowed()) { permissionNeeded = true; return }
        work = scope.launch(Dispatchers.IO) {
            if (mutable.value?.generation == snapshot.generation) updater.await().prepareInstall(snapshot.generation)
            nudge()
        }
    }
    fun cancel() {
        authorization = null
        permissionNeeded = false
        scope.launch {
            work?.cancelAndJoin()
            work = null
            if (installer.committedGeneration == null) {
                withContext(Dispatchers.IO) { installer.abandonStaging(); updater.await().cancel() }
                verifying = null; staging = null
            }
            nudge()
        }
    }
    fun playbackChanged(active: Boolean) {
        playback = active
        playbackStarting = false
        if (active) { autoCheck?.cancel(); cancel() }
        else if (resumed) browsingReady()
        nudge()
    }
    // docs/26 §1: Play is never dropped, and never waits for blocking verification (a stale result is ignored by generation).
    fun launchPlayback(launch: () -> Unit) {
        if (unavailable) { launch(); return }
        authorization = null
        playbackStarting = true
        autoCheck?.cancel()
        work?.cancel()
        scope.launch {
            try {
                val committed = installer.committedGeneration
                if (playbackGate(committed != null, resumed) == PlaybackGate.AbandonCommittedThenLaunch && committed != null) {
                    if (withContext(Dispatchers.IO) { installer.abandonCommitted() }) {
                        pendingConfirmation = null; staging = null
                        withContext(Dispatchers.IO) { updater.await().installerResult(committed, "canceled") }
                    }
                }
                if (installer.committedGeneration == null) withContext(Dispatchers.IO) { installer.abandonStaging(); updater.await().cancel() }
            } catch (_: Exception) { markUnavailable() }
            launch()
            playbackStarting = false
            nudge()
        }
    }
    private suspend fun failCommitted(generation: ULong) {
        withContext(Dispatchers.IO) {
            runCatching { installer.clear() }
            updater.await().installerResult(generation, "failed")
        }
        authorization = null; pendingConfirmation = null; staging = null
        nudge()
    }
    private fun stage(snapshot: UpdateSnapshot, core: AppUpdater) {
        if (!eligible() || authorization != snapshot.generation) { cancel(); return }
        work = scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    // docs/26 §4: verify again immediately before copying into the installer session.
                    verifyArchive(context, File(snapshot.apkPath), snapshot.profilePath?.let(::File))
                    installer.stage(snapshot)
                }
                ensureActive()
                if (!eligible() || authorization != snapshot.generation) { cancel(); return@launch }
                withContext(Dispatchers.IO) { core.installerResult(snapshot.generation, "committed") }
                ensureActive()
                if (!eligible()) { cancel(); return@launch }
                installer.commit(session, snapshot.generation)
            } catch (e: Exception) {
                // A copy interrupted by cancellation surfaces as an IO error; it is still a cancel.
                val canceled = e is CancellationException || !isActive
                authorization = null
                withContext(NonCancellable + Dispatchers.IO) {
                    installer.abandonStaging()
                    if (canceled) { if (installer.committedGeneration == null) core.installerResult(snapshot.generation, "canceled") }
                    else core.installerResult(snapshot.generation, "failed")
                }
                if (e is CancellationException) throw e
            }
        }
    }
    private fun launchConfirmation() {
        if (!eligible()) return
        val intent = pendingConfirmation ?: return
        pendingConfirmation = null
        authorization = null
        runCatching { activity.get()?.startActivity(intent) }.onFailure { pendingConfirmation = intent }
    }
    suspend fun installResult(intent: Intent) {
        try { applyInstallResult(intent) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { markUnavailable() }
    }
    private suspend fun applyInstallResult(intent: Intent) {
        val generation = installer.committedGeneration ?: return
        if (!installer.matches(intent)) return
        val core = updater.await()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) failCommitted(generation) else {
                    pendingConfirmation = confirmation
                    launchConfirmation()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installer.clear(); authorization = null
                withContext(Dispatchers.IO) { core.installerResult(generation, "success") }
            }
            else -> {
                installer.clear(); authorization = null; pendingConfirmation = null; staging = null
                val outcome = if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 1) == PackageInstaller.STATUS_FAILURE_ABORTED) "canceled" else "failed"
                withContext(Dispatchers.IO) { core.installerResult(generation, outcome) }
            }
        }
        nudge()
    }
    @Suppress("DEPRECATION")
    private fun confirmationIntent(result: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    private suspend fun reconcile(core: AppUpdater) = withContext(Dispatchers.IO) {
        val generation = installer.committedGeneration
        val installed = installedFacts(context)
        when (recoverSession(generation != null, installed.versionCode, installer.targetCode.toULong(), installer.sessionExists())) {
            SessionRecovery.Installed -> { core.installerResult(requireNotNull(generation), "success"); installer.clear() }
            SessionRecovery.Committed -> core.installerResult(requireNotNull(generation), "committed")
            SessionRecovery.Discard -> {
                if (generation != null) core.installerResult(generation, "canceled")
                installer.abandonStaging(); installer.clear()
            }
        }
        installer.removeOrphans()
    }
}
