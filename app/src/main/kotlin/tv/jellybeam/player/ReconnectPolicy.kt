package tv.jellybeam.player

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Policy behind the "Reconnecting…" flow: a network blip must not be treated as fatal. Free of any
 * `android.*`/`androidx.media3.*` import so [isRecoverable] and [decide] are plain-JVM-testable.
 * [tv.jellybeam.player.PlaybackViewModel] is the only caller.
 */
object ReconnectPolicy {

    /** A chain longer than this counts as "no cause found", guarding a pathological cycle. */
    private const val MAX_CAUSE_CHAIN_DEPTH = 16

    /**
     * `true` only when [error]'s cause chain contains a concrete transient transport failure (HTTP
     * codes like 401/403/404 are permanent -- see [NetworkAwareLoadErrorHandlingPolicy]).
     * Certificate failures override a surrounding [SSLException]: those never recover by retrying.
     */
    fun isRecoverable(error: Throwable?): Boolean {
        // Bounded chain checked for permanent TLS failures before a broader SSLException higher up.
        var certificateCause = error
        var certificateDepth = 0
        while (certificateCause != null && certificateDepth < MAX_CAUSE_CHAIN_DEPTH) {
            if (
                certificateCause is SSLPeerUnverifiedException ||
                certificateCause is CertificateException ||
                certificateCause is CertPathValidatorException
            ) {
                return false
            }
            val next = certificateCause.cause
            if (next == null || next === certificateCause) break
            certificateCause = next
            certificateDepth++
        }

        var cause = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
            if (
                cause is UnknownHostException ||
                cause is NoRouteToHostException ||
                cause is SocketTimeoutException ||
                cause is ConnectException ||
                cause is SocketException ||
                cause is SSLException
            ) {
                return true
            }
            val next = cause.cause
            if (next == null || next === cause) break // self-referential cause -- Throwable's own documented terminal case
            cause = next
            depth++
        }
        return false
    }

    /** [decide]'s fixed opening backoff (1-based); past this falls back to
     * [STEADY_STATE_RETRY_INTERVAL_MS].
     */
    val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)

    /** How often to retry once past [BACKOFF_SCHEDULE_MS]'s fixed opening. */
    const val STEADY_STATE_RETRY_INTERVAL_MS = 15_000L

    /**
     * Total time [decide] spends on retries before [Decision.GiveUp] -- checked against the
     * *candidate* delay so the actual total spent lands under budget, never over.
     */
    const val GIVE_UP_BUDGET_MS = 120_000L

    /** [decide]'s result: wait [Retry.delayMs] then retry, or [GiveUp] outright. */
    sealed interface Decision {
        data class Retry(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    /**
     * What to do for retry attempt [attempt] (1-based; a fresh episode calls `decide(1, 0L)`),
     * given [elapsedMsSoFar] (summed delays, not wall-clock). Returns [Decision.GiveUp] when this
     * attempt's delay would push the total past [GIVE_UP_BUDGET_MS], checked before the wait.
     */
    fun decide(attempt: Int, elapsedMsSoFar: Long): Decision {
        require(attempt >= 1) { "attempt is 1-based; was $attempt" }
        val delayMs = BACKOFF_SCHEDULE_MS.getOrElse(attempt - 1) { STEADY_STATE_RETRY_INTERVAL_MS }
        return if (elapsedMsSoFar + delayMs > GIVE_UP_BUDGET_MS) Decision.GiveUp else Decision.Retry(delayMs)
    }
}
