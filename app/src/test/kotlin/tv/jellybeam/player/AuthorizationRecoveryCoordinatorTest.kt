package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationRecoveryCoordinatorTest {

    @Test
    fun `a request stays pending until consumed`() {
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { 0L })

        coordinator.request()
        val id = coordinator.requests.value
        assertNotNull(id)

        coordinator.consume(id!!)
        assertNull(coordinator.requests.value)
    }

    @Test
    fun `a straggling 401 right after re-authorization is dropped, a later one is not`() {
        var now = 1_000L
        val coordinator = AuthorizationRecoveryCoordinator(nowMs = { now })
        coordinator.request()

        coordinator.noteReauthorized()
        assertNull("re-authorizing clears what was pending", coordinator.requests.value)

        now += AuthorizationRecoveryCoordinator.QUIET_AFTER_REAUTHORIZATION_MS - 1
        coordinator.request()
        assertNull(coordinator.requests.value)

        now += 1
        coordinator.request()
        assertEquals(2L, coordinator.requests.value)
    }
}
