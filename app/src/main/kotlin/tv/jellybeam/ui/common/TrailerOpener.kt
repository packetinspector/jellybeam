package tv.jellybeam.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import java.net.URI
import java.net.URISyntaxException

/** An activity that can handle a trailer intent. */
data class TrailerHandler(val packageName: String, val className: String)

/** The two URIs a trailer can be opened with, tried in this order. */
sealed interface TrailerCandidate {
    val uri: String

    data class Vnd(val key: String) : TrailerCandidate {
        override val uri: String get() = "vnd.youtube:$key"
    }

    data class Https(override val uri: String) : TrailerCandidate
}

sealed interface TrailerLaunch {
    /** Start [handler] directly: the only real app for [uri], so no picker. */
    data class Explicit(val uri: String, val handler: TrailerHandler) : TrailerLaunch

    /** Several real apps claim [uri]: a picker without the [exclude] entries (stubs, or non-YouTube apps). */
    data class Chooser(val uri: String, val exclude: List<TrailerHandler>) : TrailerLaunch

    data object Unavailable : TrailerLaunch
}

private const val STUBS_PACKAGE = "com.android.tv.frameworkpackagestubs"
private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
private val WEB_SCHEMES = setOf("http", "https")
private val KEY_REGEX = Regex("[A-Za-z0-9_-]{6,}")

/** The video key of a youtube watch, short, embed or shorts link; `null` for any other host or shape. */
fun youtubeKey(url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
    val host = uri.host?.lowercase() ?: return null
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val key = when {
        host == "youtu.be" -> segments.singleOrNull()
        host !in YOUTUBE_HOSTS -> null
        segments == listOf("watch") -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        segments.size == 2 && (segments[0] == "embed" || segments[0] == "shorts") -> segments[1]
        else -> null
    }
    return key?.takeIf { KEY_REGEX.matches(it) }
}

/**
 * docs/14: the canonical https link goes to an installed YouTube app first, else to a real https handler; stub
 * and own activities never count as handlers, so a dead stub can't turn the click into a picker.
 */
fun decideTrailerLaunch(url: String, ownPackage: String, handlers: (TrailerCandidate) -> List<TrailerHandler>): TrailerLaunch {
    fun excluded(h: TrailerHandler) = h.packageName == STUBS_PACKAGE || h.packageName == ownPackage

    val key = youtubeKey(url)
    // `vnd.youtube:` only finds the YouTube apps; they get the https link, since SmartTube can't open the vnd form.
    val target = key?.let { "https://www.youtube.com/watch?v=$it" } ?: url.trim()
    if (key != null) {
        val youtubeApps = handlers(TrailerCandidate.Vnd(key)).filterNot(::excluded)
        when (youtubeApps.size) {
            0 -> Unit
            1 -> return TrailerLaunch.Explicit(target, youtubeApps.single())
            else -> return TrailerLaunch.Chooser(target, handlers(TrailerCandidate.Https(target)).filterNot { it in youtubeApps })
        }
    }
    if (!target.startsWith("https://", ignoreCase = true)) return TrailerLaunch.Unavailable
    val https = TrailerCandidate.Https(target)
    val all = handlers(https)
    val real = all.filterNot(::excluded)
    return when (real.size) {
        0 -> TrailerLaunch.Unavailable
        1 -> TrailerLaunch.Explicit(https.uri, real.single())
        else -> TrailerLaunch.Chooser(https.uri, all.filter(::excluded))
    }
}

private fun launchFor(context: Context, url: String): TrailerLaunch =
    decideTrailerLaunch(url, context.packageName) { queryHandlers(context.packageManager, it.uri) }

/** docs/19 §1.3: whether [openTrailer] would find an app for [url]; launches nothing. */
fun canOpenTrailer(context: Context, url: String): Boolean = launchFor(context, url) != TrailerLaunch.Unavailable

/** Opens [url] per [decideTrailerLaunch]; `false` when nothing can play it. */
fun openTrailer(context: Context, url: String): Boolean {
    val intent = when (val launch = launchFor(context, url)) {
        is TrailerLaunch.Explicit -> viewIntent(launch.uri).setClassName(launch.handler.packageName, launch.handler.className)
        is TrailerLaunch.Chooser -> Intent.createChooser(viewIntent(launch.uri), null).also {
            // EXTRA_EXCLUDE_COMPONENTS exists from API 24; older chooser still lists the stub.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                it.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, launch.exclude.map { h -> ComponentName(h.packageName, h.className) }.toTypedArray())
            }
        }
        TrailerLaunch.Unavailable -> return false
    }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, uri.toUri())

// MATCH_DEFAULT_ONLY mirrors what startActivity resolves, so the list is what the picker would have shown.
private fun queryHandlers(pm: PackageManager, uri: String): List<TrailerHandler> {
    val query = viewIntent(uri)
    val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return infos.mapNotNull { info -> info.activityInfo?.let { TrailerHandler(it.packageName, it.name) } }
}
