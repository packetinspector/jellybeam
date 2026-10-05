package tv.jellybeam.data

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrRefusal
import uniffi.jellybeam_core.UnreachableReason

/**
 * A message fit to show a user in the sign-in error card, worded per docs/27 §3. `Api`/`Cache`
 * carry the underlying error's `Display` text verbatim in `detail` (not `message`, which
 * `CoreException` overrides).
 */
fun CoreException.displayMessage(strings: UiStrings): String = when (this) {
    is CoreException.NotSignedIn -> strings.get(R.string.error_not_signed_in)
    is CoreException.MirrorNotOpen -> strings.get(R.string.error_library_not_ready)
    is CoreException.Unauthorized -> strings.get(R.string.error_authorization_expired)
    is CoreException.Api -> detail
    is CoreException.Cache -> detail
    is CoreException.UpdateStorageUnavailable -> strings.get(R.string.error_update_storage_unavailable)
    // Direct Play is default, transcoding opt-in (CLAUDE.md); prepare_playback refuses rather
    // than transcode silently (docs/18-playback-quality.md §1/§3).
    is CoreException.WouldTranscode -> strings.get(R.string.error_would_transcode, reasons)
    // Only reachable via a stale drawer row racing a concurrent account removal.
    is CoreException.InvalidSessionIndex -> strings.get(R.string.error_server_removed)
    // docs/14-seerr-discover.md: a seerr_* call ran before the account had a saved Seerr
    // connection; routes Discover's error toward Settings.
    is CoreException.SeerrNotConfigured -> strings.get(R.string.error_discover_not_set_up)
    // docs/18-playback-quality.md §2 staleness guard; PlaybackViewModel catches this before
    // displayMessage() -- kept here only for exhaustiveness.
    is CoreException.StalePlaybackSession -> strings.get(R.string.error_playback_session_stale)
    // docs/13-feature-list.md "sign-in": plain-English copy for signIn/reauthorizeSession/
    // quick-connect failures, no jargon or URLs of the user's own.
    is CoreException.InvalidServerAddress -> strings.get(R.string.error_invalid_server_address)
    is CoreException.ServerUnreachable -> strings.get(R.string.error_server_unreachable, host, unreachableReason(strings, reason, detail))
    is CoreException.HttpsNotOffered -> strings.get(R.string.error_https_not_offered, host)
    is CoreException.NotJellyfinServer -> strings.get(R.string.error_not_jellyfin_server, host, status.toInt())
    is CoreException.InvalidCredentials -> strings.get(R.string.error_invalid_credentials)
    // docs/14-seerr-discover.md: the Seerr server was reached and refused the sign-in; a Seerr
    // local account signs in with its email address, which the field label also says.
    is CoreException.SeerrInvalidCredentials -> when (method) {
        SeerrAuthMethod.LOCAL -> strings.get(R.string.error_seerr_wrong_email_or_password)
        SeerrAuthMethod.JELLYFIN -> strings.get(R.string.error_invalid_credentials)
        SeerrAuthMethod.API_KEY -> strings.get(R.string.error_seerr_api_key_rejected)
    }
    // docs/14-seerr-discover.md "Auth": Seerr answered with its own error, so the address is
    // right and the fix is on the Seerr side; say which.
    // docs/14-seerr-discover.md: Seerr never answered; name why when known, never the URL or
    // transport text.
    is CoreException.SeerrUnreachable -> when (reason) {
        UnreachableReason.OTHER -> strings.get(R.string.error_seerr_unreachable)
        else -> strings.get(R.string.error_seerr_unreachable_reason, unreachableReason(strings, reason, ""))
    }
    is CoreException.SeerrSignInRefused -> when (reason) {
        SeerrRefusal.MEDIA_SERVER_SIGN_IN -> strings.get(R.string.error_seerr_media_server_sign_in)
        SeerrRefusal.NEW_USERS_BLOCKED -> strings.get(R.string.error_seerr_new_users_blocked)
        SeerrRefusal.METHOD_DISABLED -> strings.get(R.string.error_seerr_method_disabled)
        SeerrRefusal.OTHER ->
            if (detail.isBlank()) strings.get(R.string.error_seerr_refused) else strings.get(R.string.error_seerr_refused_detail, detail)
    }
}

/** `OTHER` shows the transport's own text verbatim, like server text (docs/27 §3). */
private fun unreachableReason(strings: UiStrings, reason: UnreachableReason, detail: String): String = when (reason) {
    UnreachableReason.NAME_NOT_RESOLVED -> strings.get(R.string.error_unreachable_name_not_resolved)
    UnreachableReason.CONNECTION_REFUSED -> strings.get(R.string.error_unreachable_connection_refused)
    UnreachableReason.TIMED_OUT -> strings.get(R.string.error_unreachable_timed_out)
    UnreachableReason.NETWORK_UNREACHABLE -> strings.get(R.string.error_unreachable_network_unreachable)
    UnreachableReason.OTHER -> detail
}

/** docs/21-user-reporting.md: the variant as a fixed word for the diagnostic log -- never its
 * `detail`/`host` text, and never `simpleName`, which R8 renames.
 */
fun CoreException.diagLabel(): String = when (this) {
    is CoreException.NotSignedIn -> "not_signed_in"
    is CoreException.MirrorNotOpen -> "mirror_not_open"
    is CoreException.Unauthorized -> "unauthorized"
    is CoreException.Api -> "api"
    is CoreException.Cache -> "cache"
    is CoreException.UpdateStorageUnavailable -> "update_storage_unavailable"
    is CoreException.WouldTranscode -> "would_transcode"
    is CoreException.InvalidSessionIndex -> "invalid_session_index"
    is CoreException.SeerrNotConfigured -> "seerr_not_configured"
    is CoreException.StalePlaybackSession -> "stale_playback_session"
    is CoreException.InvalidServerAddress -> "invalid_server_address"
    is CoreException.ServerUnreachable -> "server_unreachable"
    is CoreException.HttpsNotOffered -> "https_not_offered"
    is CoreException.NotJellyfinServer -> "not_jellyfin_server"
    is CoreException.InvalidCredentials -> "invalid_credentials"
    is CoreException.SeerrInvalidCredentials -> "seerr_invalid_credentials"
    is CoreException.SeerrSignInRefused -> "seerr_sign_in_refused"
    is CoreException.SeerrUnreachable -> "seerr_unreachable"
}

/**
 * Whether a failed core call means the active account's saved token is dead, so the app should
 * open re-authorization (as playback does) instead of failing open on a stale mirror. Seerr's own
 * 401 and the sign-in flows share the variant but aren't the Jellyfin session.
 */
internal fun routesToReauthorization(section: String, error: CoreException): Boolean =
    error is CoreException.Unauthorized &&
        !section.startsWith("ffi.seerr") &&
        !section.contains("QuickConnect", ignoreCase = true)
