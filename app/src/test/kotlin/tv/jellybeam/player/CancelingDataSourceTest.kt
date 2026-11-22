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
import tv.jellybeam.player.ass.uninitialized
import org.junit.Assert.assertArrayEquals
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

/** [DataSource] fake serving [bytes] from the opened position, at most [chunk] per read, counting reads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ByteSource(private val bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : DataSource {
    var reads = 0
        private set
    var lastLength = 0
        private set
    private var pos = 0

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun open(dataSpec: DataSpec): Long {
        pos = dataSpec.position.toInt()
        return (bytes.size - pos).toLong()
    }
    override fun getUri(): Uri? = null
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads++
        lastLength = length
        if (pos >= bytes.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, chunk, bytes.size - pos)
        System.arraycopy(bytes, pos, buffer, offset, n)
        pos += n
        return n
    }
    override fun close() {}
}

/** android.jar stubs build no Uri, so a bare DataSpec with only [position] set, as [OutOfMediaGateTest] does. */
private fun spec(position: Long): DataSpec = uninitialized(DataSpec::class.java).also {
    DataSpec::class.java.getDeclaredField("position").apply { isAccessible = true }.setLong(it, position)
}

/** Reads [source] to end of input [size] bytes at a time. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun drain(source: DataSource, size: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(size)
    while (true) {
        val n = source.read(buf, 0, size)
        if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
        out.write(buf, 0, n)
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

    @Test
    fun `field-sized reads come from one upstream read per buffer, byte for byte`() {
        val bytes = ByteArray(40_000) { (it * 31).toByte() }
        val upstream = ByteSource(bytes)
        val source = CancelingDataSource(upstream, ThreadLocal())
        source.open(spec(0))

        val read = drain(source, 3)

        assertArrayEquals(bytes, read)
        // 40 000 bytes / 16 KiB buffers = 3 fills, plus the end-of-input read.
        assertEquals(4, upstream.reads)
    }

    @Test
    fun `a read as large as the buffer goes straight upstream`() {
        val upstream = ByteSource(ByteArray(100_000))
        val source = CancelingDataSource(upstream, ThreadLocal())
        source.open(spec(0))

        source.read(ByteArray(64 * 1024), 0, 64 * 1024)

        assertEquals(64 * 1024, upstream.lastLength)
    }

    @Test
    fun `a reopen drops bytes buffered for the old position`() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val upstream = ByteSource(bytes)
        val source = CancelingDataSource(upstream, ThreadLocal())
        source.open(spec(0))
        source.read(ByteArray(1), 0, 1)
        source.close()

        source.open(spec(500))
        val one = ByteArray(1)
        source.read(one, 0, 1)

        assertEquals(bytes[500], one[0])
    }

    @Test
    fun `end of input comes only after every buffered byte, and a clean end never cancels`() {
        val call = FakeCall()
        val threadLocal = ThreadLocal<Call?>().apply { set(call) }
        val upstream = ByteSource(ByteArray(10) { it.toByte() }, chunk = 4)
        val source = CancelingDataSource(upstream, threadLocal)
        source.open(spec(0))

        assertEquals(10, drain(source, 1).size)
        source.close()

        assertEquals(0, call.cancelCallCount)
    }
}
