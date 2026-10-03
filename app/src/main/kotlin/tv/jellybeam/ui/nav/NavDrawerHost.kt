package tv.jellybeam.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.nav.Screen
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.ViewSnapshot

private val DRAWER_WIDTH = 272.dp
private val ENTRY_HEIGHT = 52.dp
private val ENTRY_GAP = 4.dp
private val SECTION_LABEL_TOP_PADDING = 16.dp

/**
 * One drawer row, resolved by the caller ([NavDrawerHost]'s `entries` builder): [onSelect] bakes
 * in navigate-vs-switch-server so the renderer never switches on the target, and [isCurrent]
 * drives the same Pistacchio dot for both a current [Screen] row and the active-account row.
 */
private data class DrawerEntry(val label: String, val isCurrent: Boolean, val onSelect: () -> Unit)

/** [DrawerEntry.isCurrent] for a [Screen]-targeted row; a pure function, JVM-testable without a
 * Compose runtime.
 */
fun matchesCurrentScreen(entryScreen: Screen, current: Screen): Boolean = when (entryScreen) {
    is Screen.Library -> current is Screen.Library && current.view.id == entryScreen.view.id
    else -> current == entryScreen
}

/** The Servers section's verbatim account label (never prettified): "user_name — server_url". */
fun accountDrawerLabel(account: AccountInfo): String = "${account.userName} — ${account.serverUrl}"

/** Whether selecting the Servers-section row for [index] should call `switchSession`; `false`
 * for the already-active row, since re-switching onto it would still force a pointless full app
 * reset (cleared ViewModelStores, a "Connecting…" flash, a redundant round trip).
 */
fun shouldSwitchServer(index: Int, activeAccountIndex: UInt?): Boolean = index.toUInt() != activeAccountIndex

/**
 * Optional hook for a screen with its own focus-memory machinery to run its own restore path on
 * drawer close instead of [NavDrawerHost]'s generic `contentFocusRequester`/`focusRestorer()`
 * default: [NavDrawerHost] provides one instance via [LocalDrawerFocusCoordinator], and a
 * participating screen overwrites [onDrawerOpened]/[onDrawerClosed] with its own logic (see
 * `HomeScreen`'s `SideEffect`). A screen that never reads it leaves both fields at their
 * no-op/`false` default, so [NavDrawerHost]'s generic restore keeps handling it.
 */
internal class DrawerFocusCoordinator {
    /**
     * The drawer just opened, before [DrawerPanel]'s initial-focus effect can steal focus onto a
     * drawer row. A screen freezes its focus-memory here so that stray grab can't corrupt the
     * record before the close-time restore reads it.
     */
    var onDrawerOpened: () -> Unit = {}

    /**
     * The drawer just closed. [navigatingAway] is false for Back/DPAD_RIGHT and an already-current
     * row (restore this content); a real navigation relies solely on the incoming screen's own
     * seed so an async retry can't steal focus back into the outgoing layer. Returns true when the
     * screen handled focus placement itself, so [NavDrawerHost] skips its fallback and the two
     * restores can't race.
     */
    var onDrawerClosed: (navigatingAway: Boolean) -> Boolean = { false }
}

/** See [DrawerFocusCoordinator]. `null` outside a drawer, where a screen never participates. */
internal val LocalDrawerFocusCoordinator = staticCompositionLocalOf<DrawerFocusCoordinator?> { null }

/**
 * docs/19-detail-action-menu.md §1.5: while the detail action panel is open, Left closes the panel
 * instead of opening the drawer. The panel is a focus trap, so `FocusManager.moveFocus` to the
 * left always fails inside it, which is the "true left edge" signal [NavDrawerHost] reads below.
 * A surface that owns Left sets this `true` while composed; `null` outside a host.
 */
internal val LocalDrawerLeftEdgeSuppressed = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/**
 * Wraps whichever screen's [content] is showing (every branch [tv.jellybeam.MainActivity] mounts
 * this around except Search) with a left-edge drawer: D-pad Left opens it once
 * `moveFocus(FocusDirection.Left)` fails inside [content] (a true left edge, not a guess);
 * DPAD_RIGHT or Back closes it and restores focus via `Modifier.focusRestorer()`; Enter on an
 * entry calls [onNavigate] and closes it. Not wrapped around [tv.jellybeam.ui.search.SearchScreen],
 * whose text field owns Left/Right for cursor movement; Search stays reachable as a destination.
 * [content] stays focusable while the drawer is open since disabling it would race
 * [focusRestorer]'s restore-on-request contract; open/close go through the key interception below
 * regardless. The closed drawer stays visible as the [MenuSpine] strip.
 */
