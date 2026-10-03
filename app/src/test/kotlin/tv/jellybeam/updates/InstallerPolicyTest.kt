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
