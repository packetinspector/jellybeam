package tv.jellybeam.diag

import uniffi.jellybeam_core.DiagLevel
import uniffi.jellybeam_core.DiagSink

/**
 * docs/21 §2.1: the uniffi [DiagSink] Rust's `tracing` WARN/ERROR forwarder calls into --
 * forwards each record verbatim to [diag]'s ring via [DiagLog.coreRecord]; formatting and the
 * enabled-gate both live in [DiagLog], not here.
 */
class DiagSinkBridge(private val diag: DiagLog) : DiagSink {
    override fun onRecord(level: DiagLevel, target: String, message: String, fields: String) {
        diag.coreRecord(level.name.lowercase(), target, message, fields)
    }
}
