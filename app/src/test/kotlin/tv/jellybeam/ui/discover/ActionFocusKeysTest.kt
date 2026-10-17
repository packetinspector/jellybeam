package tv.jellybeam.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrRequestStatus

/** docs/15 §4: focus after a request or cancel always lands on a pill that is still there. */
class ActionFocusKeysTest {

    private val lib = "action:go-to-library"
    private val request = "action:request"
    private val request4k = "action:request-4k"
    private val cancel = "action:cancel-request"

    private fun check(invoker: String?, visible: List<String>, expected: List<String>) =
        assertEquals("invoker=$invoker visible=$visible", expected, actionFocusKeys(invoker, visible))

    @Test
    fun `the invoker wins while it is still visible`() {
        check(request, listOf(request), listOf(request))
        check(cancel, listOf(cancel), listOf(cancel))
        check(request4k, listOf(request, request4k), listOf(request4k, request))
    }

    @Test
    fun `a replaced request pill hands focus to its slot mate`() {
        check(request, listOf(cancel), listOf(cancel))
        check(request4k, listOf(cancel), listOf(cancel))
        check(cancel, listOf(request), listOf(request))
        check(cancel, listOf(request, request4k), listOf(request, request4k))
        check(request, listOf(request4k), listOf(request4k))
    }

    @Test
    fun `a slot mate outranks an earlier unrelated pill`() {
        check(request, listOf(lib, cancel), listOf(cancel, lib))
    }

    @Test
    fun `a gone invoker with no slot mate falls to the first pill`() {
        check(request, listOf(lib), listOf(lib))
        check(lib, listOf(cancel, request), listOf(cancel, request))
    }

    @Test
    fun `no invoker lands on the first pill`() {
        check(null, listOf(request), listOf(request))
        check(null, listOf(cancel), listOf(cancel))
        check(null, listOf(request, request4k), listOf(request, request4k))
    }

    @Test
    fun `a page that still holds focus is left alone`() {
        assertEquals(emptyList<String>(), actionFocusKeys(request, listOf(cancel), pageHasFocus = true))
        assertEquals(emptyList<String>(), actionFocusKeys(cancel, listOf(request, request4k), pageHasFocus = true))
    }

    private val active = SeerrActiveRequest(requestId = 7L, status = SeerrRequestStatus.PENDING, is4k = false, seasons = emptyList())

    @Test
    fun `every pill stays focusable while a mutation is in flight`() {
        val idle = DiscoverDetailUiState(isLoading = false, activeRequest = active, canRequest = true, canRequest4k = true)
        val held = idle.copy(busy = true)
        assertEquals(listOf(cancel, request, request4k), idle.actionKeys())
        assertTrue(idle.actionSlots().all { it.actionable })
        assertEquals(idle.actionKeys(), held.actionKeys())
        assertFalse(held.actionSlots().any { it.actionable })
    }

    @Test
    fun `no visible pill yields no target`() {
        check(request, emptyList(), emptyList())
        check(request4k, emptyList(), emptyList())
        check(cancel, emptyList(), emptyList())
        check(null, emptyList(), emptyList())
    }
}
