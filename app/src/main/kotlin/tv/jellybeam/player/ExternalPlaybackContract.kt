package tv.jellybeam.player

import java.net.URI

/** Public intent/deep-link contract for starting an item on Jellybeam's active Jellyfin account. */
object ExternalPlaybackContract {
    const val ACTION_PLAY = "tv.jellybeam.action.PLAY"
    /**
     * "Still watching?" Stop/timeout (docs/feature-dev/spec-still-watching-and-lan-discovery.md
     * Feature A): explicit-component-only, never exported/deep-linked; routes to [EXTRA_ITEM_ID]'s
     * detail page after [PlaybackViewModel.stillWatchingStop].
     */
    const val ACTION_OPEN_DETAIL = "tv.jellybeam.action.OPEN_DETAIL"
    const val EXTRA_ITEM_ID = "tv.jellybeam.extra.ITEM_ID"
    const val DEEP_LINK_SCHEME = "jellybeam"
    const val DEEP_LINK_HOST = "play"

    /** Parses the documented exported/deep-linked shapes plus internal-only [ACTION_OPEN_DETAIL];
     * unrelated intents return [ExternalPlaybackIntentResult.NotExternal].
     */
    internal fun parse(
        action: String?,
        itemIdExtra: String?,
        dataUri: String?,
    ): ExternalPlaybackIntentResult = when (action) {
        ACTION_PLAY -> validated(itemIdExtra, ExternalPlaybackIntentResult::Play)
        ACTION_OPEN_DETAIL -> validated(itemIdExtra, ExternalPlaybackIntentResult::OpenDetail)
        android.content.Intent.ACTION_VIEW -> parseDeepLink(dataUri)
        else -> ExternalPlaybackIntentResult.NotExternal
    }

    private fun parseDeepLink(dataUri: String?): ExternalPlaybackIntentResult {
        val uri = try {
            dataUri?.let(::URI)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ExternalPlaybackIntentResult.Invalid

        if (!uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) ||
            !uri.host.equals(DEEP_LINK_HOST, ignoreCase = true) ||
            uri.port != -1 ||
            uri.userInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return ExternalPlaybackIntentResult.Invalid
        }

        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        return if (segments.size == 1) validated(segments.single(), ExternalPlaybackIntentResult::Play) else ExternalPlaybackIntentResult.Invalid
    }

    private fun validated(
        rawItemId: String?,
        build: (String) -> ExternalPlaybackIntentResult,
    ): ExternalPlaybackIntentResult {
        val itemId = rawItemId?.trim().orEmpty()
        return if (ITEM_ID.matches(itemId)) {
            build(canonicalItemId(itemId))
        } else {
            ExternalPlaybackIntentResult.Invalid
        }
    }

    /** A UUID in any spelling Jellyfin hands out becomes the mirror's dashed lowercase form, so an
     * external start is the same item the UI would play; any other id passes through.
     */
    internal fun canonicalItemId(itemId: String): String {
        if (!UUID_HEX.matches(itemId) && !DASHED_UUID.matches(itemId)) return itemId
        val lower = itemId.replace("-", "").lowercase()
        return "${lower.substring(0, 8)}-${lower.substring(8, 12)}-${lower.substring(12, 16)}-" +
            "${lower.substring(16, 20)}-${lower.substring(20)}"
    }

    private val UUID_HEX = Regex("[0-9A-Fa-f]{32}")
    private val DASHED_UUID = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

    // Jellyfin BaseItemIds are UUID-like; restricting to path-safe characters also rejects
    // accidental URLs, shell quoting mistakes, controls, and encoded path separators.
    private val ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}")
}

internal sealed interface ExternalPlaybackIntentResult {
    data object NotExternal : ExternalPlaybackIntentResult
    data object Invalid : ExternalPlaybackIntentResult
    data class Play(val itemId: String) : ExternalPlaybackIntentResult

    data class OpenDetail(val itemId: String) : ExternalPlaybackIntentResult
}
