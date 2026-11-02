package tv.jellybeam.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.AccentScheme
import tv.jellybeam.AccentSchemes
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.accentSchemeFor
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.theme.JellybeamHeaderLockup

/** Swatch columns per row (docs/09): lights fill the first row, each deep sits under its light partner. */
internal const val ACCENT_COLUMNS = 6

internal enum class AccentDir { Left, Right, Up, Down }

/** Where a D-pad press from a swatch goes: another swatch, nowhere (an edge consumes it), or back to the rail. */
internal sealed interface AccentStep {
    data class To(val index: Int) : AccentStep
    data object Stay : AccentStep
    data object Rail : AccentStep
}

/** The grid's one navigation rule: no wrap, columns kept on Up/Down, Left from the first column leaves to the rail. */
internal fun accentStep(index: Int, dir: AccentDir, count: Int = AccentSchemes.size): AccentStep {
    val col = index % ACCENT_COLUMNS
    val lastRow = (count - 1) / ACCENT_COLUMNS
    return when (dir) {
        AccentDir.Left -> if (col == 0) AccentStep.Rail else AccentStep.To(index - 1)
        AccentDir.Right -> if (col == ACCENT_COLUMNS - 1 || index + 1 >= count) AccentStep.Stay else AccentStep.To(index + 1)
        AccentDir.Up -> if (index < ACCENT_COLUMNS) AccentStep.Stay else AccentStep.To(index - ACCENT_COLUMNS)
        AccentDir.Down -> if (index / ACCENT_COLUMNS >= lastRow) AccentStep.Stay else AccentStep.To(index + ACCENT_COLUMNS)
    }
}

/** The preview card, rail dot and "Use" hint follow the focused swatch; with none focused they show what is in use. */
internal fun accentPreviewScheme(focused: AccentScheme?, inUse: AccentScheme): AccentScheme = focused ?: inUse

private fun stepRequester(step: AccentStep, swatches: List<FocusRequester>, rail: FocusRequester?): FocusRequester = when (step) {
    is AccentStep.To -> swatches[step.index]
    AccentStep.Stay -> FocusRequester.Cancel
    AccentStep.Rail -> rail ?: FocusRequester.Default
}

private val GRID_SHAPE = RoundedCornerShape(8.dp)
private val SWATCH_SHAPE = RoundedCornerShape(7.dp)

/**
 * Appearance section (docs/09): the accent picker. [focusedScheme] is hoisted to [SettingsScreen] so the rail dot can
 * follow it; browsing never writes, only [onApply] does.
 */
