package tv.jellybeam.ui.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.cards.ArtSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val AmbientGround = Color(0xFF0B0908)
private val AMBIENT_ART = 200.dp
private val AMBIENT_TEXT_WIDTH = 280.dp
private val MASCOT = 150.dp
private val MASCOT_GAP = 90.dp

/** The group's resting place: its drift ellipse is centred here, so art, two-line text and mascot stay title-safe (27 dp). */
private val BASE_X = 430.dp
private val BASE_Y = 110.dp
private val DRIFT_RX = 120.dp
private val DRIFT_RY = 70.dp

/** docs/29 §12: one loop of the ≈617 dp ellipse in 20 min is ≈0.5 dp (1 px) a second. */
private const val DRIFT_LOOP_MS = 20 * 60_000L
private const val DRIFT_STEP_MS = 1_000L
private const val CROSSFADE_MS = 240

/** Brightness 0.82 on the art only: a black veil at 18% multiplies every channel by 0.82. */
private val ArtVeil = Color.Black.copy(alpha = 0.18f)

/** What one drifting group draws; a new track is a new face at its own start point. */
private data class AmbientFace(val itemId: String, val title: String, val artist: String, val art: ArtSource, val startPoint: Int)

/**
 * docs/29 §6 (M1/M2): art, title and artist drift on a slow ellipse and cross-fade to a new start point on
 * a track change; paused adds the Sleepy mascot beside the art and `PAUSED │ m:ss`. Keeps the screen on only
 * while it is composed, which is only while [Ambient.Drawing].
 */
@Composable
internal fun AmbientScreen(drawing: Ambient.Drawing, model: NowPlayingModel, pausedAtMs: Long, accountEpoch: ULong?) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val face = AmbientFace(model.itemId, model.title, model.artistLine, model.art, drawing.startPoint)
    val paused = drawing.pausedSince != null
    Box(Modifier.fillMaxSize().background(AmbientGround)) {
        Crossfade(targetState = face, animationSpec = tween(CROSSFADE_MS), label = "ambient") { f ->
            AmbientGroup(f, paused, pausedAtMs, accountEpoch)
        }
    }
}

/** docs/29 §6 M2 release: nothing animates and the screen is the system's again; any key returns. */
@Composable
internal fun AmbientReleased() {
    Box(Modifier.fillMaxSize().background(AmbientGround))
}

@Composable
private fun AmbientGroup(face: AmbientFace, paused: Boolean, pausedAtMs: Long, accountEpoch: ULong?) {
    // Steps once a second on the frame clock, so it stops whenever frames do (screen off, Activity stopped).
    val elapsed by produceState(0L, face) {
        val start = withFrameMillis { it }
        while (true) {
            delay(DRIFT_STEP_MS)
            value = withFrameMillis { it } - start
        }
    }
    Box(
        Modifier
            .padding(start = BASE_X, top = BASE_Y)
            .graphicsLayer {
                val (dx, dy) = driftOffset(face.startPoint, elapsed)
                translationX = dx * DRIFT_RX.toPx()
                translationY = dy * DRIFT_RY.toPx()
            },
    ) {
        Column(Modifier.width(AMBIENT_TEXT_WIDTH)) {
            Box(Modifier.size(AMBIENT_ART).clip(RoundedCornerShape(2.dp))) {
                SquareArt(face.art, rememberMusicImageUrl(AMBIENT_ART, accountEpoch), face.title, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(ArtVeil))
            }
            BasicText(
                face.title,
                Modifier.padding(top = 16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = archivo(JellybeamTheme.Panna.copy(alpha = 0.82f), 18.sp),
            )
            if (face.artist.isNotEmpty()) {
                BasicText(
                    face.artist,
                    Modifier.padding(top = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = archivo(JellybeamTheme.Panna.copy(alpha = 0.55f), 14.sp, androidx.compose.ui.text.font.FontWeight.Normal),
                )
            }
            if (paused) {
                BasicText(
                    stringResource(R.string.music_paused_at, formatTrackTime(pausedAtMs)).uppercaseUi(),
                    Modifier.padding(top = 14.dp),
                    maxLines = 1,
                    style = monoStyle(JellybeamTheme.Grigio, 11),
                )
            }
        }
        if (paused) {
            // docs/29 §6 M2: beside the art, never over it.
            Image(
                painterResource(R.drawable.jb_mascot_update_sleeping),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.offset(x = -(MASCOT + MASCOT_GAP), y = 40.dp).size(MASCOT),
            )
        }
    }
}

/** The group's place on the unit ellipse after [elapsedMs]; each [startPoint] begins a golden-ratio step further round. */
internal fun driftOffset(startPoint: Int, elapsedMs: Long): Pair<Float, Float> {
    val start = (startPoint * GOLDEN_FRACTION) % 1.0
    val angle = 2 * PI * (start + elapsedMs.toDouble() / DRIFT_LOOP_MS)
    return cos(angle).toFloat() to sin(angle).toFloat()
}

private const val GOLDEN_FRACTION = 0.6180339887
