package tv.jellybeam.ui.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo

/** The server manager's selection follows the account, never its list position. */
class SelectedAccountIndexTest {

    private val a = AccountInfo(serverUrl = "http://a.test", userId = "u-a", userName = "a")
    private val b = AccountInfo(serverUrl = "http://b.test", userId = "u-b", userName = "b")
    private val otherUserOnA = AccountInfo(serverUrl = "http://a.test", userId = "u-a2", userName = "a2")

    @Test
    fun `a removal ahead of the selection moves its index with it`() {
        assertEquals(1u, selectedAccountIndex(listOf(a, b), selected = b))
        assertEquals(0u, selectedAccountIndex(listOf(b), selected = b))
    }

    @Test
    fun `the removed account's selection clears instead of landing on the next one`() {
        assertNull(selectedAccountIndex(listOf(b), selected = a))
        assertNull("another user on the same server", selectedAccountIndex(listOf(a), selected = otherUserOnA))
        assertNull(selectedAccountIndex(listOf(a, b), selected = null))
    }
}
