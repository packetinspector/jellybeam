package tv.jellybeam.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.focusKey

/** docs/15-focus-and-selection.md §5's shared focus-memory holder, threaded to every row/chip in
 * this package without a per-call-site parameter. Provided once in [SettingsScreen]; `null`
 * outside that provider skips [focusKey] registration.
 */
internal val LocalSettingsFocusMemory = compositionLocalOf<FocusMemory?> { null }

/** A row's namespace segment (docs/15-focus-and-selection.md §5 key scheme,
 * "<section>/<rowId>"), provided by [ChipFieldRow] so each [SettingsChip] only names its own
 * value (see [combineChipKey]). `null` outside a [ChipFieldRow] means pass the full key through.
 */
internal val LocalSettingsRowKey = compositionLocalOf<String?> { null }

/** docs/15-focus-and-selection.md §3 "Section switch inside Settings": the one row/chip that owns
 * initial focus when the pane's `focusProperties { enter }` fires. [key] is matched against each
 * row/chip's own key; a null [key] means the first row (or selected chip) to mount claims it, so
 * Right from the rail lands on the first row and never on Compose's spatially nearest one.
 * [attachedCount] keeps an unattached requester away from `enter`.
 */
internal class SettingsPaneEntryTarget(val key: String?, val requester: FocusRequester) {
    private var claimedKey: String? = null
    var attachedCount: Int = 0

    /** Whether [rowKey] carries [requester]; with a null [key] the first caller with [canClaim] does. */
    fun claims(rowKey: String, canClaim: Boolean): Boolean {
        if (key != null) return key == rowKey
        if (claimedKey == null && canClaim) claimedKey = rowKey
        return claimedKey == rowKey
    }
}

/** Attaches the pane entry [target]'s requester to this row/chip when it is the entry target,
 * tracking attachment so the pane never hands an unattached requester to the focus system.
 * [canClaim] gates only a first-mount claim (chips claim only when selected). */
@Composable
internal fun Modifier.paneEntry(target: SettingsPaneEntryTarget?, rowKey: String, canClaim: Boolean = true): Modifier {
    val entry = target?.takeIf { it.claims(rowKey, canClaim) }
    DisposableEffect(entry) {
        if (entry != null) entry.attachedCount++
        onDispose { if (entry != null) entry.attachedCount-- }
    }
    return if (entry != null) focusRequester(entry.requester) else this
}

internal val LocalSettingsPaneEntryKey = compositionLocalOf<SettingsPaneEntryTarget?> { null }

/** The retained layer's focus gate (see [tv.jellybeam.MainActivity]), for a section that must place
 * focus itself after the focused control left composition. */
internal val LocalSettingsFocusGate = compositionLocalOf<MutableState<Boolean>?> { null }

/** docs/15-focus-and-selection.md §2: D-pad entry into a [ChipFieldRow] lands on its selected
 * chip, not the spatially nearest one. [attachedCount] tracks whether any chip currently carries
 * [requester], so an unattached requester is never handed to the focus system.
 */
internal class SettingsSelectedChipTarget {
    val requester = FocusRequester()
    var attachedCount: Int = 0
}

internal val LocalSettingsSelectedChip = compositionLocalOf<SettingsSelectedChipTarget?> { null }

/** §5 key scheme for a chip inside a [ChipFieldRow]: the row's key (from [LocalSettingsRowKey],
 * `null` when none) plus this chip's value segment.
 */
internal fun combineChipKey(rowKey: String?, chipKey: String): String =
    if (rowKey != null) "$rowKey/$chipKey" else chipKey

// docs/07 §4/§6: SURFACE_RAISED fill, no scale, [focusRing] on focus. ROW_HEIGHT matches
// NavDrawerHost's ENTRY_HEIGHT so one-line rows share one rhythm.
internal val ROW_HEIGHT = 52.dp
internal val ROW_GAP = 4.dp
private val ROW_CORNER = 8.dp

