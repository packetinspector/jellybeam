package tv.jellybeam.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** [Call] fake: only [cancel]/[isCanceled] matter to [CancelingDataSource]; every other member is
 * never exercised by it. */
private class FakeCall : Call {
    var cancelCallCount = 0
        private set
    private var canceled = false

    override fun cancel() {
        cancelCallCount++
        canceled = true
    }

    override fun isCanceled(): Boolean = canceled
    override fun request(): Request = throw UnsupportedOperationException()
    override fun execute(): Response = throw UnsupportedOperationException()
    override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
    override fun isExecuted(): Boolean = false
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = throw UnsupportedOperationException()
}

/** [DataSource] fake: [readResult] is the canned [read] return value; [close] only counts calls. */
// Same DataSource/DataSpec/TransferListener opt-in CancelingDataSource itself takes.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class FakeDataSource : DataSource {
    var closeCallCount = 0
        private set
    var readResult: Int = C.RESULT_END_OF_INPUT

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
    override fun getUri(): Uri? = null
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = readResult
    override fun close() {
        closeCallCount++
    }
}

/**
 * Verifies [CancelingDataSource]'s wiring: it cancels the [Call] it finds via its [ThreadLocal] on
 * an abandoned close, leaves a clean end-of-stream close alone, and never touches a thread with no
 * tracked call. The threshold matrix itself lives in [AbandonedReadTrackerTest] -- this covers only
 * the wrapper plumbing around it.
 */
// CancelingDataSource's constructor takes a DataSource -- same opt-in as FakeDataSource above.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CancelingDataSourceTest {

    @Test
    fun `close cancels the tracked call when nothing was ever read`() {
        val call = FakeCall()
        val threadLocal = ThreadLocal<Call?>().apply { set(call) }
        val delegate = FakeDataSource()
        val source = CancelingDataSource(delegate, threadLocal)

        source.close()

        assertEquals(1, call.cancelCallCount)
        assertEquals(1, delegate.closeCallCount)
    }

    @Test
    fun `close does not cancel once read reports end-of-stream`() {
        val call = FakeCall()
        val threadLocal = ThreadLocal<Call?>().apply { set(call) }
        val delegate = FakeDataSource().apply { readResult = C.RESULT_END_OF_INPUT }
        val source = CancelingDataSource(delegate, threadLocal)

        source.read(ByteArray(1), 0, 1)
        source.close()

        assertEquals(0, call.cancelCallCount)
        assertEquals(1, delegate.closeCallCount)
    }

    @Test
    fun `close still delegates and never throws when no call was tracked on this thread`() {
        val threadLocal = ThreadLocal<Call?>()
        val delegate = FakeDataSource()
        val source = CancelingDataSource(delegate, threadLocal)

        source.close()

        assertEquals(1, delegate.closeCallCount)
    }
}
