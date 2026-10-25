package tv.jellybeam

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
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

    /** The one accent color in the palette. Use sparingly. */
    val Pistacchio = Color(0xFFA8CB6B)

    /** A lighter accent highlight, paired with [Pistacchio]. */
    val Sheen = Color(0xFFC6DE9B)

    /** OSD `TRANSCODE` reading (docs/18 §1/§3) -- the palette's one amber accent. */
    val Ambra = Color(0xFFE0B45C)

    val UpdateError = Color(0xFFEB7C64)

    /** One step up from [Surface] -- its own token so call sites name the role, not the value. */
    val SurfaceRaised = Surface

    /** One step above [SurfaceRaised] -- floating panels and the no-art placeholder tile. */
    val SurfacePanel = Color(0xFF261F19)

    /** [Pistacchio] at 30% alpha -- the watch-progress bar's track. */
    val ProgressTrack = Pistacchio.copy(alpha = 0.3f)

    /** [Panna2] at 60% opacity -- a resume card's series-name line. */
    val SeriesNameDim = Panna2.copy(alpha = 0.6f)

    /** Season chip tank fill and border for a partly-present season, unselected (docs/11 §12). */
    val SeasonSomeFilled = Color(0xFF5C5248)

    /** Season chip's unfilled tank portion when that partly-present season is selected (docs/11 §12). */
    val SeasonSomeSelectedEmpty = Color(0xFFE4EDD0)

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
