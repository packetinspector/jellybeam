package tv.jellybeam.nav

/**
 * Browser-style navigation over [Screen] (docs/07-home-browse-behavior.md §5). Plain Kotlin
 * (no Compose/Android imports) so it's JVM-testable; each function returns a new instance.
 *
 * - [push]: Home -> Library, card Enter -> Detail, Series/Season -> episode.
 * - [replace]: sibling-episode switch swaps the top entry so Back skips visited siblings.
 * - [pop]: remote/system Back.
 *
 * Each entry carries an [ids] value from a counter that only grows, so a surviving entry keeps its
 * composition key through a push, eviction or pop and a fresh entry never reuses a popped one's.
 * Equality is the [entries] alone: the ids are identity, not content.
 */
class NavBackStack private constructor(
    val entries: List<Screen>,
    /** Parallel to [entries]; assigned at push (docs/07 §5), kept by [replace] and eviction. */
    val ids: List<Int>,
    private val nextId: Int,
) {
    constructor(entries: List<Screen>) : this(entries, entries.indices.toList(), entries.size)

    init {
        require(entries.isNotEmpty()) { "NavBackStack must have at least one entry" }
        require(entries.size == ids.size) { "one id per entry" }
    }

    val current: Screen get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    fun push(screen: Screen): NavBackStack {
        val appended = entries + screen
        val appendedIds = ids + nextId
        if (appended.size <= MAX_RETAINED_ENTRIES) return NavBackStack(appended, appendedIds, nextId + 1)
        // Root is the escape hatch; drop the oldest intermediate entry rather than retain another
        // full layer.
        val keep = MAX_RETAINED_ENTRIES - 1
        return NavBackStack(
            listOf(appended.first()) + appended.drop(1).takeLast(keep),
            listOf(appendedIds.first()) + appendedIds.drop(1).takeLast(keep),
            nextId + 1,
        )
    }

    fun replace(screen: Screen): NavBackStack = NavBackStack(entries.dropLast(1) + screen, ids, nextId)

    /** A no-op (returns `this`) at the stack root -- callers gate on [canGoBack] first anyway. */
    fun pop(): NavBackStack = if (canGoBack) NavBackStack(entries.dropLast(1), ids.dropLast(1), nextId) else this

    /** Pops only when the entry with [id] is the top; a screen that left or sits under another is left alone (docs/07 §5). */
    fun popIfTop(id: Int): NavBackStack = if (ids.last() == id) pop() else this

    /** Drops every entry above [index] and puts [screen] there, keeping that entry's id. */
    fun backTo(index: Int, screen: Screen = entries[index]): NavBackStack =
        NavBackStack(entries.take(index) + screen, ids.take(index + 1), nextId)

    /** Clears the whole history down to a single fresh entry (sign-in success, sign-out). */
    fun reset(screen: Screen): NavBackStack = NavBackStack(listOf(screen), listOf(nextId), nextId + 1)

    override fun equals(other: Any?): Boolean = other is NavBackStack && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "NavBackStack(entries=$entries)"

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
    // docs/28 §1: one fixed identity, so a newly held title keeps the lobby's composition and view model.
    is Screen.WatchTogether -> "watch-together"
}

/**
 * The `key()` `MainActivity`'s `JellybeamRoot` wraps each retained per-entry layer in:
 * `"$id:${entryIdentity()}"`.
 *
 * - The ID (from [NavBackStack.ids]) makes [NavBackStack.push]/[NavBackStack.pop] cheap and
 *   eviction safe: a surviving entry's key never changes, so nothing below it recomposes or
 *   disposes.
 * - IDENTITY makes [NavBackStack.replace] correct: same id, different [Screen] produces a different
 *   key, so Compose disposes the old subtree (ViewModel included) and mounts a fresh one.
 */
fun NavBackStack.entryKeys(): List<String> = entries.mapIndexed { index, screen -> screen.entryKey(ids[index]) }

/** One entry's [entryKeys] key, given its stack entry [id]. */
fun Screen.entryKey(id: Int): String = "$id:${entryIdentity()}"

/**
 * A drawer pick's stack: Home resets to the root; anything else pushes one level above Home, or
 * replaces the top when already off Home, so hopping between libraries never piles up. The join
 * row returns to a lobby already on the stack (docs/28 §1).
 */
fun NavBackStack.forDrawerPick(screen: Screen): NavBackStack = when {
    screen == Screen.Home -> NavBackStack.of(Screen.Home)
    screen is Screen.WatchTogether -> toWatchTogether(screen) { aboveHome(screen) }
    else -> aboveHome(screen)
}

private fun NavBackStack.aboveHome(screen: Screen): NavBackStack = if (current == Screen.Home) push(screen) else replace(screen)

/**
 * docs/28 §1: the stack's one watch-together entry. Reaching it again pops back to it, taking a newly chosen
 * title when [screen] holds one, so the lobby and its group survive; without one, [open] decides the stack.
 */
fun NavBackStack.toWatchTogether(screen: Screen.WatchTogether, open: NavBackStack.() -> NavBackStack): NavBackStack {
    val lobby = entries.indexOfLast { it is Screen.WatchTogether }
    if (lobby < 0) return open()
    return if (screen.itemId == null) backTo(lobby) else backTo(lobby, screen)
}
