package tv.jellybeam.diag

import java.net.URLDecoder
import java.util.concurrent.Executor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/21 §7: the prefilled GitHub issue-form URL. */
class IssueUrlTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val directExecutor = Executor { it.run() }

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
    fun containsTemplateCategoryAndEncodedSummary() {
        val diag = DiagLog(dir = tempFolder.newFolder(), clock = { 0L }, executor = directExecutor, mirror = {})
        val snapshot = ReportSnapshot(minimalInputs(), diag, null)
        val url = IssueUrl.build(snapshot)

        assertTrue(url.startsWith("https://github.com/${IssueUrl.REPO}/issues/new?"))
        assertTrue(url.contains("template=tv-bug.yml"))
        assertTrue(url.contains("title=%5BTV%5D%20"))
        assertTrue(url.contains("category=${snapshot.category}"))

        val summaryParam = url.substringAfter("summary=")
        val decoded = URLDecoder.decode(summaryParam, "UTF-8")
        assertTrue(decoded.contains("- **App version:** 0.1.0 (build 1)"))
    }

    @Test
    fun staysUnderEightKb() {
        val diag = DiagLog(dir = tempFolder.newFolder(), clock = { 0L }, executor = directExecutor, mirror = {})
        diag.setEnabled(true)
        val big = "x".repeat(64)
        diag.event("error") {
            tag("where", "auth")
            tag("code", big)
        }
        val inputs = SummaryInputs(
            appVersion = "y".repeat(120),
            buildNumber = 123_456_789L,
            androidRelease = "z".repeat(120),
            sdkInt = 34,
            manufacturer = "m".repeat(120),
            model = "d".repeat(120),
            serverVersion = "s".repeat(120),
            serverCount = 999,
            playbackMode = "p".repeat(120),
        )
        val snapshot = ReportSnapshot(inputs, diag, null)
        val url = IssueUrl.build(snapshot)
        assertTrue(url.length < 8192)
    }
}
