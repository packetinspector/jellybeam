package tv.jellybeam

import androidx.annotation.DrawableRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Single source of truth for Jellybeam's palette and type ramp on Android: no raw color literals or font
 * lookups elsewhere in the Compose UI.
 */
@Immutable
object JellybeamTheme {

    // -- Palette -----------------------------------------------------

    /** Background. */
    val Notte = Color(0xFF14100D)

    /** Elevated surface, one step up from [Notte]. */
    val Surface = Color(0xFF1D1814)

    /** Thin dividers/borders. */
    val Hairline = Color(0xFF322A22)

    /** Primary text/foreground. */
    val Panna = Color(0xFFF7E9CE)

    /** Secondary text/foreground. */
    val Panna2 = Color(0xFFC9C0B2)

    /** Tertiary/muted text. */
    val Grigio = Color(0xFF8C8478)

    /** The default accent and the launcher's and non-base poses' fixed colour; every other site reads [LocalAccent] (docs/brand.md). */
    val Pistacchio = Color(0xFFA8CB6B)

    /** A lighter highlight, paired with [Pistacchio]. */
    val Sheen = Color(0xFFC6DE9B)

    /** OSD `TRANSCODE` reading (docs/18 §1/§3) -- the palette's one amber accent. */
    val Ambra = Color(0xFFE0B45C)

    val UpdateError = Color(0xFFEB7C64)

    /** One step up from [Surface] -- its own token so call sites name the role, not the value. */
    val SurfaceRaised = Surface

    /** One step above [SurfaceRaised] -- floating panels and the no-art placeholder tile. */
    val SurfacePanel = Color(0xFF261F19)

    /** [Panna2] at 60% opacity -- a resume card's series-name line. */
    val SeriesNameDim = Panna2.copy(alpha = 0.6f)

    /** Season chip tank fill and border for a partly-present season, unselected (docs/11 §12). */
    val SeasonSomeFilled = Color(0xFF5C5248)

    /** Season chip's dashed outline with no episode on the server, unselected (docs/11 §12). */
    val SeasonNoneDash = Color(0xFF5A5046)

    /** Season chip label with no episode on the server (docs/11 §12). */
    val SeasonNoneLabel = Color(0xFF7A7166)

    /** Lighter-than-[Hairline] border; the hero secondary button's outline. */
    val HairlineStrong = Color(0xFF4A3E33)

    /** The watch-together lobby's ground and scrim colour, a step below [Notte] (docs/28 §1). */
    val LobbyGround = Color(0xFF0B0908)

    /** The faint rule between rows of a list on [LobbyGround]. */
    val RuleFaint = Color(0xFF241E19)

    /** [Panna] at 45% opacity -- eyebrow/label text over artwork, where [Grigio] is invisible. */
    val PannaTertiary = Panna.copy(alpha = 0.45f)

    /** The watch-together key-hint line on [LobbyGround], dimmer than [Grigio]. */
    val GrigioDim = Color(0xFF6E665B)

    // -- Type ramp -----------------------------------------------------

    /** Display/wordmark face. */
    val BagelFatOne = FontFamily(
        Font(R.font.bagel_fat_one_regular, FontWeight.Normal),
    )

    /** Body/UI face. */
    val Archivo = FontFamily(
        Font(R.font.archivo_400, FontWeight.Normal),
        Font(R.font.archivo_500, FontWeight.Medium),
        Font(R.font.archivo_600, FontWeight.SemiBold),
        Font(R.font.archivo_700, FontWeight.Bold),
        Font(R.font.archivo_800, FontWeight.ExtraBold),
    )

    /** Monospace face, used for technical/diagnostic strings. */
    val MartianMono = FontFamily(
        Font(R.font.martian_mono_400, FontWeight.Normal),
        Font(R.font.martian_mono_700, FontWeight.Bold),
    )

    /** Every face declared above, by [R.font] id -- [tv.jellybeam.JellybeamApp]'s IO warm-up walks
     * this list rather than duplicating the id set there. */
    val fontResourceIds: List<Int> = listOf(
        R.font.bagel_fat_one_regular,
        R.font.archivo_400,
        R.font.archivo_500,
        R.font.archivo_600,
        R.font.archivo_700,
        R.font.archivo_800,
        R.font.martian_mono_400,
        R.font.martian_mono_700,
    )
}

/**
 * One selectable accent (docs/brand.md). [deep] flavours fill with a dark [accent] and so take Panna text;
 * on a dark ground they use [accentSheen] for ink. [name] is the flavour's own name, shown verbatim.
 */
