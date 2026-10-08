package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.AccountIdentity

class AuthorizationRecoveryCoordinatorTest {

    private val rejected = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")

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

        coordinator.noteReauthorized()
        assertNull("re-authorizing clears what was pending", coordinator.requests.value)

        now += AuthorizationRecoveryCoordinator.QUIET_AFTER_REAUTHORIZATION_MS - 1
        coordinator.request(rejected)
        assertNull(coordinator.requests.value)

        now += 1
        coordinator.request(rejected)
        assertEquals(2L, coordinator.requests.value?.id)
    }
}
