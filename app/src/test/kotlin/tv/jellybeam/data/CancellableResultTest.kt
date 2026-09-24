package tv.jellybeam.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test
    fun `preserves successful values including null`() {
        assertEquals(7, runCatchingCancellable { 7 }.getOrThrow())
        val result = runCatchingCancellable { null }
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `captures ordinary exceptions without changing their identity`() {
        val failure = IllegalStateException("synthetic failure")
        assertSame(failure, runCatchingCancellable { throw failure }.exceptionOrNull())
    }

    @Test
    fun `cancellation escapes unchanged`() {
        val cancelled = CancellationException("synthetic cancellation")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runCatchingCancellable { throw cancelled }
        })
    }

    @Test
    fun `fatal errors are not converted into recoverable failures`() {
        val fatal = AssertionError("synthetic fatal error")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            runCatchingCancellable { throw fatal }
        })
    }
}
