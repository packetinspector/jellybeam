package tv.jellybeam.diag

import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/21 §2, §2.2, §2.3: recorder, ring cap, aliasing, flush/read-back and redaction. */
class DiagLogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val directExecutor = Executor { it.run() }

    private fun newDiagLog(dir: File, atMs: () -> Long = { 0L }): DiagLog =
        DiagLog(dir = dir, clock = atMs, executor = directExecutor, mirror = {})

    @Test
    fun disabledEventWritesNothing() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.event("app.start") { tag("version", "0.1.0") }
        assertTrue(diag.linesSnapshot().isEmpty())
    }

    @Test
    fun lineFormatIsIsoUtcThenEventThenFields() {
        val atMs = Instant.parse("2026-09-10T20:15:01.123Z").toEpochMilli()
        val diag = newDiagLog(tempFolder.newFolder(), atMs = { atMs })
        diag.setEnabled(true)
        diag.event("app.start") {
            tag("version", "0.1.0")
            bool("cold", true)
        }
        assertEquals(
            listOf("2026-09-10T20:15:01.123Z app.start version=0.1.0 cold=true"),
            diag.linesSnapshot(),
        )
    }

    @Test
    fun tagRejectsUrlsTitlesAndSpaces() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.setEnabled(true)
        diag.event("nav.screen") { tag("screen", "https://example.test/x") }
        diag.event("nav.screen") { tag("screen", "The Movie Title") }
        diag.event("nav.screen") { tag("screen", "a b") }

        val lines = diag.linesSnapshot()
        assertEquals(3, lines.size)
        lines.forEach { assertTrue(it.endsWith("screen=<invalid>")) }
    }

    @Test
    fun aliasesNumberFirstSeenPerKindAndResetOnClear() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.setEnabled(true)
        diag.event("sync.pass") { item("item", "id-a") }
        diag.event("sync.pass") { item("item", "id-b") }
        diag.event("sync.pass") { item("item", "id-a") }
        diag.event("sync.pass") { server("server", "srv-a") }

        val lines = diag.linesSnapshot()
        assertTrue(lines[0].endsWith("item=item#1"))
        assertTrue(lines[1].endsWith("item=item#2"))
        assertTrue(lines[2].endsWith("item=item#1"))
        assertTrue(lines[3].endsWith("server=server#1"))

        diag.clear()
        diag.event("sync.pass") { item("item", "id-a") }
        assertTrue(diag.linesSnapshot().single().endsWith("item=item#1"))
    }

    @Test
    fun ringEvictsOldestPastTwoThousandLines() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.setEnabled(true)
        repeat(2005) { i -> diag.event("nav.screen") { num("i", i.toLong()) } }

        val lines = diag.linesSnapshot()
        assertEquals(2000, lines.size)
        assertTrue(lines.first().endsWith("i=5"))
        assertTrue(lines.last().endsWith("i=2004"))
    }

    @Test
    fun ringEvictsOldestPastByteCap() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.setEnabled(true)
        val bigTag = "x".repeat(64)
        repeat(1600) { i ->
            diag.event("nav.screen") {
                tag("a", bigTag)
                tag("b", bigTag)
                num("i", i.toLong())
            }
        }

        val lines = diag.linesSnapshot()
        val totalBytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        assertTrue(lines.size < 1600)
        assertTrue(totalBytes <= 262_144)
    }

    @Test
    fun flushWritesRingAndReadBackBecomesPrevious() {
        val dir = tempFolder.newFolder()
        val diag1 = newDiagLog(dir)
        diag1.setEnabled(true)
        diag1.event("app.start") { tag("version", "0.1.0") }
        diag1.flush()

        val diag2 = newDiagLog(dir)
        diag2.setEnabled(true)
        diag2.event("app.start") { tag("version", "0.1.0") }

        assertEquals(2, diag2.linesSnapshot().size)
    }

    @Test
    fun disablingClearsRingAndDeletesFile() {
        val dir = tempFolder.newFolder()
        val diag = newDiagLog(dir)
        diag.setEnabled(true)
        diag.event("app.start") { tag("version", "0.1.0") }
        diag.flush()
        assertTrue(File(dir, DiagLog.RING_FILE_NAME).exists())

        diag.setEnabled(false)
        assertTrue(diag.linesSnapshot().isEmpty())
        assertFalse(File(dir, DiagLog.RING_FILE_NAME).exists())
    }

    @Test
    fun plantedSecretsNeverAppearInDump() {
        val diag = newDiagLog(tempFolder.newFolder())
        diag.setEnabled(true)
        val hostname = "storeserver.example.test"
        val token = "tok-ABC123"
        val title = "Secret Title"
        val path = "/media/x.mkv"

        // A hostname/token made only of tag-safe characters would pass tag()'s charset check
        // untouched -- docs/21 §2.3 keeps such identity out of tag() by routing it through
        // item()/server() instead, so that is what is exercised here.
        diag.event("settings.change") {
            tag("field", "https://$hostname/$token")
            tag("old", title)
            tag("new", path)
            item("item", hostname)
            item("item2", token)
            server("server", hostname)
            server("server2", token)
            num("n", 1L)
            ms("ms", 2L)
            bool("b", true)
        }

        val dump = diag.linesSnapshot().joinToString("\n")
        assertFalse(dump.contains(hostname))
        assertFalse(dump.contains(token))
        assertFalse(dump.contains(title))
        assertFalse(dump.contains(path))
    }

    @Test
    fun enabledMarkerSurvivesReconstruction() {
        val dir = tempFolder.newFolder()
        val atMs = Instant.parse("2026-09-10T20:15:01.000Z").toEpochMilli()
        val diag1 = newDiagLog(dir, atMs = { atMs })
        diag1.setEnabled(true)

        val diag2 = newDiagLog(dir)
        assertTrue(diag2.enabled)
        assertEquals(atMs, diag2.status.value.onSinceMs)
    }

    @Test
    fun setEnabledSameValueIsNoOp() {
        val dir = tempFolder.newFolder()
        File(dir, DiagLog.RING_FILE_NAME).writeText("2026-09-10T20:15:01.000Z app.start version=0.1.0\n")
        var now = 1_000L
        val diag = newDiagLog(dir, atMs = { now })

        diag.setEnabled(false)
        assertEquals(1, diag.linesSnapshot().size)

        diag.setEnabled(true)
        val onSince = diag.status.value.onSinceMs
        now = 5_000L
        diag.setEnabled(true)
        assertEquals(onSince, diag.status.value.onSinceMs)

        diag.event("nav.screen") { tag("screen", "home") }
        assertEquals(2, diag.linesSnapshot().size)
    }

    @Test
    fun goldenDumpMatchesFixture() {
        var now = Instant.parse("2026-09-10T20:15:01.000Z").toEpochMilli()
        val diag = newDiagLog(tempFolder.newFolder(), atMs = { now })
        diag.setEnabled(true)

        diag.event("app.start") {
            tag("version", "0.1.0")
            bool("cold", true)
        }
        now += 500
        diag.event("auth.restore") {
            server("server", "srv-a")
            ms("ms", 42L)
            tag("result", "ok")
        }
        now += 500
        diag.event("playback.plan") {
            item("item", "item-a")
            tag("mode", "DirectPlay")
        }

        val expected = listOf(
            "2026-09-10T20:15:01.000Z app.start version=0.1.0 cold=true",
            "2026-09-10T20:15:01.500Z auth.restore server=server#1 ms=42 result=ok",
            "2026-09-10T20:15:02.000Z playback.plan item=item#1 mode=DirectPlay",
        )
        assertEquals(expected, diag.linesSnapshot())
    }
}
