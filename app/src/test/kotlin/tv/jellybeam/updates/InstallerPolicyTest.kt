package tv.jellybeam.updates

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class InstallerPolicyTest {
    @Test fun `only resumed idle update page may open installer`() {
        assertTrue(updateForeground(true, true, false, false))
        assertFalse(updateForeground(false, true, false, false))
        assertFalse(updateForeground(true, false, false, false))
        assertFalse(updateForeground(true, true, true, false))
        assertFalse(updateForeground(true, true, false, true))
    }
    @Test fun `stale or forged session results cannot finish another operation`() {
        assertTrue(matchesSession(5, 4u, 5, 4))
        assertFalse(matchesSession(5, 4u, 6, 4))
        assertFalse(matchesSession(5, 4u, 5, 3))
        assertFalse(matchesSession(5, null, 5, 4))
        assertFalse(matchesSession(-1, 4u, -1, 4))
        assertFalse(matchesSession(5, ULong.MAX_VALUE, 5, -1))
    }
    @Test fun `installed target is recognized without old process callback`() {
        assertEquals(SessionRecovery.Installed, recoverSession(true, 7u, 7u, false))
        assertEquals(SessionRecovery.Installed, recoverSession(true, 8u, 7u, true))
        assertEquals(SessionRecovery.Committed, recoverSession(true, 6u, 7u, true))
        assertEquals(SessionRecovery.Discard, recoverSession(true, 6u, 7u, false))
        assertEquals(SessionRecovery.Discard, recoverSession(false, 6u, 7u, true))
    }
    @Test fun `turning checks off wins over a delayed startup read`() = runBlocking {
        var preference = AutomaticPreference()
        val started = CompletableDeferred<Unit>()
        val stored = CompletableDeferred<Boolean>()
        val load = launch {
            val revision = preference.revision
            started.complete(Unit)
            val loaded = stored.await()
            preference = preference.loaded(loaded, revision)
        }
        started.await()
        preference = preference.edit(false)
        stored.complete(true)
        load.join()
        assertFalse(preference.enabled)
    }
    @Test fun `unchanged preference loads stored off choice`() {
        val preference = AutomaticPreference()
        assertFalse(preference.loaded(false, preference.revision).enabled)
    }
    @Test fun `latest toggle wins over an older stored off choice`() {
        val preference = AutomaticPreference()
        val edited = preference.edit(false).edit(true)
        assertTrue(edited.loaded(false, preference.revision).enabled)
    }
    @Test fun `play while resumed on a committed session abandons it and still launches`() {
        assertEquals(PlaybackGate.AbandonCommittedThenLaunch, playbackGate(committed = true, resumed = true))
        assertEquals(PlaybackGate.Launch, playbackGate(committed = true, resumed = false))
        assertEquals(PlaybackGate.Launch, playbackGate(committed = false, resumed = true))
        assertEquals(PlaybackGate.Launch, playbackGate(committed = false, resumed = false))
    }
    @Test fun `play launches at once unless uncommitted staging needs abandoning afterwards`() {
        // (staging exists, core phase) -> plan; a download or verification is never Play's to cancel (docs/26 §1).
        val table = listOf(
            Triple(false, "Idle", PlayLaunchPlan.LaunchNow),
            Triple(false, "Current", PlayLaunchPlan.LaunchNow),
            Triple(false, "Available", PlayLaunchPlan.LaunchNow),
            Triple(false, "Downloading", PlayLaunchPlan.LaunchNow),
            Triple(false, "Verifying", PlayLaunchPlan.LaunchNow),
            Triple(false, "Ready", PlayLaunchPlan.LaunchNow),
            Triple(false, "AwaitingConfirmation", PlayLaunchPlan.LaunchNow),
            Triple(false, "", PlayLaunchPlan.LaunchNow),
            Triple(true, "Preparing", PlayLaunchPlan.LaunchThenAbandonStaging),
            Triple(true, "Staging", PlayLaunchPlan.LaunchThenAbandonStaging),
            Triple(true, "Ready", PlayLaunchPlan.LaunchThenAbandonStaging),
            Triple(false, "Preparing", PlayLaunchPlan.LaunchThenAbandonStaging),
            Triple(false, "Staging", PlayLaunchPlan.LaunchThenAbandonStaging),
        )
        val wrong = table.filter { (staging, phase, expected) -> playLaunchPlan(staging, phase) != expected }
        assertEquals(emptyList<Any>(), wrong)
    }
    @Test fun `play from either entry cancels only install preparation and never a download, verification or check`() {
        // (staging exists, core phase, running work) -> (abandon staging, cancel core, cancel work); launchPlayback and playbackChanged(true) apply this one plan.
        data class Row(val staging: Boolean, val phase: String, val work: WorkKind?, val abandon: Boolean, val core: Boolean, val cancelWork: Boolean)
        val phases = listOf("Idle", "Checking", "Current", "Available", "Downloading", "Verifying", "Ready", "Preparing", "Staging", "AwaitingConfirmation", "Error")
        val table = phases.flatMap { phase ->
            listOf(false, true).flatMap { staging ->
                listOf(null, WorkKind.Verify, WorkKind.Prepare, WorkKind.Stage).map { work ->
                    val preparing = phase == "Preparing" || phase == "Staging"
                    val preparationWork = work == WorkKind.Prepare || work == WorkKind.Stage
                    Row(staging, phase, work, abandon = staging || preparing || preparationWork, core = preparing || preparationWork, cancelWork = preparationWork)
                }
            }
        }
        val wrong = table.filter { row ->
            val plan = playPlan(row.staging, row.phase, row.work)
            (plan.launch == PlayLaunchPlan.LaunchThenAbandonStaging) != row.abandon ||
                plan.cancelCore != row.core || plan.cancelWork != row.cancelWork
        }
        assertEquals(emptyList<Any>(), wrong.map { "${it.staging}/${it.phase}/${it.work}" })
        for (phase in listOf("Downloading", "Verifying")) for (staging in listOf(false, true)) for (work in listOf(null, WorkKind.Verify)) {
            val plan = playPlan(staging, phase, work)
            assertFalse("$phase/$staging/$work: Play never cancels the core", plan.cancelCore)
            assertFalse("$phase/$staging/$work: Play never cancels the work", plan.cancelWork)
        }
    }
    @Test fun `an interrupted stage is reported canceled and never failed, and a committed session keeps its own report`() {
        for (cancellation in listOf(true, false)) for (jobActive in listOf(true, false)) for (committed in listOf(true, false)) {
            val canceled = cancellation || !jobActive
            val expected = if (canceled) (if (committed) null else "canceled") else "failed"
            assertEquals("cancellation=$cancellation jobActive=$jobActive committed=$committed", expected, stageOutcome(cancellation, jobActive, committed))
        }
    }
    @Test fun `an abandoned staging session is no longer staging and a committed one never is`() {
        // (staging id held in memory, session key in the journal, committed flag) -> the uncommitted session
        assertNull("fresh", uncommittedSessionId(null, -1, false))
        assertEquals("staged", 7, uncommittedSessionId(7, 7, false))
        assertEquals("staged, held only in the journal after a restart", 7, uncommittedSessionId(null, 7, false))
        assertNull("staged then abandoned", uncommittedSessionId(null, journalSessionAfterAbandon(7, 7), false))
        assertEquals("a different session's key is left alone", 9, journalSessionAfterAbandon(9, 7))
        assertNull("committed", uncommittedSessionId(7, 7, true))
        assertNull("committed then cleared", uncommittedSessionId(null, -1, false))
    }
    @Test fun `pause keeps transfers and verification and only stops pre-commit staging`() {
        assertEquals(PausePlan(true, false), pausePlan(false, false, "Downloading"))
        assertEquals(PausePlan(true, false), pausePlan(false, false, "Verifying"))
        assertEquals(PausePlan(true, false), pausePlan(false, false, "Ready"))
        assertEquals(PausePlan(true, true), pausePlan(false, false, "Preparing"))
        assertEquals(PausePlan(true, true), pausePlan(false, false, "Staging"))
        assertEquals(PausePlan(false, false), pausePlan(true, false, "Staging"))
        assertEquals(PausePlan(false, false), pausePlan(false, true, "AwaitingConfirmation"))
    }
    @Test fun `fast polling only while busy in the foreground`() {
        assertEquals(FAST_POLL_MS, pollIntervalMs("Downloading", resumed = true, playback = false))
        assertEquals(FAST_POLL_MS, pollIntervalMs("Checking", resumed = true, playback = false))
        assertEquals(SLOW_POLL_MS, pollIntervalMs("Downloading", resumed = false, playback = false))
        assertEquals(SLOW_POLL_MS, pollIntervalMs("Downloading", resumed = true, playback = true))
        assertEquals(SLOW_POLL_MS, pollIntervalMs("Idle", resumed = true, playback = false))
        assertEquals(SLOW_POLL_MS, pollIntervalMs("Current", resumed = true, playback = false))
    }
    @Test fun `snapshots publish only when generation or revision moved`() {
        assertTrue(shouldPublish(null, null, 1u, 1u))
        assertFalse(shouldPublish(1u, 7u, 1u, 7u))
        assertTrue(shouldPublish(1u, 7u, 1u, 8u))
        assertTrue(shouldPublish(1u, 7u, 2u, 7u))
    }
    @Test fun `second install press neither overlaps work nor targets a stale generation`() {
        assertTrue(mayContinueInstall(false, 3u, 3u))
        assertFalse(mayContinueInstall(true, 3u, 3u))
        assertFalse(mayContinueInstall(false, 4u, 3u))
        assertFalse(mayContinueInstall(false, null, 3u))
    }
}
