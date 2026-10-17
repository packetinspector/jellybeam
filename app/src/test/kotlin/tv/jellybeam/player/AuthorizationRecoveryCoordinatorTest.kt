package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.AccountIdentity

class AuthorizationRecoveryCoordinatorTest {

    private val rejected = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")
    private val other = AccountIdentity(serverUrl = "http://b.test", userId = "u-b")

    @Test
    fun `a request stays pending until consumed`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })

        coordinator.request(rejected)
        val request = coordinator.requests.value
        assertNotNull(request)
        assertEquals("the rejected account travels with it", rejected, request!!.account)
        assertEquals("a call's 401 is not a failed playback", false, request.failedPlayback)

        coordinator.consume(request.id)
        assertNull(coordinator.requests.value)
    }

    @Test
    fun `the player's own 401 says it came from a failed playback`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })

        coordinator.requestForFailedPlayback(rejected)

        assertEquals(AuthorizationRequest(1L, rejected, failedPlayback = true), coordinator.requests.value)
    }

    @Test
    fun `a straggling 401 right after re-authorization is dropped, a later one is not`() {
        var now = 1_000L
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { now })
        coordinator.request(rejected)

        coordinator.noteReauthorized(rejected)
        assertNull("re-authorizing clears what was pending", coordinator.requests.value)

        now += AuthorizationRecoveryCoordinator.QUIET_AFTER_REAUTHORIZATION_MS - 1
        coordinator.request(rejected)
        assertNull(coordinator.requests.value)

        now += 1
        coordinator.request(rejected)
        assertEquals(2L, coordinator.requests.value?.id)
    }

    /** docs/18 §2.1: one quiet window after any re-authorization, for every account. */
    @Test
    fun `any 401 inside the quiet window is dropped`() {
        var now = 1_000L
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { now })
        coordinator.noteReauthorized(rejected)
        now += 1_000L

        coordinator.request(other)
        assertNull(coordinator.requests.value)

        now += AuthorizationRecoveryCoordinator.QUIET_AFTER_REAUTHORIZATION_MS
        coordinator.request(other)
        assertEquals(other, coordinator.requests.value?.account)
    }

    /** docs/18 §2.1: one request at a time; the held one is never replaced by another account's. */
    @Test
    fun `another account's 401 while one is held is dropped, and re-authorizing it keeps the held one`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })
        coordinator.request(other)

        coordinator.request(rejected)
        assertEquals(other, coordinator.requests.value?.account)

        coordinator.noteReauthorized(rejected)
        assertEquals(other, coordinator.requests.value?.account)
    }

    @Test
    fun `a repeat request for the same account is one pending request`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })
        coordinator.request(rejected)
        coordinator.request(rejected)

        coordinator.consume(coordinator.requests.value!!.id)

        assertNull(coordinator.requests.value)
    }

    /** docs/18 §2.1: a failed playback's flag survives a later ordinary 401 for the same account. */
    @Test
    fun `the pending flag for an account is the or of every request posted for it`() {
        for (first in listOf(false, true)) {
            for (second in listOf(false, true)) {
                val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })
                fun post(failedPlayback: Boolean) =
                    if (failedPlayback) coordinator.requestForFailedPlayback(rejected) else coordinator.request(rejected)

                post(first)
                post(second)

                assertEquals("first=$first second=$second", first || second, coordinator.requests.value?.failedPlayback)
            }
        }
    }

    /** docs/18 §2.1: a request naming nobody prompts nobody, so it is never held. */
    @Test
    fun `a request naming no account is dropped`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })

        coordinator.request(null)
        assertNull(coordinator.requests.value)
        coordinator.requestForFailedPlayback(null)
        assertNull(coordinator.requests.value)

        coordinator.request(rejected)
        coordinator.request(null)
        assertEquals("a nameless request leaves a held one alone", rejected, coordinator.requests.value?.account)
    }
}