// docs/15-focus-and-selection.md §6: ToggleRow/ChipFieldRow reserve a fixed second line for
// subtext via [SETTINGS_ROW_HEIGHT]/[SETTINGS_CHIP_ROW_HEIGHT] (`Modifier.height`), so row
// height is a property of kind, not content.
internal val SETTINGS_ROW_HEIGHT = 64.dp
internal val SETTINGS_CHIP_ROW_HEIGHT = 76.dp

/** docs/15-focus-and-selection.md §6: the description line's alpha fade, alpha only, no layout
 * change.
 */
internal const val SETTINGS_DESCRIPTION_FADE_MS = 120

/** Disabled-row dim, shared by [ToggleRow]'s `enabled` gate and `StillWatchingGroup`'s contextual
 * dim.
 */
internal const val SETTINGS_DISABLED_ALPHA = 0.45f

@Composable
internal fun settingsDescriptionStyle() = TextStyle(
    fontFamily = JellybeamTheme.Archivo,
    color = JellybeamTheme.Grigio,
    fontSize = 12.sp,
)

/** Whole-row focus/click target: a plain label row, or the shell every other row builds on.
 * [height] defaults to [ROW_HEIGHT] except [ToggleRow], which uses [SETTINGS_ROW_HEIGHT].
 */
@Composable
internal fun RowCard(
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = ROW_HEIGHT,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(JellybeamTheme.SurfaceRaised, RoundedCornerShape(ROW_CORNER))
            .focusRing(isFocused, cornerRadius = ROW_CORNER)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

// 16sp: matches NavDrawerHost's row-label size, and every other row label on this screen.
@Composable
internal fun rowLabelStyle() = TextStyle(
    fontFamily = JellybeamTheme.Archivo,
    fontWeight = FontWeight.Medium,
    color = JellybeamTheme.Panna,
    fontSize = 16.sp,
)

/** Group label -- same small-caps treatment as Home's hero eyebrow: uppercase + tight tracking, at
 * the app's quietest text tier.
 */
@Composable
internal fun SectionHeader(text: String) {
    BasicText(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = TextStyle(
            fontFamily = JellybeamTheme.Archivo,
            fontWeight = FontWeight.SemiBold,
            color = JellybeamTheme.Grigio,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        ),
    )
}

// -- Toggle switch -----------------------------------------------------------

private val TOGGLE_TRACK_WIDTH = 36.dp
private val TOGGLE_TRACK_HEIGHT = 20.dp
private val TOGGLE_KNOB_SIZE = 16.dp
private val TOGGLE_KNOB_PAD = 2.dp
private const val TOGGLE_ANIM_MS = 150

/** Track+thumb switch (36x20 track, 16dp knob, 2dp inset). Presentational only -- [ToggleRow]
 * owns the D-pad focus/click target, so this composable only renders state.
 */
@Composable
internal fun ToggleSwitch(isOn: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (isOn) 1f else 0f,
        animationSpec = tween(TOGGLE_ANIM_MS),
        label = "toggleTrackProgress",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (isOn) TOGGLE_TRACK_WIDTH - TOGGLE_KNOB_SIZE - TOGGLE_KNOB_PAD else TOGGLE_KNOB_PAD,
        animationSpec = tween(TOGGLE_ANIM_MS),
        label = "toggleKnobOffset",
    )
    val trackColor = lerp(JellybeamTheme.Hairline, JellybeamTheme.Pistacchio, progress)

    Box(
        modifier = modifier
            .size(TOGGLE_TRACK_WIDTH, TOGGLE_TRACK_HEIGHT)
            .background(trackColor, RoundedCornerShape(50)),
    ) {
        Box(
            modifier = Modifier
                // Lambda overload reads [knobOffset] at layout/draw time instead of recomposing.
                .offset { IntOffset(knobOffset.roundToPx(), TOGGLE_KNOB_PAD.roundToPx()) }
                .size(TOGGLE_KNOB_SIZE)
                .background(JellybeamTheme.Panna, CircleShape),
        )
    }
}

/**
 * A boolean row -- flips [value] on Enter/Select. [key] is the docs/15-focus-and-selection.md §5
 * stable key ("<section>/<rowId>"), registered via [focusKey]; when this row is the pane's entry
 * point (§3) it also carries [LocalSettingsPaneEntryKey]. [description] is §6's reserved second
 * line, visible only while focused. [enabled] = false dims to [SETTINGS_DISABLED_ALPHA] and
 * no-ops [onToggle], but the row stays focusable.
 */
