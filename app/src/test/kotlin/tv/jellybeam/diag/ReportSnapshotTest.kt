package tv.jellybeam.diag

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/21 §3: summary derivation, log/json layout, and the report category. */
class ReportSnapshotTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val directExecutor = Executor { it.run() }

    private fun newDiag() = DiagLog(dir = tempFolder.newFolder(), clock = { 0L }, executor = directExecutor, mirror = {})

    private fun minimalInputs(): SummaryInputs = SummaryInputs(
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

    @Test
    fun summaryOrderAndKeys() {
        val diag = newDiag()
        val snapshot = ReportSnapshot(minimalInputs().copy(buildNumber = 7, serverCount = 2), diag, null)

        val expectedKeys = listOf(
            "App version", "Android", "Hardware", "Server version", "Servers signed in",
            "Playback mode", "Last playback plan", "Last reauth", "Last error", "Crash",
            "Logging", "Process starts covered",
        )
        assertEquals(expectedKeys, snapshot.summary.map { it.first })
        val byKey = snapshot.summary.toMap()
        assertEquals("0.1.0 (build 7)", byKey["App version"])
        assertEquals("14 (API 34)", byKey["Android"])
        assertEquals("Acme Box", byKey["Hardware"])
        assertEquals("10.9.0", byKey["Server version"])
        assertEquals("2", byKey["Servers signed in"])
        assertEquals("DirectPlay", byKey["Playback mode"])
        assertEquals("none this session", byKey["Last playback plan"])
        assertEquals("none this session", byKey["Last reauth"])
        assertEquals("none", byKey["Last error"])
        assertEquals("none", byKey["Crash"])
        assertEquals("off", byKey["Logging"])
        assertEquals("none", byKey["Process starts covered"])

        val withoutServer = ReportSnapshot(minimalInputs().copy(serverVersion = null), diag, null)
        assertEquals("unknown", withoutServer.summary.toMap()["Server version"])
    }

    @Test
    fun lastPlanLastReauthLastErrorDerived() {
        val diag = newDiag()
        diag.setEnabled(true)
        diag.event("playback.plan") {
            item("item", "item-a")
            tag("mode", "DirectPlay")
        }
        diag.event("auth.reauth") {
            server("server", "srv-a")
            tag("result", "ok")
        }
        diag.event("error") {
            tag("where", "sync")
            tag("code", "500")
        }
        diag.event("playback.plan") {
            item("item", "item-b")
            tag("mode", "Transcode")
        }
        diag.event("player.error") {
            tag("code", "decoder")
            num("posMs", 1000L)
        }

        val byKey = ReportSnapshot(minimalInputs(), diag, null).summary.toMap()
        assertEquals("item=item#2 mode=Transcode", byKey["Last playback plan"])
        assertEquals("server=server#1 result=ok", byKey["Last reauth"])
        assertEquals("code=decoder posMs=1000", byKey["Last error"])
    }

    @Test
    fun categoryCrashThenPlaybackThenSignInThenOther() {
        val diagOther = newDiag()
        assertEquals("Other", ReportSnapshot(minimalInputs(), diagOther, null).category)

        val diagSignIn = newDiag()
        diagSignIn.setEnabled(true)
        diagSignIn.event("error") {
            tag("where", "auth")
            tag("code", "401")
        }
        assertEquals("Sign-in", ReportSnapshot(minimalInputs(), diagSignIn, null).category)

        val diagPlayback = newDiag()
        diagPlayback.setEnabled(true)
        diagPlayback.event("player.error") { tag("code", "decoder") }
        assertEquals("Playback", ReportSnapshot(minimalInputs(), diagPlayback, null).category)

        val crashDir = tempFolder.newFolder()
        val diagCrash = DiagLog(dir = crashDir, clock = { 0L }, executor = directExecutor, mirror = {})
        diagCrash.setEnabled(true)
        diagCrash.event("player.error") { tag("code", "decoder") }
        val crash = CrashCapture(crashDir, diagCrash, summary = { emptyList() })
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals("Crash", ReportSnapshot(minimalInputs(), diagCrash, crash).category)
    }

    @Test
    fun reportJsonIsWellFormedAndEscapes() {
        val diag = newDiag()
        val inputs = minimalInputs().copy(manufacturer = "Acme\"Co\\Ltd", model = "Box\n9000")
        val snapshot = ReportSnapshot(inputs, diag, null)

        assertTrue(snapshot.reportJson.startsWith("{\"summary\":{"))
        assertTrue(snapshot.reportJson.contains("\"lines\":["))
        assertTrue(snapshot.reportJson.contains("\"crash\":null"))
        assertEquals("Acme\"Co\\Ltd Box\n9000", extractJsonString(snapshot.reportJson, "Hardware"))
    }

    @Test
    fun logTextLayout() {
        val diag = newDiag()
        diag.setEnabled(true)
        diag.event("app.start") { tag("version", "0.1.0") }

        val snapshot = ReportSnapshot(minimalInputs(), diag, null)
        val expected = buildString {
            snapshot.summary.forEach { (k, v) -> append("# ").append(k).append(": ").append(v).append('\n') }
            append('\n')
            diag.linesSnapshot().forEach { append(it).append('\n') }
        }.trimEnd('\n')
        assertEquals(expected, snapshot.logText)

        val crashDir = tempFolder.newFolder()
        val diagCrash = DiagLog(dir = crashDir, clock = { 0L }, executor = directExecutor, mirror = {})
        val crash = CrashCapture(crashDir, diagCrash, summary = { emptyList() })
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        val withCrash = ReportSnapshot(minimalInputs(), diagCrash, crash)
        assertTrue(withCrash.logText.contains("\n--- crash ---\nexception: java.lang.RuntimeException"))
    }

    /** Hand-rolled JSON string decoder, just enough to check [ReportSnapshot.reportJson]'s
     * escaper round-trips without pulling in a JSON library.
     */
    private fun extractJsonString(json: String, key: String): String {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        require(start >= 0) { "key $key not found in $json" }
        var i = start + marker.length
        val sb = StringBuilder()
        while (json[i] != '"') {
            if (json[i] == '\\') {
                when (val next = json[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(json[i])
                i += 1
            }
        }
        return sb.toString()
    }
}
