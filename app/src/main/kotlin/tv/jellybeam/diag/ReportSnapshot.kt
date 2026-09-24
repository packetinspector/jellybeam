package tv.jellybeam.diag

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Everything [ReportSnapshot]'s summary rows are built from (docs/21 §3.1); Rust-owned values
 * (server version/count, playback mode) are read once by the caller before construction.
 */
data class SummaryInputs(
    val appVersion: String,
    val buildNumber: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val serverVersion: String?,
    val serverCount: Int,
    val playbackMode: String,
)

/**
 * docs/21 §3: a frozen snapshot of the summary, the ring and any pending crash, taken once at
 * construction so a second scan or download of the same report screen gets identical bytes.
 */
class ReportSnapshot(inputs: SummaryInputs, diag: DiagLog, crash: CrashCapture?) {

    val summary: List<Pair<String, String>>
    val logText: String
    val reportJson: String
    val category: String

    init {
        val lines = diag.linesSnapshot()
        val status = diag.status.value
        val crashInfo = crash?.pending()

        val lastPlan = findLastEvent(lines, PLAYBACK_PLAN_EVENTS)?.body ?: "none this session"
        val lastReauth = findLastEvent(lines, AUTH_REAUTH_EVENTS)?.body ?: "none this session"
        val lastErrorEvent = findLastEvent(lines, ERROR_EVENTS)
        val lastError = lastErrorEvent?.body ?: "none"
        val processStarts = lines.mapNotNull { line ->
            val parts = line.split(" ", limit = 3)
            if (parts.size >= 2 && parts[1] == "app.start") parts[0] else null
        }

        summary = listOf(
            "App version" to "${inputs.appVersion} (build ${inputs.buildNumber})",
            "Android" to "${inputs.androidRelease} (API ${inputs.sdkInt})",
            "Hardware" to "${inputs.manufacturer} ${inputs.model}",
            "Server version" to (inputs.serverVersion ?: "unknown"),
            "Servers signed in" to inputs.serverCount.toString(),
            "Playback mode" to inputs.playbackMode,
            "Last playback plan" to lastPlan,
            "Last reauth" to lastReauth,
            "Last error" to lastError,
            "Crash" to (crashInfo?.let { "${it.exceptionClass} at ${it.firstAppFrame}" } ?: "none"),
            "Logging" to loggingRow(status),
            "Process starts covered" to (if (processStarts.isEmpty()) "none" else processStarts.joinToString(", ")),
        )

        logText = buildLogText(summary, lines, crashInfo)
        reportJson = buildReportJson(summary, lines, crashInfo)
        category = when {
            crashInfo != null -> "Crash"
            lastErrorEvent?.eventName == "player.error" -> "Playback"
            lastErrorEvent?.eventName == "error" && hasWhereAuth(lastErrorEvent.body) -> "Sign-in"
            else -> "Other"
        }
    }

    private fun loggingRow(status: DiagStatus): String =
        if (status.enabled) {
            val since = status.onSinceMs?.let { ISO_FORMATTER.format(Instant.ofEpochMilli(it)) } ?: "unknown"
            "on since $since, ${status.lines} lines"
        } else {
            "off"
        }

    private data class LastEvent(val eventName: String, val body: String)

    private fun findLastEvent(lines: List<String>, names: Set<String>): LastEvent? {
        for (line in lines.asReversed()) {
            val parts = line.split(" ", limit = 3)
            if (parts.size >= 2 && parts[1] in names) {
                return LastEvent(parts[1], if (parts.size == 3) parts[2] else "")
            }
        }
        return null
    }

    private fun hasWhereAuth(body: String): Boolean = body.split(" ").any { it == "where=auth" }

    private fun buildLogText(summary: List<Pair<String, String>>, lines: List<String>, crashInfo: CrashInfo?): String {
        val sb = StringBuilder()
        summary.forEach { (k, v) -> sb.append("# ").append(k).append(": ").append(v).append('\n') }
        sb.append('\n')
        lines.forEach { sb.append(it).append('\n') }
        if (crashInfo != null) {
            sb.append('\n').append("--- crash ---").append('\n')
            sb.append(stackPortion(crashInfo.text))
        }
        return sb.toString().trimEnd('\n')
    }

    private fun stackPortion(crashText: String): String {
        val crashLines = crashText.split("\n")
        val startIdx = crashLines.indexOf("--- crash ---")
        if (startIdx == -1) return crashText
        val afterStart = crashLines.subList(startIdx + 1, crashLines.size)
        val endIdx = afterStart.indexOf("--- log ---")
        val stackLines = if (endIdx == -1) afterStart else afterStart.subList(0, endIdx)
        return stackLines.joinToString("\n").trim('\n')
    }

    private fun buildReportJson(summary: List<Pair<String, String>>, lines: List<String>, crashInfo: CrashInfo?): String {
        val sb = StringBuilder()
        sb.append("{\"summary\":{")
        sb.append(summary.joinToString(",") { (k, v) -> "${jsonString(k)}:${jsonString(v)}" })
        sb.append("},\"lines\":[")
        sb.append(lines.joinToString(",") { jsonString(it) })
        sb.append("],\"crash\":")
        sb.append(if (crashInfo != null) jsonString(crashInfo.text) else "null")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private val PLAYBACK_PLAN_EVENTS = setOf("playback.plan")
        private val AUTH_REAUTH_EVENTS = setOf("auth.reauth")
        private val ERROR_EVENTS = setOf("error", "player.error")
        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
