package tv.jellybeam.nav

/** Two-press guard for the Back action that would leave the app: [press] is `true` only when a
 * prior press landed inside [windowMs].
 */
class ExitConfirmationGate(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var armedAtMs: Long? = null

    init {
        require(windowMs > 0L) { "windowMs must be positive" }
    }

    fun press(nowMs: Long): Boolean {
        val armed = armedAtMs
        if (armed != null && nowMs - armed in 0..windowMs) {
            armedAtMs = null
            return true
        }
        armedAtMs = nowMs
        return false
    }

    fun reset() {
        armedAtMs = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
