package tv.jellybeam.ui.settings

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings

/** Pure formatting rules for docs/13 About, free of Compose/Android so they're plain-JVM-testable
 * (minSdk 23 `java.time` works via `coreLibraryDesugaring`, see app/build.gradle.kts).
 */
object AboutFormatting {

    private val BYTE_UNITS = listOf(
        R.string.settings_about_size_kb,
        R.string.settings_about_size_mb,
        R.string.settings_about_size_gb,
        R.string.settings_about_size_tb,
    )

    /** "512 B" below 1024; above that, one decimal place per [BYTE_UNITS] step ("1.0 KB", never a
     * bare "1 KB").
     */
    fun formatBytes(bytes: Long, strings: UiStrings): String {
        if (bytes < 1024) return strings.get(R.string.settings_about_size_bytes, bytes)
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < BYTE_UNITS.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return strings.get(BYTE_UNITS[unitIndex], value)
    }

    /** "Just now" / "n min ago" / "n h ago" below a day old, else a local "MMM d, HH:mm" stamp --
     * a sync from days ago needs a date, not a relative count.
     */
    fun formatSyncInstant(epochMs: Long, nowMs: Long, strings: UiStrings, locale: Locale = AppLocale.format): String {
        val diffSec = (nowMs - epochMs) / 1000
        return when {
            diffSec < 60 -> strings.get(R.string.updates_checked_just_now)
            diffSec < 3_600 -> (diffSec / 60).toInt().let { strings.plural(R.plurals.updates_checked_minutes_ago, it, it) }
            diffSec < 86_400 -> (diffSec / 3_600).toInt().let { strings.plural(R.plurals.updates_checked_hours_ago, it, it) }
            else -> Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d, HH:mm", locale))
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

/**
 * The scroll position the About pane settles on for a focus request whose default target is
 * [defaultTargetPx]: 0 or at least [headerBottomPx], never the band between, so the brand header
 * is never half-clipped. A band target at or past [currentScrollPx] snaps down to the header's
 * bottom, one behind it snaps to 0; everything is clamped to [maxScrollPx]. An unmeasured header
 * (0) passes the default through.
 */
internal fun aboutSnapScrollTarget(currentScrollPx: Int, defaultTargetPx: Int, headerBottomPx: Int, maxScrollPx: Int): Int {
    val target = defaultTargetPx.coerceIn(0, maxScrollPx.coerceAtLeast(0))
    if (headerBottomPx <= 0 || target <= 0 || target >= headerBottomPx) return target
    return if (target >= currentScrollPx) headerBottomPx.coerceAtMost(maxScrollPx.coerceAtLeast(0)) else 0
}

/**
 * Trailing space the About pane needs so its scroll range is 0 or at least [headerBottomPx]:
 * a range of 1..headerBottom-1 could only ever leave the brand header half-clipped. [maxScrollWithoutSlackPx]
 * is the range without that space.
 */
internal fun aboutBottomSlackPx(maxScrollWithoutSlackPx: Int, headerBottomPx: Int): Int =
    if (headerBottomPx <= 0 || maxScrollWithoutSlackPx <= 0 || maxScrollWithoutSlackPx >= headerBottomPx) 0 else headerBottomPx - maxScrollWithoutSlackPx
