package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

private class HttpDataSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
private class InvalidResponseCodeException(message: String) : Exception(message)

/** A codec/renderer/parsing-shaped exception -- must never be treated as recoverable. */
private class MediaCodecRendererDecoderInitializationException(message: String) : Exception(message)
private class ParserException(message: String) : Exception(message)

class ReconnectPolicyTest {

    // -- isRecoverable: classification --------------------------------

    @Test
    fun `a bare UnknownHostException is recoverable`() {
        assertTrue(ReconnectPolicy.isRecoverable(UnknownHostException("no network")))
    }

    @Test
    fun `a SocketTimeoutException wrapped as the direct cause of a generic IOException is recoverable`() {
        val ioException = java.io.IOException("source error", SocketTimeoutException("timed out"))
        assertTrue(ReconnectPolicy.isRecoverable(ioException))
    }

    @Test
    fun `a ConnectException several wraps deep in the cause chain is still found`() {
        val root = ConnectException("connection refused")
        val middle = java.io.IOException("load failed", root)
        val top = HttpDataSourceException("http request failed", middle)
        assertTrue(ReconnectPolicy.isRecoverable(top))
    }

    @Test
    fun `HttpDataSourceException itself without a transport cause is not recoverable`() {
        assertFalse(ReconnectPolicy.isRecoverable(HttpDataSourceException("bad response")))
    }

    @Test
    fun `HTTP response status without a transport cause is not recoverable at player level`() {
        assertFalse(ReconnectPolicy.isRecoverable(InvalidResponseCodeException("503")))
    }

    @Test
    fun `peer verification failure is permanent even though it is an SSLException`() {
        assertFalse(ReconnectPolicy.isRecoverable(SSLPeerUnverifiedException("hostname mismatch")))
    }

    @Test
    fun `certificate validation failure nested in a handshake is permanent`() {
        val handshake = SSLHandshakeException("certificate rejected").apply {
            initCause(CertPathValidatorException("expired"))
        }
        assertFalse(ReconnectPolicy.isRecoverable(handshake))
    }

    @Test
    fun `a decoder-initialization-shaped exception is NOT recoverable`() {
        assertFalse(ReconnectPolicy.isRecoverable(MediaCodecRendererDecoderInitializationException("no decoder for format")))
    }

    @Test
    fun `a parser-shaped exception is NOT recoverable`() {
        assertFalse(ReconnectPolicy.isRecoverable(ParserException("malformed container")))
    }

    @Test
    fun `a bare IOException with no network-shaped cause is NOT recoverable`() {
        // A bare IOException is as consistent with a truncated local file as a network failure, so
        // it is not auto-retried.
        assertFalse(ReconnectPolicy.isRecoverable(java.io.IOException("unexpected end of stream")))
    }

    @Test
    fun `null is not recoverable`() {
        assertFalse(ReconnectPolicy.isRecoverable(null))
    }

    @Test
    fun `a network cause within the scan depth is still found`() {
        var chain: Throwable = UnknownHostException("shallow enough")
        repeat(5) { index -> chain = java.io.IOException("wrap $index", chain) }
        assertTrue(ReconnectPolicy.isRecoverable(chain))
    }

    @Test
    fun `a network cause buried past the defensive scan-depth cap is not found`() {
        // Cause chain deeper than the scan cap: the cap is a finite bound, not evidence
        // normal-depth causes get missed.
        var chain: Throwable = UnknownHostException("buried")
        repeat(20) { index -> chain = java.io.IOException("wrap $index", chain) }
        assertFalse(ReconnectPolicy.isRecoverable(chain))
    }

    // -- decide: backoff schedule + give-up budget ----------------------

    @Test
    fun `the fixed opening schedule is 1s 2s 5s 10s`() {
        var elapsed = 0L
        val delays = (1..4).map { attempt ->
            val decision = ReconnectPolicy.decide(attempt, elapsed) as ReconnectPolicy.Decision.Retry
            elapsed += decision.delayMs
            decision.delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L), delays)
    }

    @Test
    fun `attempts past the fixed opening retry every 15s`() {
        var elapsed = 18_000L // matches the fixed opening's own total (1+2+5+10s)
        repeat(3) { index ->
            val attempt = 5 + index
            val decision = ReconnectPolicy.decide(attempt, elapsed)
            assertTrue("attempt $attempt should still be a Retry", decision is ReconnectPolicy.Decision.Retry)
            val delayMs = (decision as ReconnectPolicy.Decision.Retry).delayMs
            assertEquals(ReconnectPolicy.STEADY_STATE_RETRY_INTERVAL_MS, delayMs)
            elapsed += delayMs
        }
    }

    @Test
    fun `gives up once the next delay would push the running total past the ~2 minute budget`() {
        // Gives up at attempt 11 (~108s elapsed), not exactly 120s: schedule is 1, 2, 5, 10s, then
        // 15s steady-state.
        var elapsed = 0L
        var attempt = 1
        var retries = 0
        var gaveUp = false
        while (!gaveUp) {
            when (val decision = ReconnectPolicy.decide(attempt, elapsed)) {
                is ReconnectPolicy.Decision.Retry -> {
                    elapsed += decision.delayMs
                    retries++
                    attempt++
                }
                is ReconnectPolicy.Decision.GiveUp -> gaveUp = true
            }
            check(attempt < 1_000) { "runaway loop -- decide() never gave up" }
        }

        assertEquals(10, retries)
        assertEquals(108_000L, elapsed)
        assertEquals(11, attempt)
    }

    @Test
    fun `a fresh episode always starts at attempt 1 with zero elapsed`() {
        assertEquals(ReconnectPolicy.Decision.Retry(1_000L), ReconnectPolicy.decide(1, 0L))
    }

    @Test
    fun `decide rejects a non-positive attempt number`() {
        try {
            ReconnectPolicy.decide(0, 0L)
            org.junit.Assert.fail("expected an IllegalArgumentException for attempt = 0")
        } catch (expected: IllegalArgumentException) {
            // expected -- attempt is documented as 1-based
        }
    }
}