@Composable
fun NavDrawerHost(
    currentScreen: Screen,
    libraries: List<ViewSnapshot>,
    onNavigate: (Screen) -> Unit,
    /** Whether this retained layer is visible; hidden layers cancel any pending content restore. */
    isTop: Boolean = true,
    /** Retained-layer entry gate, opened only around an explicit content restore. */
    focusGate: MutableState<Boolean>? = null,
    /** Every locally known account, verbatim; empty until [tv.jellybeam.MainActivity] loads them. */
    accounts: List<AccountInfo> = emptyList(),
    /** Index into [accounts] of the currently active session, or `null` before that's known. */
    activeAccountIndex: UInt? = null,
    /** Selecting a non-active Servers-section row; see [shouldSwitchServer]. */
    onSwitchServer: (UInt) -> Unit = {},
    /** Selecting the "Add server" row below the Servers section. */
    onAddServer: () -> Unit = {},
    /** Opens the destructive, confirmation-gated saved-server manager. */
    onManageServers: () -> Unit = {},
    /** docs/14-seerr-discover.md: Discover entry between libraries and Search, present only when
     * `seerr_status().configured`; re-evaluated on session swap and Settings connect/disconnect.
     */
    discoverConfigured: Boolean = false,
    /** Selecting the Discover row. */
    onOpenDiscover: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    val contentFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val leftEdgeSuppressed = remember { mutableStateOf(false) }
    val focusScope = rememberCoroutineScope()
    var contentRestoreJob by remember { mutableStateOf<Job?>(null) }
    // See [DrawerFocusCoordinator]. One instance per [NavDrawerHost] mount, provided to [content].
    val drawerFocusCoordinator = remember { DrawerFocusCoordinator() }

    LaunchedEffect(isTop) {
        if (!isTop) contentRestoreJob?.cancel()
    }

    val homeLabel = stringResource(R.string.drawer_home)
    val discoverLabel = stringResource(R.string.drawer_discover)
    val searchLabel = stringResource(R.string.search_chip)
    val updateAvailable by tv.jellybeam.AppGraph.updateAvailable.collectAsState()
    val settingsChip = stringResource(R.string.settings_chip)
    val settingsLabel = if (updateAvailable) stringResource(R.string.settings_chip_with_update, settingsChip) else settingsChip
    val serversSectionLabel = stringResource(R.string.drawer_servers_section)
    val addServerLabel = stringResource(R.string.drawer_add_server)
    val manageServersLabel = stringResource(R.string.drawer_manage_servers)

    // Independently-remembered lists so a library add/remove never rebuilds the Servers section's
    // [FocusRequester]s and vice versa; concatenated via its own `remember` for referential
    // stability.
    val navEntries = remember(libraries, currentScreen, homeLabel, discoverLabel, discoverConfigured, searchLabel, settingsLabel) {
        buildList {
            add(DrawerEntry(homeLabel, matchesCurrentScreen(Screen.Home, currentScreen)) { onNavigate(Screen.Home) })
            libraries.forEach { view ->
                add(DrawerEntry(view.name, matchesCurrentScreen(Screen.Library(view), currentScreen)) { onNavigate(Screen.Library(view)) })
            }
            // docs/14-seerr-discover.md: between libraries and Search, only when configured.
            if (discoverConfigured) {
                add(DrawerEntry(discoverLabel, matchesCurrentScreen(Screen.Discover, currentScreen), onOpenDiscover))
            }
            add(DrawerEntry(searchLabel, matchesCurrentScreen(Screen.Search, currentScreen)) { onNavigate(Screen.Search) })
            add(DrawerEntry(settingsLabel, matchesCurrentScreen(Screen.Settings, currentScreen)) { onNavigate(Screen.Settings) })
        }
    }
    val serverEntries = remember(accounts, activeAccountIndex, addServerLabel, manageServersLabel) {
        buildList {
            accounts.forEachIndexed { index, account ->
                add(
                    DrawerEntry(accountDrawerLabel(account), index.toUInt() == activeAccountIndex) {
                        if (shouldSwitchServer(index, activeAccountIndex)) onSwitchServer(index.toUInt())
                    },
                )
            }
            add(DrawerEntry(addServerLabel, isCurrent = false, onSelect = onAddServer))
            add(DrawerEntry(manageServersLabel, isCurrent = false, onSelect = onManageServers))
        }
    }
    val entries = remember(navEntries, serverEntries) { navEntries + serverEntries }

    fun closeDrawer(navigatingAway: Boolean) {
        isOpen = false
        val handledByContent = drawerFocusCoordinator.onDrawerClosed(navigatingAway)
        contentRestoreJob?.cancel()
        if (!navigatingAway && !handledByContent) {
            contentRestoreJob = focusScope.launch {
                requestFocusUntilSuccess(focusGate) { contentFocusRequester.requestFocus() }
            }
        }
    }

    // Registers later than MainActivity's back-stack-pop BackHandler, so it wins (LIFO): Back
    // closes the drawer instead of popping a screen.
    BackHandler(enabled = isOpen) { closeDrawer(navigatingAway = false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    !isOpen && event.key == Key.DirectionLeft -> {
                        // See [LocalDrawerLeftEdgeSuppressed]: the content owns Left right now.
                        if (leftEdgeSuppressed.value) return@onPreviewKeyEvent false
                        if (!focusManager.moveFocus(FocusDirection.Left)) {
                            contentRestoreJob?.cancel()
                            isOpen = true
                            // Called synchronously (not from a LaunchedEffect) so it runs before
                            // DrawerPanel's initial-focus effect can steal focus onto a drawer row.
                            drawerFocusCoordinator.onDrawerOpened()
                        }
                        true
                    }
                    isOpen && event.key == Key.DirectionRight -> {
                        closeDrawer(navigatingAway = false)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                .focusRestorer()
                .focusGroup(),
        ) {
            // Makes [drawerFocusCoordinator] reachable from [content]; unaffected if unused.
            CompositionLocalProvider(
                LocalDrawerFocusCoordinator provides drawerFocusCoordinator,
                LocalDrawerLeftEdgeSuppressed provides leftEdgeSuppressed,
            ) {
                content()
            }
        }

        val scrimAlpha = animateFloatAsState(
            if (isOpen) 1f else 0f,
            animationSpec = tween(if (isOpen) MenuSpine.OPEN_MS else MenuSpine.CLOSE_MS),
            label = "drawerScrim",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(JellybeamTheme.Notte.copy(alpha = 0.7f)),
        )

        DrawerPanel(
            entries = entries,
            serverSectionStart = navEntries.size,
            serversSectionLabel = serversSectionLabel,
            isOpen = isOpen,
            onSelect = { entry ->
                // Current rows are no-ops (restore like Back/Right); other rows navigate away.
                closeDrawer(navigatingAway = !entry.isCurrent)
                entry.onSelect()
            },
        )
    }
}

/**
 * The drawer panel and, closed, the [MenuSpine]: one box laid out at [DRAWER_WIDTH] whose drawn
 * width follows the open animation, so no layout changes in any state. The fill is the panel's
 * own [JellybeamTheme.SurfacePanel], 40% while closed and solid once open, and the divider a 1px
 * [JellybeamTheme.Hairline]; [isOpen] widens the strip to the full panel, fading the chevron out
 * first and the rows in last. [serverSectionStart]: index in [entries] where the Servers section begins;
 * [DrawerSectionLabel] renders once immediately before it, without a second, separately-indexed
 * list.
 */
@Composable
private fun DrawerPanel(
    entries: List<DrawerEntry>,
    serverSectionStart: Int,
    serversSectionLabel: String,
    isOpen: Boolean,
    onSelect: (DrawerEntry) -> Unit,
) {
    val open = animateFloatAsState(
        if (isOpen) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isOpen) MenuSpine.OPEN_MS else MenuSpine.CLOSE_MS,
            easing = if (isOpen) EaseOut else EaseIn,
        ),
        label = "drawerOpen",
    )
    val chevron = remember { Path() }

    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val entryFocusRequesters = remember(entries) { entries.map { FocusRequester() } }

    // Initial-focus target only, not what drives each row's dot. Two entries can be `isCurrent`
    // at once (the on-screen row and the active-account row are independent), so `indexOfFirst`
    // deliberately lands on the nav entry first: the drawer opens from an on-screen section.
    val currentIndex = remember(entries) { entries.indexOfFirst { it.isCurrent } }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            val target = currentIndex.takeIf { it >= 0 } ?: 0
            requestFocusUntilSuccess {
                entryFocusRequesters.getOrNull(target)?.requestFocus() ?: false
            }
        }
    }

    Box(
        modifier = Modifier
            .width(DRAWER_WIDTH)
            .fillMaxHeight()
            .drawWithContent {
                val p = open.value
                val spineWidth = MenuSpine.WIDTH.toPx()
                val panelWidth = lerp(spineWidth, size.width, p)
                // One device pixel, whatever the density: a 1dp line reads as a bar on a TV.
                val hairline = MenuSpine.HAIRLINE.toPx().coerceAtLeast(1f)

                drawRect(
                    color = JellybeamTheme.SurfacePanel.copy(alpha = lerp(MenuSpine.CLOSED_FILL_ALPHA, 1f, p)),
                    size = Size(panelWidth, size.height),
                )
                clipRect(right = panelWidth) { this@drawWithContent.drawContent() }
                drawRect(
                    color = JellybeamTheme.Hairline,
                    topLeft = Offset(panelWidth - hairline, 0f),
                    size = Size(hairline, size.height),
                )

                val chevronAlpha = spineChevronAlpha(p)
                if (chevronAlpha > 0f) {
                    // Never Pistacchio: accent means focus everywhere else, and this can't take it.
                    val box = MenuSpine.CHEVRON_SIZE.toPx()
                    val left = (spineWidth - box) / 2f
                    val top = (size.height - box) / 2f
                    chevron.reset()
                    chevron.moveTo(left + box * 0.625f, top + box * 0.25f)
                    chevron.lineTo(left + box * 0.375f, top + box * 0.5f)
                    chevron.lineTo(left + box * 0.625f, top + box * 0.75f)
                    drawPath(
                        path = chevron,
                        color = JellybeamTheme.Grigio,
                        alpha = chevronAlpha,
                        style = Stroke(width = MenuSpine.CHEVRON_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = drawerContentAlpha(open.value) }
                // Scrollable: the Servers section can push the row count past what the panel holds.
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ENTRY_GAP),
        ) {
            entries.forEachIndexed { index, entry ->
                if (index == serverSectionStart) {
                    DrawerSectionLabel(serversSectionLabel)
                }
                val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }
                DrawerRow(
                    label = entry.label,
                    isFocused = isFocused,
                    isCurrent = entry.isCurrent,
                    enabled = isOpen,
                    modifier = Modifier
                        .focusRequester(entryFocusRequesters[index])
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                            } else if (focusedIndex == index) {
                                focusedIndex = null
                            }
                        },
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

/** Servers section header, same Grigio/12sp recipe as sign-in field labels; not focusable. */
@Composable
private fun DrawerSectionLabel(text: String) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, top = SECTION_LABEL_TOP_PADDING, bottom = 4.dp),
        style = TextStyle(
            fontFamily = JellybeamTheme.Archivo,
            fontWeight = FontWeight.SemiBold,
            color = JellybeamTheme.Grigio,
            fontSize = 12.sp,
        ),
    )
}

/**
 * One drawer entry row, same [focusRing]/SURFACE_RAISED recipe as `SettingsScreen`'s `RowCard`.
 * A Pistacchio dot marks the [isCurrent] entry. `enabled` ties to the drawer's open/closed state
 * since `clickable`'s `enabled` also gates focusability, so closed rows drop out of focus search.
 */
@Composable
private fun DrawerRow(
    label: String,
    isFocused: Boolean,
    isCurrent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ENTRY_HEIGHT)
            .background(if (isFocused) JellybeamTheme.SurfaceRaised else Color.Transparent, RoundedCornerShape(8.dp))
            .focusRing(isFocused, cornerRadius = 8.dp)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (isCurrent) JellybeamTheme.Pistacchio else Color.Transparent, CircleShape),
        )
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2,
                fontSize = 16.sp,
            ),
        )
    }
}