@Immutable
data class AccentScheme(
    val key: String,
    val name: String,
    val deep: Boolean,
    val accent: Color,
    val accentSheen: Color,
    val onAccent: Color,
    /** This flavour's base-pose mascot (docs/brand.md §5); other poses stay Pistacchio. */
    @param:DrawableRes val mascot: Int,
    /** Season chip's unfilled tank when a partly-present season is selected (docs/11 §12); [onAccent] stays readable on it. */
    val accentTankEmpty: Color =
        if (deep) lerp(accent, JellybeamTheme.Notte, 0.5f) else lerp(accentSheen, JellybeamTheme.Panna, 0.5f),
    /** Secondary text on an [accent] fill; stays at 4.5:1 or better. */
    val onAccentMuted: Color = lerp(onAccent, accent, if (deep) 0.05f else 0.2f),
) {
    /** Text, marks and thin bars on a dark ground: the colour that keeps 4.5:1 there. */
    val accentInk: Color get() = if (deep) accentSheen else accent

    /** [accentInk] at 30% alpha -- the watch-progress bar's track. */
    val progressTrack: Color get() = accentInk.copy(alpha = 0.3f)
}

private fun lightAccent(key: String, name: String, accent: Long, sheen: Long, @DrawableRes mascot: Int) =
    AccentScheme(key, name, deep = false, Color(accent), Color(sheen), JellybeamTheme.Notte, mascot)

private fun deepAccent(key: String, name: String, accent: Long, sheen: Long, @DrawableRes mascot: Int) =
    AccentScheme(key, name, deep = true, Color(accent), Color(sheen), JellybeamTheme.Panna, mascot)

private val PistacchioScheme = AccentScheme(
    "pistacchio", "Pistacchio", deep = false, JellybeamTheme.Pistacchio, JellybeamTheme.Sheen, JellybeamTheme.Notte,
    R.drawable.jb_mascot_base_pistacchio,
    accentTankEmpty = Color(0xFFE4EDD0),
    onAccentMuted = Color(0xFF2E3A18),
)

/** Every flavour in picker order; the first is the default. */
val AccentSchemes: List<AccentScheme> = listOf(
    PistacchioScheme,
    lightAccent("menta", "Menta", 0xFF7FCFAE, 0xFFAEE3CB, R.drawable.jb_mascot_base_menta),
    lightAccent("puffo", "Puffo", 0xFF86C3E3, 0xFFB5DBEF, R.drawable.jb_mascot_base_puffo),
    lightAccent("mirtillo", "Mirtillo", 0xFFA6B4EE, 0xFFC9D1F6, R.drawable.jb_mascot_base_mirtillo),
    lightAccent("lavanda", "Lavanda", 0xFFC6A8E6, 0xFFDDCAF1, R.drawable.jb_mascot_base_lavanda),
    lightAccent("fragola", "Fragola", 0xFFEEA0B4, 0xFFF5C5D1, R.drawable.jb_mascot_base_fragola),
    deepAccent("bosco", "Bosco", 0xFF4E6A2C, 0xFFA8CB6B, R.drawable.jb_mascot_base_bosco),
    deepAccent("pino", "Pino", 0xFF2C6B58, 0xFF7FCFAE, R.drawable.jb_mascot_base_pino),
    deepAccent("oceano", "Oceano", 0xFF2B5F82, 0xFF86C3E3, R.drawable.jb_mascot_base_oceano),
    deepAccent("mirtillo_scuro", "Mirtillo scuro", 0xFF4C4F94, 0xFFA6B4EE, R.drawable.jb_mascot_base_mirtillo_scuro),
    deepAccent("prugna", "Prugna", 0xFF6E3F7C, 0xFFC6A8E6, R.drawable.jb_mascot_base_prugna),
    deepAccent("amarena", "Amarena", 0xFF8C2F48, 0xFFEEA0B4, R.drawable.jb_mascot_base_amarena),
)

/** The scheme for a stored key; `null` or an unknown key is the default (docs/09). */
fun accentSchemeFor(key: String?): AccentScheme = AccentSchemes.firstOrNull { it.key == key } ?: PistacchioScheme

/** Static: the accent changes only when the user confirms one, so reads carry no tracking cost. */
val LocalAccent = staticCompositionLocalOf { PistacchioScheme }

/** Provides [LocalAccent] from the saved setting; an unrelated settings write re-runs only this body, not [content]. */
@Composable
fun ProvideAccent(content: @Composable () -> Unit) {
    val settings by AppGraph.settingsStore.settings.collectAsState()
    val scheme = accentSchemeFor(settings?.accentColor)
    CompositionLocalProvider(LocalAccent provides scheme, content = content)
}
