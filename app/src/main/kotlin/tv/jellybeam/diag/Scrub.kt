package tv.jellybeam.diag

/** docs/21 §1.3: the same URL/secret redaction Rust applies to `tracing` messages, used here on
 * exception text before it ever reaches a crash capture or report.
 */
object Scrub {
    private const val MAX_LENGTH = 200
    private val URL_PATTERN = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    private val SECRET_PATTERN = Regex("""(?i)\b(api_key|apikey|token|password)=\S+""")

    /** URL and secret-value redaction with no line or length limit -- for multi-line text such
     * as a full stack trace, where [scrub]'s truncation would cut too much.
     */
    fun redact(text: String): String {
        val urlSafe = URL_PATTERN.replace(text, "<url>")
        return SECRET_PATTERN.replace(urlSafe) { "${it.groupValues[1]}=<redacted>" }
    }

    /** First line only, then [redact], capped to [MAX_LENGTH] chars: the exception summary line
     * in a crash capture.
     */
    fun scrub(message: String): String = redact(message.substringBefore('\n')).take(MAX_LENGTH)
}
