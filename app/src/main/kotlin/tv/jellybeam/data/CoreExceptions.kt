package tv.jellybeam.data

import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrRefusal

/**
 * A message fit to show a user in the sign-in error card. `Api`/`Cache` carry the underlying
 * error's `Display` text verbatim in `detail` (not `message`, which `CoreException` overrides).
 */
fun CoreException.displayMessage(): String = when (this) {
    is CoreException.NotSignedIn -> "Not signed in."
    is CoreException.MirrorNotOpen -> "Library isn't ready yet."
    is CoreException.Unauthorized -> "Authorization expired."
    is CoreException.Api -> detail
    is CoreException.Cache -> detail
    // Direct Play is default, transcoding opt-in (CLAUDE.md); prepare_playback refuses rather
    // than transcode silently (docs/18-playback-quality.md §1/§3).
    is CoreException.WouldTranscode ->
        "Direct Play isn't possible for this file on this TV: $reasons. " +
            "Set Quality to Auto in Settings › Playback to let the server transcode it."
    // Only reachable via a stale drawer row racing a concurrent account removal.
    is CoreException.InvalidSessionIndex -> "That server is no longer available."
    // docs/14-seerr-discover.md: a seerr_* call ran before the account had a saved Seerr
    // connection; routes Discover's error toward Settings.
    is CoreException.SeerrNotConfigured -> "Discover isn't set up yet. Connect it from Settings."
    // docs/18-playback-quality.md §2 staleness guard; PlaybackViewModel catches this before
    // displayMessage() -- kept here only for exhaustiveness.
    is CoreException.StalePlaybackSession -> "Playback session is no longer current."
    // docs/13-feature-list.md "sign-in": plain-English copy for signIn/reauthorizeSession/
    // quick-connect failures, no jargon or URLs of the user's own.
    is CoreException.InvalidServerAddress ->
        "That doesn't look like a server address. Enter it the way your browser shows it, like http://192.0.2.10:8096."
    is CoreException.ServerUnreachable ->
        "Couldn't reach $host ($reason). Check the address and port, and that the server is running."
    is CoreException.HttpsNotOffered -> "$host isn't answering over HTTPS. Try the same address with http:// instead."
    is CoreException.NotJellyfinServer ->
        "$host answered, but it isn't a Jellyfin server (HTTP $status). Check the port; Jellyfin's default is 8096."
    is CoreException.InvalidCredentials -> "Wrong username or password."
    // docs/14-seerr-discover.md: the Seerr server was reached and refused the sign-in; a Seerr
    // local account signs in with its email address, which the field label also says.
    is CoreException.SeerrInvalidCredentials -> when (method) {
        SeerrAuthMethod.LOCAL -> "Wrong email or password. A Seerr account signs in with its email address."
        SeerrAuthMethod.JELLYFIN -> "Wrong username or password."
        SeerrAuthMethod.API_KEY -> "The server rejected that API key."
    }
    // docs/14-seerr-discover.md "Auth": Seerr answered with its own error, so the address is
    // right and the fix is on the Seerr side; say which.
    is CoreException.SeerrSignInRefused -> when (reason) {
        SeerrRefusal.MEDIA_SERVER_SIGN_IN ->
            "Seerr couldn't sign in to its own Jellyfin server, so it can't check this account. " +
                "Jellyfin 12 needs Seerr 3.0 or newer; otherwise check Seerr's Jellyfin settings."
        SeerrRefusal.NEW_USERS_BLOCKED ->
            "Seerr doesn't have this Jellyfin user yet and new sign-ins are turned off there. " +
                "Sign in to Seerr in a browser once, or allow new Jellyfin sign-ins in Seerr."
        SeerrRefusal.METHOD_DISABLED -> "Seerr has this sign-in method turned off. Try another method."
        SeerrRefusal.OTHER -> "Seerr refused the sign-in" + if (detail.isBlank()) "." else ": $detail"
    }
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