@Composable
internal fun ToggleRow(
    label: String,
    description: String,
    value: Boolean,
    onToggle: () -> Unit,
    key: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val descriptionAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(SETTINGS_DESCRIPTION_FADE_MS),
        label = "toggleRowDescriptionAlpha",
    )
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current

    RowCard(
        isFocused = isFocused,
        height = SETTINGS_ROW_HEIGHT,
        modifier = modifier
            .let { if (!enabled) it.alpha(SETTINGS_DISABLED_ALPHA) else it }
            .let { if (memory != null) it.focusKey(memory, key) else it }
            .let { it.paneEntry(entryTarget, key) }
            .clickable(interactionSource = interactionSource, indication = null, onClick = { if (enabled) onToggle() }),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = rowLabelStyle(),
            )
            BasicText(
                text = description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = descriptionAlpha },
                style = settingsDescriptionStyle(),
            )
        }
        ToggleSwitch(isOn = value)
    }
}

/**
 * An action row -- [ToggleRow]'s shape without the switch, for a plain click target instead of a
 * boolean ("Report a problem"/"Clear log", docs/21-user-reporting.md §6). [enabled] = false dims
 * to [SETTINGS_DISABLED_ALPHA] and no-ops [onClick], but the row stays focusable.
 */
@Composable
internal fun ActionRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    key: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val descriptionAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(SETTINGS_DESCRIPTION_FADE_MS),
        label = "actionRowDescriptionAlpha",
    )
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current

    RowCard(
        isFocused = isFocused,
        height = SETTINGS_ROW_HEIGHT,
        modifier = modifier
            .let { if (!enabled) it.alpha(SETTINGS_DISABLED_ALPHA) else it }
            .let { if (memory != null) it.focusKey(memory, key) else it }
            .let { it.paneEntry(entryTarget, key) }
            .clickable(interactionSource = interactionSource, indication = null, onClick = { if (enabled) onClick() }),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = rowLabelStyle(),
            )
            BasicText(
                text = description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = descriptionAlpha },
                style = settingsDescriptionStyle(),
            )
        }
    }
}

// -- Chip button --------------------------------------------------------------

private val CHIP_HEIGHT = 32.dp
internal val CHIP_GAP = 8.dp
private val CHIP_HPADDING = 14.dp

/**
 * One segmented chip: filled Pistacchio/Notte-label when [selected], transparent/Hairline-border
 * otherwise. Individually focusable via its own [focusRing]. [key] combines with the enclosing
 * [ChipFieldRow]'s row key via [combineChipKey] (or is used as-is outside one) to form the full
 * docs/15-focus-and-selection.md §5 key; defaults to [label]. Also used outside this focus
 * scheme, where [LocalSettingsFocusMemory] is `null` and registration is skipped.
 */
@Composable
/** [focusRequester] lands on the chip's own focus target; a requester on [modifier] would sit on
 * the ring box, which is not focusable, and `requestFocus()` would return false. */
internal fun SettingsChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    key: String = label,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current
    val selectedChip = LocalSettingsSelectedChip.current
    val fullKey = combineChipKey(LocalSettingsRowKey.current, key)
    DisposableEffect(selected, selectedChip) {
        if (selected && selectedChip != null) selectedChip.attachedCount++
        onDispose { if (selected && selectedChip != null) selectedChip.attachedCount-- }
    }

    // Ring on a Pistacchio fill is Sheen (docs/15-focus-and-selection.md §1.2).
    Box(modifier = modifier.focusRing(isFocused, cornerRadius = CHIP_HEIGHT / 2, color = if (selected) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .let {
                    if (selected) {
                        it.background(JellybeamTheme.Pistacchio, RoundedCornerShape(50))
                    } else {
                        it.border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(50))
                    }
                }
                .let { if (memory != null) it.focusKey(memory, fullKey) else it }
                .let { it.paneEntry(entryTarget, fullKey, canClaim = selected) }
                .let { if (selected && selectedChip != null) it.focusRequester(selectedChip.requester) else it }
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
                .padding(horizontal = CHIP_HPADDING),
        ) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) JellybeamTheme.Notte else JellybeamTheme.Panna2,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}

