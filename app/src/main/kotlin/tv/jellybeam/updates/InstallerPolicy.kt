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
