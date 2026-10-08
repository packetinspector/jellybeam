package tv.jellybeam.nav

/**
 * Browser-style navigation over [Screen] (docs/07-home-browse-behavior.md §5). Plain Kotlin
 * (no Compose/Android imports) so it's JVM-testable; each function returns a new instance.
 *
 * - [push]: Home -> Library, card Enter -> Detail, Series/Season -> episode.
 * - [replace]: sibling-episode switch swaps the top entry so Back skips visited siblings.
 * - [pop]: remote/system Back.
 */
data class NavBackStack(val entries: List<Screen>) {
    init {
        require(entries.isNotEmpty()) { "NavBackStack must have at least one entry" }
    }

    val current: Screen get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    fun push(screen: Screen): NavBackStack {
        val appended = entries + screen
        if (appended.size <= MAX_RETAINED_ENTRIES) return NavBackStack(appended)
        // Root is the escape hatch; drop the oldest intermediate entry rather than retain another
        // full layer.
        return NavBackStack(listOf(appended.first()) + appended.drop(1).takeLast(MAX_RETAINED_ENTRIES - 1))
    }

    fun replace(screen: Screen): NavBackStack = NavBackStack(entries.dropLast(1) + screen)

    /** A no-op (returns `this`) at the stack root -- callers gate on [canGoBack] first anyway. */
    fun pop(): NavBackStack = if (canGoBack) NavBackStack(entries.dropLast(1)) else this

    /** Clears the whole history down to a single fresh entry (sign-in success, sign-out). */
    fun reset(screen: Screen): NavBackStack = NavBackStack(listOf(screen))

    companion object {
        const val MAX_RETAINED_ENTRIES = 8
        fun of(screen: Screen) = NavBackStack(listOf(screen))
    }
}

/**
 * Whether the retained Home layer (`MainActivity`'s `JellybeamRoot`) should be composed at all:
 * true for a Home-rooted stack regardless of [entries] depth (Home stays retained under every
 * push), false for a SignIn-rooted stack. Pulled out as an extension so it's JVM-testable.
 */
val NavBackStack.isHomeRooted: Boolean get() = entries.first() == Screen.Home

/** Whether Home is visible/focusable now, vs merely retained under a pushed screen. Only meaningful
 * when [isHomeRooted].
 */
val NavBackStack.isHomeVisible: Boolean get() = current == Screen.Home

/**
 * Stable per-entry composition identity, independent of [Screen]'s own data-class equality
 * (whose payload can change shape without changing which item is shown). Only the id matters
 * for Library/Detail; other variants are singletons on this stack, so a fixed label suffices.
 */
fun Screen.entryIdentity(): String = when (this) {
    Screen.SignIn -> "signin"
    Screen.Home -> "home"
    is Screen.Library -> "library-${view.id}"
    is Screen.Detail -> "detail-${card.id}"
    is Screen.Person -> "person-$personId"
    Screen.Search -> "search"
    Screen.Settings -> "settings"
    Screen.Discover -> "discover"
    is Screen.DiscoverGrid -> "discover-grid-$kind-${genreId ?: "all"}"
    is Screen.DiscoverDetail -> "discover-detail-$mediaType-$tmdbId"
    is Screen.DiscoverPerson -> "discover-person-$personId"
    Screen.DiscoverRequests -> "discover-requests"
    Screen.DiscoverSearch -> "discover-search"
    Screen.Report -> "report"
}

/**
 * The `key()` `MainActivity`'s `JellybeamRoot` wraps each retained per-entry layer in:
 * `"$index:${entryIdentity()}"`.
 *
 * - INDEX makes [push]/[pop] cheap: existing keys never change, so nothing below the
 *   pushed/popped entry recomposes or disposes.
 * - IDENTITY makes [replace] correct: same index, different [Screen] produces a different
 *   key, so Compose disposes the old subtree (ViewModel included) and mounts a fresh one.
 */
fun NavBackStack.entryKeys(): List<String> = entries.mapIndexed { index, screen -> screen.entryKey(index) }

/** One entry's [entryKeys] key, given its stack [index]. */
fun Screen.entryKey(index: Int): String = "$index:${entryIdentity()}"
