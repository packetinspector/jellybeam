package tv.jellybeam.diag

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * docs/21 §4: the TV-local HTTP server for one report screen visit -- a hand-parsed GET-only
 * server for exactly three routes, alive only while the report screen is open.
 */
class ReportServer(
    private val snapshot: ReportSnapshot,
    private val token: String = randomToken(),
    private val ttlMs: Long = 600_000L,
    private val port: Int = 8765,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readTimeoutMs: Int = SOCKET_TIMEOUT_MS,
) {
    interface Listener {
        fun onPageServed()
        fun onLogServed()
    }

    data class Info(val port: Int, val url: String?)

    private val createdAt = clock()

    /** docs/21 §4 connection budget: 2 handler threads plus an 8-deep queue -- a connection
     * beyond that is rejected and closed immediately rather than queued forever.
     */
    private val connectionExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(MAX_QUEUED_CONNECTIONS),
        RejectedExecutionHandler { runnable, _ -> (runnable as? ConnectionTask)?.socket?.closeQuietly() },
    )

    @Volatile
    private var closed = false
    private var serverSocket: ServerSocket? = null

    /** docs/21 §4: every socket [acceptLoop] has handed to the executor, running or still
     * queued, so [close] can revoke a connection accepted just before it ran.
     */
    private val acceptedSockets = Collections.synchronizedSet(HashSet<Socket>())

    private inner class ConnectionTask(val socket: Socket) : Runnable {
        override fun run() = handleConnection(socket)
    }

    /** Binds [port]..[port]+4 (or just `0` when [port] is 0), starts the accept thread, and
     * returns the bound port plus the report URL ([Info.url] is null when [LanAddress.find]
     * finds no LAN address -- the server still runs).
     */
    fun start(): Info {
        val socket = bindSocket()
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "diag-report-accept").apply {
            isDaemon = true
            start()
        }
        val boundPort = socket.localPort
        val url = LanAddress.find()?.let { "http://$it:$boundPort/r/$token" }
        return Info(port = boundPort, url = url)
    }

    /** Idempotent: closes the listening socket, drops every queued connection, and closes every
     * socket [acceptLoop] already handed off -- so one accepted just before [close] runs never
     * gets a response (docs/21 §4).
     */
    fun close() {
        if (closed) return
        closed = true
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Already closed or never bound.
        }
        connectionExecutor.shutdownNow().forEach { (it as? ConnectionTask)?.socket?.closeQuietly() }
        synchronized(acceptedSockets) { acceptedSockets.forEach { it.closeQuietly() } }
    }

    private fun bindSocket(): ServerSocket {
        var lastError: IOException? = null
        val candidatePorts = if (port == 0) listOf(0) else (port..port + 4)
        for (p in candidatePorts) {
            try {
                return ServerSocket(p, BACKLOG, InetAddress.getByName("0.0.0.0"))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("unable to bind report server")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            acceptedSockets.add(client)
            connectionExecutor.execute(ConnectionTask(client))
        }
    }

    /** docs/21 §4 bounds: a request line or header over its byte cap, more than [MAX_HEADERS]
     * header lines, or [MAX_CONNECTION_MS] elapsing all close the socket without a response --
     * only a well-formed request within budget reaches [respond].
     */
    private fun handleConnection(client: Socket) {
        try {
            client.use { socket ->
                try {
                    socket.soTimeout = readTimeoutMs
                    val input = socket.getInputStream()
                    val deadlineAtMs = clock() + MAX_CONNECTION_MS
                    val requestLine = readBoundedLine(input, MAX_REQUEST_LINE, deadlineAtMs) ?: return
                    val request = parseRequestLine(requestLine) ?: return
                    var headerCount = 0
                    var headerBytes = 0
                    while (true) {
                        val header = readBoundedLine(input, MAX_HEADER_BYTES, deadlineAtMs) ?: return
                        if (header.isEmpty()) break
                        headerCount++
                        headerBytes += header.length
                        if (headerCount > MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) return
                    }
                    // docs/21 §4: close() may have run while this request was being read.
                    if (closed) return
                    respond(socket.getOutputStream(), request.method, request.path)
                } catch (_: IOException) {
                    // Client disconnected or timed out; nothing more to send.
                }
            }
        } finally {
            acceptedSockets.remove(client)
        }
    }

    /** Reads one CRLF- or LF-terminated line up to [maxBytes] bytes, checking [deadlineAtMs]
     * between reads -- null covers EOF, an oversized line and an expired deadline alike, since
     * all three mean "close without a response" (docs/21 §4).
     */
    private fun readBoundedLine(input: InputStream, maxBytes: Int, deadlineAtMs: Long): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            if (clock() >= deadlineAtMs) return null
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            buffer.write(b)
            if (buffer.size() > maxBytes) return null
        }
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Already closed or never connected.
        }
    }

    private data class ParsedRequest(val method: String, val path: String)

    private fun parseRequestLine(line: String): ParsedRequest? {
        val parts = line.trim().split(" ")
        if (parts.size < 2) return null
        return ParsedRequest(parts[0], parts[1].substringBefore('?'))
    }

    private enum class Route { PAGE, LOG, JSON }

    private fun matchRoute(path: String): Route? = when (path) {
        "/r/$token" -> Route.PAGE
        "/r/$token/jellybeam-log.txt" -> Route.LOG
        "/r/$token/report.json" -> Route.JSON
        else -> null
    }

    private fun respond(out: OutputStream, method: String, path: String) {
        if (method != "GET") {
            writeResponse(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val route = matchRoute(path)
        if (route == null) {
            writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (clock() - createdAt > ttlMs) {
            writeResponse(out, 410, "Gone", "text/plain; charset=utf-8", "report expired".toByteArray(Charsets.UTF_8))
            return
        }
        when (route) {
            Route.PAGE -> serveHtml(out)
            Route.LOG -> serveLog(out)
            Route.JSON -> serveJson(out)
        }
    }

    private fun serveHtml(out: OutputStream) {
        listener.onPageServed()
        writeResponse(out, 200, "OK", "text/html; charset=utf-8", renderPage().toByteArray(Charsets.UTF_8))
    }

    private fun serveLog(out: OutputStream) {
        listener.onLogServed()
        writeResponse(
            out,
            200,
            "OK",
            "text/plain; charset=utf-8",
            snapshot.logText.toByteArray(Charsets.UTF_8),
            mapOf("Content-Disposition" to "attachment; filename=\"jellybeam-log.txt\""),
        )
    }

    private fun serveJson(out: OutputStream) {
        writeResponse(out, 200, "OK", "application/json", snapshot.reportJson.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** docs/21 §5: inline CSS, no external resources, no script beyond the native `<details>`. */
    private fun renderPage(): String {
        val rows = snapshot.summary.joinToString("\n") { (k, v) ->
            "<tr><td>${escapeHtml(k)}</td><td>${escapeHtml(v)}</td></tr>"
        }
        val logLineCount = snapshot.logText.split("\n").size
        val logKb = (snapshot.logText.toByteArray(Charsets.UTF_8).size + 1023) / 1024
        val issueUrl = escapeHtml(IssueUrl.build(snapshot))
        val logHref = "/r/$token/jellybeam-log.txt"
        return """
            |<!doctype html>
            |<html>
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>Jellybeam TV diagnostic report</title>
            |<style>body{font-family:sans-serif;margin:16px}table{border-collapse:collapse}td{padding:2px 8px;vertical-align:top}pre{white-space:pre-wrap;word-break:break-word}</style>
            |</head>
            |<body>
            |<h2>Jellybeam TV diagnostic report</h2>
            |<p>This is exactly what will be shared. Nothing else leaves the TV. Names, titles, addresses and sign-in details are never recorded. The bug report you file will be public.</p>
            |<table>
            |$rows
            |</table>
            |<details><summary>Full log ($logLineCount lines)</summary><pre>${escapeHtml(snapshot.logText)}</pre></details>
            |<p><a href="$logHref" download="jellybeam-log.txt">1. Download log ($logKb KB)</a></p>
            |<p><a href="$issueUrl" target="_blank">2. Open bug report on GitHub</a></p>
            |<footer>This page stops working after ten minutes or when the TV leaves the report screen.</footer>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    companion object {
        private const val BACKLOG = 8
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val MAX_REQUEST_LINE = 2048
        private const val MAX_HEADERS = 32
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_CONNECTION_MS = 10_000L
        private const val MAX_QUEUED_CONNECTIONS = 8
        private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

        private fun randomToken(): String {
            val random = SecureRandom()
            return buildString { repeat(8) { append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)]) } }
        }
    }
}
