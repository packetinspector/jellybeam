package tv.jellybeam.ui.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPlacementTest {

    @Test
    fun `false return remains a failed placement`() {
        assertFalse(tryRequestFocus { false })
    }

    @Test
    fun `true return is a successful placement`() {
        assertTrue(tryRequestFocus { true })
    }

    @Test
    fun `unattached requester exception remains retryable`() {
        assertFalse(tryRequestFocus { throw IllegalStateException("not attached") })
    }
}
