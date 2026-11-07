package tv.jellybeam.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.cards.ArtBox
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.focusRing
import uniffi.jellybeam_core.ImageKind

/** docs/29 §8.2: square art 128 dp. */
internal val SQUARE_ART = 128.dp

/** docs/29 §8.3: the artist circle, 112 dp. */
internal val ARTIST_CIRCLE = 112.dp

/** Server art for a slot; `null` when the item has none. */
internal typealias MusicImageUrl = (itemId: String, kind: ImageKind, tag: String) -> String?

/** PANNA at 0x40, the no-art mark (docs/29 §8: alpha 0x40, not 40%). */
private val NoArtInk = JellybeamTheme.Panna.copy(alpha = 0x40 / 255f)

/** PANNA at B3, the 70% secondary line. */
internal val PannaB3 = JellybeamTheme.Panna.copy(alpha = 0xB3 / 255f)

/** docs/29 §8.2/S4: SURFACE with a 1px HAIRLINE border and a centred note; nothing is generated from the name. */
@Composable
internal fun NoArtTile(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(2.dp)) {
    Box(
        modifier = modifier
            .background(JellybeamTheme.Surface, shape)
            .border(Dp.Hairline, JellybeamTheme.Hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize(0.3f)) { NoteGlyph(NoArtInk) }
    }
}

/** [source]'s art in a square slot, or the [NoArtTile] when there is none. */
@Composable
internal fun SquareArt(source: ArtSource, imageUrl: MusicImageUrl, name: String, modifier: Modifier = Modifier) {
    if (source == ArtSource.None) {
        NoArtTile(modifier)
    } else {
        CardArtImage(source = source, imageUrl = imageUrl, itemName = name, contentAlpha = 1f, aspect = 1f, modifier = modifier)
    }
}

/**
 * docs/29 §8.2: square art with the shared focus scale, lift and ring; title 2 lines, subline 1 line.
 * [modifier] goes before the focus target, so a caller's `focusKey` lands on it; Search draws a smaller [size] (K1).
 */
@Composable
internal fun SquareCard(
    title: String,
    subline: String?,
    art: ArtSource,
    imageUrl: MusicImageUrl,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SQUARE_ART,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .width(size)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArtBox(width = size, height = size, isFocused = focused) {
            SquareArt(art, imageUrl, title, Modifier.fillMaxSize())
        }
        Column {
            BasicText(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = JellybeamTheme.Panna, fontSize = 14.sp),
            )
            if (subline != null) {
                BasicText(
                    text = subline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = PannaB3, fontSize = 13.sp),
                )
            }
        }
    }
}

/**
 * docs/29 §8.3: provider Primary cropped to a circle, else a SURFACE circle with the [artistInitial]
 * of [sortName] (fallback [name]) at 38% of the diameter. Name 2 lines centred, then a mono [subline]; Search fits
 * [size] to its grid cell (K2).
 */
@Composable
internal fun ArtistCircle(
    name: String,
    sortName: String?,
    art: ArtSource,
    subline: String?,
    imageUrl: MusicImageUrl,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ARTIST_CIRCLE,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (focused) 1.04f else 1f,
        tween(if (focused) 180 else 240, easing = JellybeamEasing),
        label = "artistScale",
    )
    Column(
        modifier = modifier
            .width(size)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .focusRing(focused, cornerRadius = size / 2, color = LocalAccent.current.accentInk)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (art == ArtSource.None) {
                InitialCircle(artistInitial(sortName, name), size)
            } else {
                CardArtImage(source = art, imageUrl = imageUrl, itemName = name, contentAlpha = 1f, aspect = 1f, modifier = Modifier.fillMaxSize())
            }
        }
        BasicText(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Medium,
                color = JellybeamTheme.Panna,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
        if (subline != null) {
            BasicText(
                text = subline.uppercaseUi(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = monoStyle(JellybeamTheme.Grigio, 10),
            )
        }
    }
}

@Composable
private fun InitialCircle(initial: String, diameter: Dp) {
    val fontSize = with(LocalDensity.current) { (diameter * 0.38f).toSp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Surface, CircleShape)
            .border(Dp.Hairline, JellybeamTheme.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = initial.uppercaseUi(),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.ExtraBold, color = NoArtInk, fontSize = fontSize),
        )
    }
}

/** Martian Mono at [sizeSp], the house -0.02em tracking. */
internal fun monoStyle(color: Color, sizeSp: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = JellybeamTheme.MartianMono, fontWeight = weight, color = color, fontSize = sizeSp.sp, letterSpacing = (-0.02).em)
