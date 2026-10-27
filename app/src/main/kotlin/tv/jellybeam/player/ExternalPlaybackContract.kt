package tv.jellybeam.player

import java.net.URI

/** Public intent/deep-link contract (docs/05 "external", docs/17 §2): start an item, or open its detail page, on Jellybeam's active Jellyfin account. */
object ExternalPlaybackContract {
    const val ACTION_PLAY = "tv.jellybeam.action.PLAY"
    /** Opens [EXTRA_ITEM_ID]'s detail page; also the "Still watching?" Stop route (docs/12 §13). */
    const val ACTION_OPEN_DETAIL = "tv.jellybeam.action.OPEN_DETAIL"
    const val EXTRA_ITEM_ID = "tv.jellybeam.extra.ITEM_ID"

    /** PLAY: boolean, ignore the saved position; mutually exclusive with [EXTRA_START_MS]. */
    const val EXTRA_START_FROM_BEGINNING = "tv.jellybeam.extra.START_FROM_BEGINNING"

    /** PLAY: long milliseconds >= 0, an absolute start; past the end opens 10 s before it. */
    const val EXTRA_START_MS = "tv.jellybeam.extra.START_MS"
    const val DEEP_LINK_SCHEME = "jellybeam"
    const val DEEP_LINK_HOST = "play"
    const val DEEP_LINK_HOST_ITEM = "item"

    /** Parses the documented shapes (docs/05 "external"); unrelated intents return
     * [ExternalPlaybackIntentResult.NotExternal]. The `*Extra` values are the raw extras, `null` when absent.
     */
    internal fun parse(
        action: String?,
        itemIdExtra: String?,
        dataUri: String?,
        startMsExtra: Any? = null,
        startFromBeginningExtra: Any? = null,
    ): ExternalPlaybackIntentResult = when (action) {
        ACTION_PLAY -> parsePlay(itemIdExtra, startMsExtra, startFromBeginningExtra)
        ACTION_OPEN_DETAIL -> validated(itemIdExtra, ExternalPlaybackIntentResult::OpenDetail)
        android.content.Intent.ACTION_VIEW -> parseDeepLink(dataUri)
        else -> ExternalPlaybackIntentResult.NotExternal
    }

    private fun parsePlay(itemIdExtra: String?, startMsExtra: Any?, startFromBeginningExtra: Any?): ExternalPlaybackIntentResult {
        val startMs = when (startMsExtra) {
            null -> null
            is Long -> startMsExtra
            is Int -> startMsExtra.toLong()
            else -> return ExternalPlaybackIntentResult.Invalid
        }
        val fromStart = when (startFromBeginningExtra) {
            null -> null
            is Boolean -> startFromBeginningExtra
            else -> return ExternalPlaybackIntentResult.Invalid
        }
        if (startMs != null && (startMs < 0L || fromStart != null)) return ExternalPlaybackIntentResult.Invalid
        return validated(itemIdExtra) { ExternalPlaybackIntentResult.Play(it, fromStart == true, startMs) }
    }

    private fun parseDeepLink(dataUri: String?): ExternalPlaybackIntentResult {
        val uri = try {
            dataUri?.let(::URI)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ExternalPlaybackIntentResult.Invalid

        val play = uri.host.equals(DEEP_LINK_HOST, ignoreCase = true)
        if (!uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) ||
            !(play || uri.host.equals(DEEP_LINK_HOST_ITEM, ignoreCase = true)) ||
            uri.port != -1 ||
            uri.userInfo != null ||
            uri.rawFragment != null
        ) {
            return ExternalPlaybackIntentResult.Invalid
        }

        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 1) return ExternalPlaybackIntentResult.Invalid
        if (!play) {
            return if (uri.rawQuery == null) validated(segments.single(), ExternalPlaybackIntentResult::OpenDetail) else ExternalPlaybackIntentResult.Invalid
        }
        // Raw query, so an encoded `&` or `=` can't pass as a separator; only `from=start` and one `t=<seconds>` exist.
        val pairs = uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) }.orEmpty()
        val keys = pairs.map { it.first() }
        if (pairs.any { it.size != 2 } || keys.toSet().size != keys.size || !setOf("from", "t").containsAll(keys)) {
            return ExternalPlaybackIntentResult.Invalid
        }
        val query = pairs.associate { it[0] to it[1] }
        val fromStart = query["from"]?.let { if (it == "start") true else return ExternalPlaybackIntentResult.Invalid }
        val seconds = query["t"]?.let { if (SECONDS.matches(it)) it.toLong() else return ExternalPlaybackIntentResult.Invalid }
        if (fromStart != null && seconds != null) return ExternalPlaybackIntentResult.Invalid
        return validated(segments.single()) { ExternalPlaybackIntentResult.Play(it, fromStart == true, seconds?.times(1000L)) }
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

    private val SECONDS = Regex("[0-9]{1,10}")

    private val UUID_HEX = Regex("[0-9A-Fa-f]{32}")
    private val DASHED_UUID = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

    // Jellyfin BaseItemIds are UUID-like; restricting to path-safe characters also rejects
    // accidental URLs, shell quoting mistakes, controls, and encoded path separators.
    private val ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}")
}

internal sealed interface ExternalPlaybackIntentResult {
    data object NotExternal : ExternalPlaybackIntentResult
    data object Invalid : ExternalPlaybackIntentResult
    data class Play(val itemId: String, val startFromBeginning: Boolean = false, val startMs: Long? = null) : ExternalPlaybackIntentResult {
        /** The owner's request; the parse already made the two start extras exclusive. */
        fun kind() = Req.Kind.Play(
            itemId,
            when {
                startFromBeginning -> Req.Start.Beginning
                startMs != null -> Req.Start.At(startMs)
                else -> Req.Start.Resume
            },
        )
    }

    data class OpenDetail(val itemId: String) : ExternalPlaybackIntentResult
}
