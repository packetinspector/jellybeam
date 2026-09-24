package tv.jellybeam.diag

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/21 §4: request parsing, the three routes, token/method/ttl handling, and lifecycle. */
class ReportServerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeListener : ReportServer.Listener {
        val pageCount = AtomicInteger(0)
        val logCount = AtomicInteger(0)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun onPageServed() {
            pageCount.incrementAndGet()
            order += "page"
        }

        override fun onLogServed() {
            logCount.incrementAndGet()
            order += "log"
        }
    }

    private fun newSnapshot(): ReportSnapshot {
        val dir = tempFolder.newFolder()
        val diag = DiagLog(dir = dir, clock = { 0L }, executor = Executor { it.run() }, mirror = {})
        diag.setEnabled(true)
        diag.event("app.start") { tag("version", "0.1.0") }
        val inputs = SummaryInputs(
            appVersion = "0.1.0",
            buildNumber = 1,
            androidRelease = "14",
            sdkInt = 34,
            manufacturer = "Acme",
            model = "Box",
            serverVersion = "10.9.0",
            serverCount = 1,
            playbackMode = "DirectPlay",
        )
        return ReportSnapshot(inputs, diag, null)
    }

    private fun request(url: String, method: String = "GET"): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.instanceFollowRedirects = false
        return connection
    }

    /** A server-side close with unread bytes still buffered can arrive as a reset (IOException)
     * instead of a clean EOF -- either one means "no response was sent".
     */
    private fun assertClosedWithoutResponse(socket: Socket) {
        val firstByte = try {
            socket.getInputStream().read()
        } catch (_: IOException) {
            -1
        }
        assertEquals(-1, firstByte)
    }

    @Test
    fun pageServesSummaryAndFullLog() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345")
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.contains("Jellybeam TV diagnostic report"))
            assertTrue(body.contains("App version"))
            assertTrue(body.contains("Full log"))
            assertEquals(1, listener.pageCount.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun logRouteIsAttachmentWithHeaderLines() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345/jellybeam-log.txt")
            assertEquals(200, connection.responseCode)
            assertEquals(
                "attachment; filename=\"jellybeam-log.txt\"",
                connection.getHeaderField("Content-Disposition"),
            )
            assertTrue(connection.contentType.startsWith("text/plain"))
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.contains("# App version: 0.1.0 (build 1)"))
            assertEquals(1, listener.logCount.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun jsonRouteServesReportJson() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345/report.json")
            assertEquals(200, connection.responseCode)
            assertEquals("application/json", connection.contentType)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.startsWith("{\"summary\":{"))
        } finally {
            server.close()
        }
    }

    @Test
    fun wrongTokenIs404Empty() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/wrongtoken")
            assertEquals(404, connection.responseCode)
            val body = connection.errorStream?.bufferedReader()?.readText() ?: ""
            assertTrue(body.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun postIs405() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345", method = "POST")
            assertEquals(405, connection.responseCode)
        } finally {
            server.close()
        }
    }

    @Test
    fun expiredIs410() {
        val listener = FakeListener()
        var now = 0L
        val server = ReportServer(
            snapshot = newSnapshot(),
            token = "tok12345",
            ttlMs = 1000L,
            port = 0,
            listener = listener,
            clock = { now },
        )
        val info = server.start()
        try {
            now = 5000L
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345")
            assertEquals(410, connection.responseCode)
            val body = connection.errorStream?.bufferedReader()?.readText() ?: ""
            assertEquals("report expired", body)
        } finally {
            server.close()
        }
    }

    @Test
    fun listenerFiresPageThenLog() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            request("http://127.0.0.1:${info.port}/r/tok12345").responseCode
            request("http://127.0.0.1:${info.port}/r/tok12345/jellybeam-log.txt").responseCode
            assertEquals(listOf("page", "log"), listener.order)
        } finally {
            server.close()
        }
    }

    @Test
    fun closeStopsAccepting() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        server.close()

        var refused = false
        try {
            val connection = request("http://127.0.0.1:${info.port}/r/tok12345")
            connection.connectTimeout = 500
            connection.responseCode
        } catch (_: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun oversizedRequestLineClosesWithoutResponse() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 2000
                val oversizedPath = "a".repeat(4096)
                socket.getOutputStream().write("GET /r/$oversizedPath HTTP/1.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                assertClosedWithoutResponse(socket)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun tooManyHeadersClosesWithoutResponse() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener)
        val info = server.start()
        try {
            Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 2000
                val rawRequest = buildString {
                    append("GET /r/tok12345 HTTP/1.1\r\n")
                    repeat(40) { i -> append("X-Header-$i: value\r\n") }
                    append("\r\n")
                }
                socket.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                assertClosedWithoutResponse(socket)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun slowClientIsClosedAtReadTimeout() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener, readTimeoutMs = 200)
        val info = server.start()
        try {
            Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 3000
                assertClosedWithoutResponse(socket)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun closeRevokesConnectionsAcceptedBeforeIt() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener, readTimeoutMs = 3000)
        val info = server.start()
        try {
            Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 2000
                // Lets the accept loop hand this connection to the executor before close() runs.
                Thread.sleep(200)
                server.close()

                runCatching {
                    socket.getOutputStream().write("GET /r/tok12345 HTTP/1.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
                assertClosedWithoutResponse(socket)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun closeDropsQueuedConnections() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener, readTimeoutMs = 5000)
        val info = server.start()
        val sockets = (1..5).map { Socket("127.0.0.1", info.port).apply { soTimeout = 1000 } }
        try {
            // 2 running (executor pool size) plus 3 queued, all sending nothing.
            Thread.sleep(300)
            server.close()
            sockets.forEach { assertClosedWithoutResponse(it) }
        } finally {
            sockets.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun excessConnectionsAreRejectedNotQueuedForever() {
        val listener = FakeListener()
        val server = ReportServer(newSnapshot(), token = "tok12345", port = 0, listener = listener, readTimeoutMs = 3000)
        val info = server.start()
        val holders = mutableListOf<Socket>()
        try {
            repeat(10) {
                val socket = Socket("127.0.0.1", info.port)
                socket.soTimeout = 3000
                holders += socket
            }
            // Lets the accept loop hand all ten to the executor (2 running + 8 queued) before
            // the eleventh is opened.
            Thread.sleep(300)

            Socket("127.0.0.1", info.port).use { eleventh ->
                eleventh.soTimeout = 2000
                assertClosedWithoutResponse(eleventh)
            }

            holders.forEach { it.close() }

            val connection = request("http://127.0.0.1:${info.port}/r/tok12345")
            assertEquals(200, connection.responseCode)
        } finally {
            holders.forEach { runCatching { it.close() } }
            server.close()
        }
    }
}
