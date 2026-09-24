package tv.jellybeam.ui.settings

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure formatting rules for docs/13 About, free of Compose/Android so they're plain-JVM-testable
 * (minSdk 23 `java.time` works via `coreLibraryDesugaring`, see app/build.gradle.kts).
 */
object AboutFormatting {

    private val BYTE_UNITS = listOf("KB", "MB", "GB", "TB")
    private val SYNC_INSTANT_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

    /** "512 B" below 1024; above that, one decimal place per [BYTE_UNITS] step ("1.0 KB", never a
     * bare "1 KB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < BYTE_UNITS.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unitIndex])
    }

    /** "Just now" / "n min ago" / "n h ago" below a day old, else a local "MMM d, HH:mm" stamp --
     * a sync from days ago needs a date, not a relative count.
     */
    fun formatSyncInstant(epochMs: Long, nowMs: Long): String {
        val diffSec = (nowMs - epochMs) / 1000
        return when {
            diffSec < 60 -> "Just now"
            diffSec < 3_600 -> "${diffSec / 60} min ago"
            diffSec < 86_400 -> "${diffSec / 3_600} h ago"
            else -> Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(SYNC_INSTANT_FORMAT)
        }
    }

    /** Martian Mono's advance width in em, measured on device. */
    private const val MONO_ADVANCE_EM = 0.69f

    /** The largest size in [minSp]..[maxSp] at which [chars] monospace characters fit
     * [availableSp] of width -- one size for a whole tile row, so a 1,234,567 library shrinks
     * every number together instead of clipping one.
     */
    fun fitMonoFontSp(chars: Int, availableSp: Float, maxSp: Float, minSp: Float): Float {
        if (chars <= 0) return maxSp
        return (availableSp / (chars * MONO_ADVANCE_EM)).coerceIn(minSp, maxSp)
    }

    /** `null` on anything that isn't a parseable RFC 3339 timestamp -- [MirrorStats.lastDeltaSync]
     * fails open to "Never" rather than crashing About.
     */
    fun parseRfc3339Millis(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    /** [uniffi.jellybeam_core.ServerInfoSnapshot.serverUrl]'s host, `:port` appended only when it's
     * not the scheme's default -- `null` on an unparseable URL or one with no host.
     */
    fun hostOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        return if (port == -1 || isDefaultPort(uri.scheme, port)) host else "$host:$port"
    }

    private fun isDefaultPort(scheme: String?, port: Int): Boolean = when (scheme?.lowercase(Locale.US)) {
        "http" -> port == 80
        "https" -> port == 443
        else -> false
    }
}
