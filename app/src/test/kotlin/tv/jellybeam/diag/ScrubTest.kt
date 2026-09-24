package tv.jellybeam.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** docs/21 §1.3: the exception-message redaction pass. */
class ScrubTest {

    @Test
    fun urlRunsAreReplaced() {
        val input = "failed to reach https://storeserver.example.test:8096/path?x=1 during sync"
        val result = Scrub.scrub(input)
        assertFalse(result.contains("storeserver.example.test"))
        assertEquals("failed to reach <url> during sync", result)
    }

    @Test
    fun apiKeyTokenAndPasswordValuesAreRedacted() {
        val input = "api_key=abc123 token=xyz789 password=hunter2 ok"
        assertEquals("api_key=<redacted> token=<redacted> password=<redacted> ok", Scrub.scrub(input))
    }

    @Test
    fun messageIsCutAtFirstNewlineAndCappedAtTwoHundredChars() {
        val long = "a".repeat(300)
        val result = Scrub.scrub("$long\nsecond line ignored")
        assertEquals(200, result.length)
        assertEquals("a".repeat(200), result)
    }

    @Test
    fun redactAppliesToMultiLineTextWithoutTruncating() {
        val input = "line one https://storeserver.example.test/x\ntoken=xyz789\nline three"
        val result = Scrub.redact(input)
        assertFalse(result.contains("storeserver.example.test"))
        assertFalse(result.contains("xyz789"))
        assertEquals("line one <url>\ntoken=<redacted>\nline three", result)
    }
}
