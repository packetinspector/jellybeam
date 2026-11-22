package tv.jellybeam.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.player.TrackChoiceText
import uniffi.jellybeam_core.SubtitleModeSetting

/** The ISO 639-2/B codes the core normalizes, in display order. */
private val LANGUAGES = listOf(
    "eng", "spa", "fre", "ger", "ita", "por", "dut", "swe", "nor", "dan", "fin", "pol",
    "rus", "jpn", "kor", "chi", "ara", "heb", "hin", "tur", "cze", "gre",
)

/** Any (`null`) then [LANGUAGES]; a [selected] code outside it (hand-edited file) is appended so the choice stays visible. */
internal fun languageOptions(selected: String?): List<String?> =
    listOf<String?>(null) + LANGUAGES + listOfNotNull(selected?.takeIf { it !in LANGUAGES })

/** The Audio and Subtitle language rows: Any plus [languageOptions], names in the UI text language. */
@Composable
internal fun LanguageChipRow(
    label: String,
    description: String,
    key: String,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val any = stringResource(R.string.settings_language_any)
    PresetChipRow(
        label = label,
        description = description,
        key = key,
        options = remember(selected) { languageOptions(selected) },
        selected = selected,
        onSelect = onSelect,
        chipLabel = { code -> code?.let { TrackChoiceText.languageName(it, AppLocale.text) ?: it } ?: any },
        modifier = modifier,
        chipKey = { it ?: "any" },
    )
}

/** [SubtitleModeSetting]'s chip label. */
@Composable
internal fun subtitleModeChipLabel(mode: SubtitleModeSetting): String = when (mode) {
    SubtitleModeSetting.DEFAULT -> stringResource(R.string.settings_subtitle_mode_default)
    SubtitleModeSetting.ALWAYS -> stringResource(R.string.settings_subtitle_mode_always)
    SubtitleModeSetting.ONLY_FORCED -> stringResource(R.string.settings_subtitle_mode_only_forced)
    SubtitleModeSetting.NONE -> stringResource(R.string.settings_subtitle_mode_off)
}