@Composable
internal fun AppearanceSectionContent(
    storedKey: String?,
    focusedScheme: AccentScheme?,
    onFocusedScheme: (AccentScheme?) -> Unit,
    onApply: (String) -> Unit,
) {
    val inUse = accentSchemeFor(storedKey)
    val shown = accentPreviewScheme(focusedScheme, inUse)
    val entry = LocalSettingsPaneEntryKey.current
    val rail = LocalSettingsRailEntry.current
    val swatches = remember { List(AccentSchemes.size) { FocusRequester() } }
    val onFocusedSchemeNow by rememberUpdatedState(onFocusedScheme)
    var gridFocused by remember { mutableStateOf(false) }

    // Leaving the section any way ends a preview the rail dot would otherwise keep showing.
    DisposableEffect(Unit) { onDispose { onFocusedSchemeNow(null) } }
    // Back without OK abandons the preview by sending focus to the rail, which clears it (no write).
    BackHandler(enabled = gridFocused && rail != null) { rail?.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.settings_appearance_accent_label))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    gridFocused = state.hasFocus
                    if (!state.hasFocus) onFocusedSchemeNow(null)
                }
                .background(JellybeamTheme.Surface, GRID_SHAPE)
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            for (row in 0 until AccentSchemes.size / ACCENT_COLUMNS) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(48.dp).height(SWATCH_HEIGHT), contentAlignment = Alignment.CenterStart) {
                        SectionHeader(
                            stringResource(if (row == 0) R.string.settings_appearance_light else R.string.settings_appearance_deep),
                            Modifier,
                        )
                    }
                    for (col in 0 until ACCENT_COLUMNS) {
                        val index = row * ACCENT_COLUMNS + col
                        val scheme = AccentSchemes[index]
                        AccentSwatch(
                            scheme = scheme,
                            inUse = scheme.key == inUse.key,
                            onFocused = { onFocusedSchemeNow(scheme) },
                            onApply = { onApply(scheme.key) },
                            modifier = Modifier
                                .weight(1f)
                                .paneEntry(entry, "appearance/${scheme.key}", canClaim = scheme.key == inUse.key, requester = swatches[index])
                                .focusProperties {
                                    left = stepRequester(accentStep(index, AccentDir.Left), swatches, rail)
                                    right = stepRequester(accentStep(index, AccentDir.Right), swatches, rail)
                                    up = stepRequester(accentStep(index, AccentDir.Up), swatches, rail)
                                    down = stepRequester(accentStep(index, AccentDir.Down), swatches, rail)
                                },
                        )
                    }
                }
            }
        }

        CompositionLocalProvider(LocalAccent provides shown) { AccentPreviewCard() }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
            KeyHint(stringResource(R.string.settings_appearance_hint_ok), stringResource(R.string.settings_appearance_hint_use, shown.name))
            KeyHint(stringResource(R.string.settings_appearance_hint_back), stringResource(R.string.settings_appearance_hint_keep, inUse.name))
        }
    }
}

private val SWATCH_HEIGHT = 56.dp

@Composable
private fun AccentSwatch(
    scheme: AccentScheme,
    inUse: Boolean,
    onFocused: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .height(SWATCH_HEIGHT)
            // The ring is the swatch's own sheen, not the app's accent; the 2dp gap shows the card behind.
            .focusRing(focused, cornerRadius = 7.dp, color = scheme.accentSheen, width = 2.dp, gap = 2.dp)
            .background(scheme.accent, SWATCH_SHAPE)
            .clickable(indication = null, interactionSource = null, onClick = onApply)
            .padding(start = 7.dp, end = 7.dp, bottom = 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText(
            text = scheme.name,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                color = scheme.onAccent,
                fontSize = 10.5.sp,
            ),
        )
        if (inUse) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 5.dp).size(14.dp)) {
                drawCircle(scheme.onAccent)
                val check = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.34f)
                }
                drawPath(check, scheme.accent, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Static sample of the accent in use (logo, fills, primary pill, thin bar); [LocalAccent] is overridden around it. */
@Composable
private fun AccentPreviewCard() {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(JellybeamTheme.Surface, GRID_SHAPE)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_appearance_preview), Modifier)
        // The real Home header (docs/brand.md §6.1) on its own ground, so the sample cannot drift from it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Notte, RoundedCornerShape(5.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            JellybeamHeaderLockup()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).background(accent.accent, RoundedCornerShape(5.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.settings_appearance_sample_title),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = accent.onAccent, fontSize = 13.sp),
                )
                BasicText(
                    text = stringResource(R.string.settings_appearance_sample_count).uppercaseUi(),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = accent.onAccent, fontSize = 9.sp),
                )
            }
            // Always drawn as focused: the sample is not interactive and shows the ring in this accent's sheen.
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .focusRing(true, cornerRadius = 50.dp, color = accent.accentSheen, width = 2.dp, gap = 2.dp)
                    .background(accent.accent, RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                BasicText(
                    text = stringResource(R.string.settings_appearance_sample_start),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = accent.onAccent, fontSize = 14.sp),
                )
            }
            // Thin bars use ink (docs/brand.md), not the fill colour.
            Box(Modifier.width(100.dp).height(4.dp).background(JellybeamTheme.Hairline, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(0.62f).height(4.dp).background(accent.accentInk, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun KeyHint(key: String, action: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(key, style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 11.sp))
        BasicText(action, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp))
    }
}