// Fixed width, unlike [rowLabelStyle]'s usual weighted sibling: a `FlowRow` of chips needs a
// bounded max width to wrap correctly against the full row width.
private val CHIP_ROW_LABEL_WIDTH = 170.dp

/**
 * A chip-picker row: label in a fixed-width column, a `FlowRow` of [SettingsChip]s that wraps
 * rather than overflowing the pane; each chip is its own focus stop. [key] is this row's
 * docs/15-focus-and-selection.md §5 namespace segment, provided as [LocalSettingsRowKey] so each
 * chip builds its full key via [combineChipKey]. [description] visibility follows
 * `FocusState.hasFocus` on the chips column, not a single interaction source.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun ChipFieldRow(label: String, description: String, key: String, modifier: Modifier = Modifier, chips: @Composable () -> Unit) {
    var chipsHaveFocus by remember { mutableStateOf(false) }
    val descriptionAlpha by animateFloatAsState(
        targetValue = if (chipsHaveFocus) 1f else 0f,
        animationSpec = tween(SETTINGS_DESCRIPTION_FADE_MS),
        label = "chipFieldRowDescriptionAlpha",
    )

    // Label + chips on one line, description on a full-width line beneath (a 12sp sentence never
    // fits the 170dp label column). Row height is fixed; only description alpha moves with focus.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(SETTINGS_CHIP_ROW_HEIGHT)
            .background(JellybeamTheme.SurfaceRaised, RoundedCornerShape(ROW_CORNER))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(CHIP_ROW_LABEL_WIDTH),
                style = rowLabelStyle(),
            )
            val selectedChip = remember { SettingsSelectedChipTarget() }
            val memory = LocalSettingsFocusMemory.current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { chipsHaveFocus = it.hasFocus }
                    // §2 group entry: land on the selected chip, yielding while a restore is in
                    // flight.
                    .focusProperties {
                        enter = {
                            if (selectedChip.attachedCount > 0 && memory?.frozen != true) selectedChip.requester else FocusRequester.Default
                        }
                    }
                    .focusGroup(),
            ) {
                CompositionLocalProvider(
                    LocalSettingsRowKey provides key,
                    LocalSettingsSelectedChip provides selectedChip,
                    content = chips,
                )
            }
        }
        BasicText(
            text = description,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                // Clears the focused chip's outset ring.
                .padding(top = 8.dp)
                .graphicsLayer { alpha = descriptionAlpha },
            style = settingsDescriptionStyle(),
        )
    }
}

/** Quiet explanatory copy used below settings groups; it is never a focus target. */
@Composable
internal fun SettingsNoteRow(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
    )
}

// -- Section rail (190dp sidebar) ---------------------------------------------

internal val RAIL_WIDTH = 190.dp
// 44dp: eight rail rows must fit the 1080p/320dpi frame (540dp tall); 52dp overflowed once
// Troubleshooting made eight and Compose squeezed the last rows.
private val RAIL_ROW_HEIGHT = 44.dp

/**
 * One rail entry, same SURFACE_RAISED-on-focus + [focusRing] + Pistacchio dot recipe as
 * [tv.jellybeam.ui.nav.NavDrawerHost]'s `DrawerRow`. Section switching is driven by the
 * `Modifier.onFocusChanged` [SettingsScreen] attaches to this row (focus-follows-pane, the
 * standard TV-settings pattern) -- [onSelect] is just an idempotent fallback, since Select always
 * lands after the row is already focused.
 */
@Composable
internal fun SettingsRailRow(
    label: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RAIL_ROW_HEIGHT)
            .background(if (isFocused) JellybeamTheme.SurfaceRaised else Color.Transparent, RoundedCornerShape(ROW_CORNER))
            .focusRing(isFocused, cornerRadius = ROW_CORNER)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (isActive) JellybeamTheme.Pistacchio else Color.Transparent,
                    CircleShape,
                ),
        )
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2,
                fontSize = 16.sp,
            ),
        )
    }
}
