package tv.jellybeam.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.R

/**
 * Header lockup for every top-level screen (docs/brand.md §6.1): `jb_mascot_base` 35dp tall,
 * pulled 3dp left and overhanging the row 7dp above and 6dp below so the row height is unchanged,
 * a 6dp gap, then the wordmark at 20sp without rays.
 */
@Composable
fun JellybeamHeaderLockup(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_base),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.overhang(height = 35.dp, top = 7.dp, bottom = 6.dp, pullLeft = 3.dp),
        )
        JellybeamWordmark(size = 20.sp)
    }
}

/** Measures the content [height] tall but occupies `height - top - bottom` by `width - pullLeft`. */
private fun Modifier.overhang(height: Dp, top: Dp, bottom: Dp, pullLeft: Dp): Modifier = layout { measurable, _ ->
    val h = height.roundToPx()
    val placeable = measurable.measure(Constraints(minHeight = h, maxHeight = h))
    val pull = pullLeft.roundToPx()
    layout(width = (placeable.width - pull).coerceAtLeast(0), height = (h - top.roundToPx() - bottom.roundToPx()).coerceAtLeast(0)) {
        placeable.place(-pull, -top.roundToPx())
    }
}
