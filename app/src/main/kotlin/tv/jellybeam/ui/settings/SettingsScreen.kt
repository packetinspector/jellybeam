package tv.jellybeam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory

private val PAGE_MARGIN = 32.dp
private val RAIL_PANE_GAP = 24.dp

// A focused last row's ring bottom edge would otherwise clip at the viewport bottom: container
// bounds, not the ring's outset, are the hard scroll limit. Applied after `.verticalScroll`
// (same 24dp token as [tv.jellybeam.ui.nav.NavDrawerHost]) so it's part of scrollable content, top
// and bottom for the symmetric case.
private val PANE_CONTENT_VERTICAL_PADDING = 24.dp

// About's pane spans the page height, so this is also the brand mark's distance from the screen top.
private val ABOUT_PANE_VERTICAL_PADDING = 22.dp

/** docs/15-focus-and-selection.md §5's rail-row key: "rail:<Section.name>". Pure -- shared by
 * [SettingsScreen]'s focus/selectedKey/fallback wiring so all three can't drift apart. */
internal fun railFocusKey(section: SettingsSection): String = "rail:${section.name}"

/** §5's key namespaces: "rail:<name>" for the rail, "<section>/<rowId>" for the pane -- so "a
 * pane key" is simply "not a rail key". */
internal fun isPaneKey(key: String?): Boolean = key != null && !key.startsWith("rail:")

/**
 * §3 "Section switch inside Settings": [SettingsSection.name] -> that section's last focused
 * pane key, flattened to a `List<String>` for [listSaver] since `SnapshotStateMap` isn't itself
 * `rememberSaveable`-able.
 */
internal fun flattenPaneLastKey(map: Map<String, String>): List<String> =
    map.entries.flatMap { (section, key) -> listOf(section, key) }

internal fun unflattenPaneLastKey(flat: List<String>): Map<String, String> =
    buildMap { for (i in flat.indices step 2) put(flat[i], flat[i + 1]) }

private val PaneLastKeySaver: Saver<SnapshotStateMap<String, String>, Any> = listSaver<SnapshotStateMap<String, String>, String>(
    save = { flattenPaneLastKey(it) },
    restore = { flat -> mutableStateMapOf(*unflattenPaneLastKey(flat).toList().toTypedArray()) },
)

/** [SettingsSection] is an enum, saved across a configuration change by its stable [Enum.name]. */
private val SettingsSectionSaver: Saver<SettingsSection, String> = Saver(
    save = { it.name },
    restore = { SettingsSection.valueOf(it) },
)

