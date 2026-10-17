package tv.jellybeam.updates

internal fun updateForeground(resumed: Boolean, pageVisible: Boolean, playback: Boolean, starting: Boolean): Boolean =
    resumed && pageVisible && !playback && !starting

internal fun matchesSession(expectedId: Int, expectedGeneration: ULong?, id: Int, generation: Long): Boolean =
    expectedId >= 0 && expectedGeneration != null && generation >= 0 && id == expectedId && generation.toULong() == expectedGeneration

internal enum class SessionRecovery { Installed, Committed, Discard }
internal fun recoverSession(committed: Boolean, installed: ULong, target: ULong, exists: Boolean): SessionRecovery = when {
    committed && target > 0u && installed >= target -> SessionRecovery.Installed
    committed && exists -> SessionRecovery.Committed
    else -> SessionRecovery.Discard
}

internal data class AutomaticPreference(val enabled: Boolean = true, val revision: Long = 0) {
    fun edit(enabled: Boolean) = AutomaticPreference(enabled, revision + 1)
    fun loaded(enabled: Boolean, loadRevision: Long) = if (revision == loadRevision) copy(enabled = enabled) else this
}

internal enum class PlaybackGate { Launch, AbandonCommittedThenLaunch }
/** docs/26 §1: Play while resumed with a committed session means the viewer left Android's dialog; the request is never dropped. */
internal fun playbackGate(committed: Boolean, resumed: Boolean): PlaybackGate =
    if (committed && resumed) PlaybackGate.AbandonCommittedThenLaunch else PlaybackGate.Launch

/** docs/26 §1: what the coordinator's one in-flight work job is doing; Play may cancel only [Prepare] and [Stage]. */
internal enum class WorkKind { Verify, Prepare, Stage }

internal enum class PlayLaunchPlan { LaunchNow, LaunchThenAbandonStaging }
/** docs/26 §1: Play starts at once; only uncommitted staging (a session, or the core preparing one) is abandoned, after the launch, and a download or verification continues. */
internal fun playLaunchPlan(stagingExists: Boolean, phase: String, work: WorkKind? = null): PlayLaunchPlan =
    if (stagingExists || phase == "Preparing" || phase == "Staging" || work.isInstallPreparation()) PlayLaunchPlan.LaunchThenAbandonStaging else PlayLaunchPlan.LaunchNow

private fun WorkKind?.isInstallPreparation() = this == WorkKind.Prepare || this == WorkKind.Stage

internal data class PlayPlan(val launch: PlayLaunchPlan, val cancelCore: Boolean, val cancelWork: Boolean)
/** docs/26 §1: Play, whichever way it arrives, cancels install preparation only (the running job by its [work] kind, the core by its phase); a download, verification or check is never its to cancel. */
internal fun playPlan(stagingExists: Boolean, phase: String, work: WorkKind?): PlayPlan {
    val cancelWork = work.isInstallPreparation()
    val preparing = phase == "Preparing" || phase == "Staging"
    return PlayPlan(playLaunchPlan(stagingExists, phase, work), cancelCore = cancelWork || preparing, cancelWork = cancelWork)
}

/** docs/26 §4: what an interrupted Stage tells the core -- "canceled" for any cancellation, "failed" otherwise, nothing once a session is committed. */
internal fun stageOutcome(cancellation: Boolean, jobActive: Boolean, committed: Boolean): String? =
    if (cancellation || !jobActive) "canceled".takeUnless { committed } else "failed"

/** docs/26 §4: the installer session that is staged but not committed, from memory or else the journal. */
internal fun uncommittedSessionId(stagingId: Int?, journalSession: Int, committed: Boolean): Int? =
    if (committed) null else stagingId ?: journalSession.takeIf { it >= 0 }
/** docs/26 §4: an abandoned session leaves the journal, so a restart does not find staging that no longer exists. */
internal fun journalSessionAfterAbandon(journalSession: Int, abandoned: Int): Int = if (journalSession == abandoned) -1 else journalSession

internal data class PausePlan(val dropAuthorization: Boolean, val cancelInstallPreparation: Boolean)
/** docs/26 §1: leaving the page drops authorization and pre-commit staging only; transfers and verification continue. */
internal fun pausePlan(permissionReturn: Boolean, committed: Boolean, phase: String): PausePlan =
    if (permissionReturn || committed) PausePlan(false, false)
    else PausePlan(true, phase == "Preparing" || phase == "Staging")

private val busyPhases = setOf("Checking", "Downloading", "Verifying", "Preparing", "Staging")
internal const val FAST_POLL_MS = 250L
internal const val SLOW_POLL_MS = 5_000L
/** docs/26 §5: poll fast only while work is visible; otherwise wake on events or the slow tick. */
internal fun pollIntervalMs(phase: String, resumed: Boolean, playback: Boolean): Long =
    if (resumed && !playback && phase in busyPhases) FAST_POLL_MS else SLOW_POLL_MS

/** Snapshot publication is skipped when neither generation nor revision moved. */
internal fun shouldPublish(seenGeneration: ULong?, seenRevision: ULong?, generation: ULong, revision: ULong): Boolean =
    seenGeneration != generation || seenRevision != revision

/** docs/26 §4: a second press must not start a second preparation or act on a replaced generation. */
internal fun mayContinueInstall(workActive: Boolean, currentGeneration: ULong?, generation: ULong): Boolean =
    !workActive && currentGeneration == generation
