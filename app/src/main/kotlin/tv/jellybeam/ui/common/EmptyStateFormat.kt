package tv.jellybeam.ui.common

import java.net.URI
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings

/**
 * `host` or `host:port` for a saved server URL, shown verbatim (docs/brand.md §6.2/§6.3);
 * the scheme's default port is omitted. `null` when the URL is missing or has no host.
 */
fun serverHostLabel(serverUrl: String?): String? {
    val uri = runCatching { URI(serverUrl ?: return null) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val defaultPort = when (uri.scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
    return if (uri.port == -1 || uri.port == defaultPort) host else "$host:${uri.port}"
}

/** The empty-library spec strip: `HOST:PORT │ N LIBRARIES │ 0 ITEMS` (§6.2), host omitted when unknown. */
fun emptyLibrarySpecLine(strings: UiStrings, host: String?, libraries: Int, items: Int): String {
    val parts = listOfNotNull(
        host,
        strings.plural(R.plurals.common_library_count, libraries, libraries),
        strings.plural(R.plurals.common_item_count, items, items),
    )
    return parts.joinToString(" │ ")
}
