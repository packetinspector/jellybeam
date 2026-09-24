package tv.jellybeam.diag

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/21 §1.3: the uncaught-exception capture, its files, and the Later-counter. */
class CrashCaptureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDiag(dir: File): DiagLog =
        DiagLog(dir = dir, clock = { 0L }, executor = Executor { it.run() }, mirror = {})

    private fun summary(): List<Pair<String, String>> = listOf("App version" to "0.1.0 (build 1)")

    @Test
    fun writesCaptureWithSummaryStackAndLogWhenEnabled() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        diag.setEnabled(true)
        diag.event("app.start") { tag("version", "0.1.0") }

        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.enabled = true
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        val file = File(dir, CrashCapture.CRASH_FILE_NAME)
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.contains("# App version: 0.1.0 (build 1)"))
        assertTrue(text.contains("--- crash ---"))
        assertTrue(text.contains("exception: java.lang.RuntimeException"))
        assertFalse(text.contains("boom"))
        assertTrue(text.contains("first app frame:"))
        assertTrue(text.contains("stack:"))
        assertTrue(text.contains("  at tv.jellybeam"))
        assertTrue(text.contains("--- log ---"))
        assertTrue(text.contains("app.start version=0.1.0"))
    }

    @Test
    fun writesNothingWhenDisabled() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.enabled = false
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertFalse(File(dir, CrashCapture.CRASH_FILE_NAME).exists())
    }

    @Test
    fun delegatesToPreviousHandler() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        var delegated = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        val defaultBefore = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val crash = CrashCapture(dir, diag, summary = { summary() })
            crash.install()
            crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertTrue(delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(defaultBefore)
        }
    }

    @Test
    fun pendingParsesClassAndFirstAppFrame() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.uncaughtException(Thread.currentThread(), IllegalStateException("bad state"))

        val info = crash.pending()
        assertEquals("java.lang.IllegalStateException", info?.exceptionClass)
        assertTrue(info?.firstAppFrame?.startsWith("tv.jellybeam") == true)
    }

    @Test
    fun shouldPromptStopsAfterThreeShows() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(crash.shouldPrompt())
        crash.markShown()
        assertTrue(crash.shouldPrompt())
        crash.markShown()
        assertTrue(crash.shouldPrompt())
        crash.markShown()
        assertFalse(crash.shouldPrompt())
    }

    @Test
    fun discardDeletesBothFiles() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        crash.markShown()
        assertTrue(File(dir, CrashCapture.CRASH_FILE_NAME).exists())
        assertTrue(File(dir, CrashCapture.STATE_FILE_NAME).exists())

        crash.discard()
        assertFalse(File(dir, CrashCapture.CRASH_FILE_NAME).exists())
        assertFalse(File(dir, CrashCapture.STATE_FILE_NAME).exists())
        assertNull(crash.pending())
    }

    @Test
    fun optOutMarkerDisablesCaptureFromConstruction() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        File(dir, CrashCapture.OFF_MARKER_NAME).createNewFile()

        val crash = CrashCapture(dir, diag, summary = { summary() })
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertFalse(File(dir, CrashCapture.CRASH_FILE_NAME).exists())
        assertNull(crash.pending())
    }

    @Test
    fun disablingWritesMarkerAndEnablingRemovesIt() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })
        val marker = File(dir, CrashCapture.OFF_MARKER_NAME)

        crash.enabled = false
        assertTrue(marker.exists())

        crash.enabled = true
        assertFalse(marker.exists())
    }

    @Test
    fun crashTextNeverContainsThrowableMessages() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })

        val rootMessage = "user testperson opened title Sample Movie Title at " +
            "/data/user/0/tv.jellybeam/files/secret-path on example.test"
        val causeMessage = "connect to backend.example.test failed for user testperson"
        val throwable = RuntimeException(rootMessage, IllegalStateException(causeMessage))
        crash.uncaughtException(Thread.currentThread(), throwable)

        val crashText = File(dir, CrashCapture.CRASH_FILE_NAME).readText()
        val inputs = SummaryInputs(
            appVersion = "0.1.0", buildNumber = 1, androidRelease = "14", sdkInt = 34,
            manufacturer = "Acme", model = "Box", serverVersion = null, serverCount = 0,
            playbackMode = "DirectPlay",
        )
        val snapshot = ReportSnapshot(inputs, diag, crash)

        val plantedFragments = listOf("testperson", "Sample Movie Title", "/data/user/0", "example.test")
        for (text in listOf(crashText, snapshot.logText, snapshot.reportJson)) {
            plantedFragments.forEach { fragment -> assertFalse(text.contains(fragment)) }
        }
        assertTrue(crashText.contains("exception: java.lang.RuntimeException"))
        assertTrue(crashText.contains("caused by: java.lang.IllegalStateException"))
        assertTrue(crashText.contains("  at tv.jellybeam"))
    }

    @Test
    fun upgradeWithPersistedOptOutAndNoMarkerStartsDisabled() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val settings = tempFolder.newFile("settings.json")
        settings.writeText(
            """{"show_clock":true,"crash_reports_enabled":false,"diagnostic_logging_enabled":false}""",
        )

        val crash = CrashCapture(dir, diag, { summary() }, persistedSettings = settings)
        crash.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertFalse(File(dir, CrashCapture.CRASH_FILE_NAME).exists())
        assertTrue(File(dir, CrashCapture.OFF_MARKER_NAME).exists())
    }

    @Test
    fun persistedTrueOrMissingFileStartsEnabled() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val settingsTrue = tempFolder.newFile("settings-true.json")
        settingsTrue.writeText("""{"crash_reports_enabled":true}""")

        val crashWithTrue = CrashCapture(dir, diag, { summary() }, persistedSettings = settingsTrue)
        assertTrue(crashWithTrue.enabled)
        assertFalse(File(dir, CrashCapture.OFF_MARKER_NAME).exists())

        val missingDir = tempFolder.newFolder()
        val crashWithMissingFile = CrashCapture(
            missingDir,
            newDiag(missingDir),
            { summary() },
            persistedSettings = File(missingDir, "no-such-settings.json"),
        )
        assertTrue(crashWithMissingFile.enabled)
        assertFalse(File(missingDir, CrashCapture.OFF_MARKER_NAME).exists())
    }

    @Test
    fun markerWinsOverPersistedTrue() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        File(dir, CrashCapture.OFF_MARKER_NAME).createNewFile()
        val settings = tempFolder.newFile("settings.json")
        settings.writeText("""{"crash_reports_enabled":true}""")

        val crash = CrashCapture(dir, diag, { summary() }, persistedSettings = settings)

        assertFalse(crash.enabled)
        assertTrue(File(dir, CrashCapture.OFF_MARKER_NAME).exists())
    }

    @Test
    fun causeChainIsCappedAtFour() {
        val dir = tempFolder.newFolder()
        val diag = newDiag(dir)
        val crash = CrashCapture(dir, diag, summary = { summary() })

        var current: Throwable = RuntimeException("level-0")
        for (i in 1..5) {
            current = RuntimeException("level-$i", current)
        }
        crash.uncaughtException(Thread.currentThread(), current)

        val text = File(dir, CrashCapture.CRASH_FILE_NAME).readText()
        val causedByCount = Regex("caused by:").findAll(text).count()
        assertEquals(4, causedByCount)
    }
}