/**
 * The Settings screen (docs/09-settings-plan.md slice 2): a left rail of section names + right
 * content pane, widened to a full TV screen rather than a floating dialog since this app has no
 * modal-sheet chrome elsewhere. A rail matches Android TV's own system Settings shape: Left/Right
 * moves between rail and pane via Compose's ordinary spatial focus search.
 *
 * Rail rows ([SettingsRailRow]) select their section as soon as the row gains D-pad focus (the
 * standard TV-settings pattern, not focus+Select), guarded by `activeSection != section` so a
 * refocus of the already-active row is a no-op and can't disturb the pane's focus/scroll state.
 * Toggle/chip rows write through immediately -- no save button.
 *
 * Focus machinery: [isTop]/[focusGate] are [tv.jellybeam.MainActivity]'s retained-screen contract.
 * Restore goes through [tv.jellybeam.ui.focus.FocusRestorer]: [selectedKey] is the active rail row
 * ([railFocusKey]), [fallback] its [FocusRequester], so a first mount lands on Home row 0 and a
 * later becoming-top restores the last-focused pane row/chip (§2 rule 1) before falling back to
 * the active rail row (§2 rules 2-3).
 *
 * Section switch (§3): the pane subtree is disposed/recomposed on every `activeSection` change,
 * so Compose's own `focusRestorer()` can't help. [paneLastKey] is this screen's per-section
 * memory (section name -> last focused pane key), consulted by the pane's `focusProperties
 * { enter }` (via [SettingsPaneEntryTarget]/[LocalSettingsPaneEntryKey]) so Right from the rail
 * into a visited section lands where the viewer left it, first row otherwise.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- closed while hidden/transitioning; opened before
     * each explicit focus placement. See [tv.jellybeam.MainActivity.RetainedScreenLayer].
     */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    /** docs/14-seerr-discover.md: [tv.jellybeam.MainActivity]'s `seerrEpoch` bump, so the drawer's
     * Discover entry appears/disappears without a restart. No-op default for other call sites.
     */
    onSeerrConfigChanged: () -> Unit = {},
    /** docs/21-user-reporting.md §1.2: pushes [tv.jellybeam.nav.Screen.Report]. No-op default for
     * other call sites (e.g. focus/section tests). */
    onReportProblem: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    val sections = remember { settingsSectionOrder() }
    var activeSection by rememberSaveable(stateSaver = SettingsSectionSaver) { mutableStateOf(sections.first()) }
    val railFocusRequesters = remember(sections) { sections.associateWith { FocusRequester() } }
    val memory = rememberFocusMemory()

    // §3's per-section pane memory: which pane row/chip key was last focused in each section, so a
    // return via the rail (not a becoming-top restore, that's [memory]'s job) can re-enter there.
    val paneLastKey = rememberSaveable(saver = PaneLastKeySaver) { mutableStateMapOf() }
    val paneEntryRequester = remember { FocusRequester() }

    // Keyed on [memory], not [activeSection], so it observes every focus move for this screen's
    // lifetime. Only a pane key, never a rail key, is recorded (see [isPaneKey]).
    LaunchedEffect(memory) {
        snapshotFlow { memory.lastKey }.collect { key ->
            key?.takeIf { isPaneKey(it) }?.let { paneKey ->
                paneLastKey[activeSection.name] = paneKey
            }
        }
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = !state.isLoading,
        selectedKey = { railFocusKey(activeSection) },
        fallback = { railFocusRequesters.getValue(activeSection).asFocusTarget() },
        tag = "settings",
    )

    Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        // Blank Notte background only, matching MainActivity's between-screens convention -- state
        // reloads on entry, so there's a real (if brief) window with nothing to render yet.
        if (state.isLoading) return@Box

        CompositionLocalProvider(LocalSettingsFocusMemory provides memory) {
            // One pane, two placements: beside the rail, or (About) over the full page height.
            val pane: @Composable (Modifier, Dp) -> Unit = { paneModifier, contentPadding ->
                // §3 pane entry point: `enter` hands focus to [paneEntryRequester], carried by
                // this section's recorded last-focused row/chip or else by the first row (never
                // the spatially nearest); a section switch disposes the pane and its claim.
                val entryKey = paneLastKey[activeSection.name]
                val paneEntry = remember(activeSection, entryKey) { SettingsPaneEntryTarget(entryKey, paneEntryRequester) }
                Column(
                    modifier = paneModifier
                        .focusProperties {
                            enter = { if (paneEntry.attachedCount > 0) paneEntryRequester else FocusRequester.Default }
                            // Up/Down never leave the pane (past the last or first row the
                            // D-pad stops), and Left returns to the active section's rail row
                            // rather than the spatially nearest one, which would switch sections.
                            exit = { direction ->
                                when (direction) {
                                    FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                                    FocusDirection.Left -> railFocusRequesters.getValue(activeSection)
                                    else -> FocusRequester.Default
                                }
                            }
                        }
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = contentPadding),
                ) {
                    CompositionLocalProvider(
                        LocalSettingsPaneEntryKey provides paneEntry,
                        LocalSettingsFocusGate provides focusGate,
                    ) {
                        when (activeSection) {
                            SettingsSection.HOME -> HomeSectionContent(state, viewModel)
                            SettingsSection.LIBRARY -> LibrarySectionContent(state, viewModel)
                            SettingsSection.PLAYBACK -> PlaybackSectionContent(state, viewModel)
                            SettingsSection.OSD -> OsdSectionContent(state, viewModel)
                            SettingsSection.SUBTITLES -> SubtitlesSectionContent(state, viewModel)
                            SettingsSection.DISCOVER -> DiscoverSectionContent(onSeerrConfigChanged = onSeerrConfigChanged)
                            SettingsSection.TROUBLESHOOTING -> TroubleshootingSectionContent(state, viewModel, onReportProblem)
                            SettingsSection.ABOUT -> AboutSectionContent()
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                BasicText(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = PAGE_MARGIN, bottom = 16.dp),
                    style = TextStyle(
                        fontFamily = JellybeamTheme.Archivo,
                        fontWeight = FontWeight.Bold,
                        color = JellybeamTheme.Panna,
                        // 28sp: same TEXT_TITLE tier LibraryScreen's header uses.
                        fontSize = 28.sp,
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = PAGE_MARGIN, end = PAGE_MARGIN, bottom = PAGE_MARGIN),
                ) {
                    Column(
                        modifier = Modifier
                            .width(RAIL_WIDTH)
                            .fillMaxHeight()
                            .background(JellybeamTheme.Surface, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                    ) {
                        sections.forEach { section ->
                            SettingsRailRow(
                                label = stringResource(section.labelRes),
                                isActive = section == activeSection,
                                onSelect = { activeSection = section },
                                modifier = Modifier
                                    // §5: registers this row under "rail:<Section.name>" so
                                    // [FocusRestorer] rule 1 can find the exact row left on.
                                    .focusKey(memory, railFocusKey(section))
                                    .focusRequester(railFocusRequesters.getValue(section))
                                    // Switch pane on focus arrival, not focus+Select (see class
                                    // doc). Guarded so refocusing the active row is a no-op.
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused && activeSection != section) {
                                            activeSection = section
                                        }
                                    },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(RAIL_PANE_GAP))

                    if (activeSection != SettingsSection.ABOUT) {
                        pane(Modifier.weight(1f).fillMaxHeight(), PANE_CONTENT_VERTICAL_PADDING)
                    }
                }
            }

            // About's brand header rises into the title band beside "Settings" so its cards fit
            // one screen; every other pane starts level with the rail.
            if (activeSection == SettingsSection.ABOUT) {
                pane(
                    Modifier
                        .fillMaxSize()
                        .padding(start = PAGE_MARGIN + RAIL_WIDTH + RAIL_PANE_GAP, end = PAGE_MARGIN, bottom = PAGE_MARGIN),
                    ABOUT_PANE_VERTICAL_PADDING,
                )
            }
        }
    }
}
