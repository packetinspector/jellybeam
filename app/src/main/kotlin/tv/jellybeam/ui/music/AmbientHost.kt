package tv.jellybeam.ui.music

import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import tv.jellybeam.AppForeground
import tv.jellybeam.AppGraph
import tv.jellybeam.MainActivity
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.player.music.MusicPlaybackOwner

/** docs/29 §6: how many open dialogs, menus and panels hold ambient off; written only by [BlocksAmbient]. */
internal class AmbientBlocks {
    var open by mutableIntStateOf(0)
}

/** docs/29 §6: the root's [AmbientBlocks]; the default only serves a composable shown outside the root. */
internal val LocalAmbientBlock = staticCompositionLocalOf { AmbientBlocks() }

/** docs/29 §6: holds ambient off while composed, so call it only while the dialog, menu or panel is open. */
@Composable
internal fun BlocksAmbient() {
    val blocks = LocalAmbientBlock.current
    DisposableEffect(blocks) {
        blocks.open++
        onDispose { blocks.open-- }
    }
}

/**
 * docs/29 §6: ambient over every screen while [owner] holds a session. It never takes focus or navigates: keys
 * reach it first through [MainActivity.ambientKeys], so closing it leaves the screen and its focus as they were.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmbientHost(owner: MusicPlaybackOwner) {
    val scope = rememberCoroutineScope()
    val controller = remember(owner, scope) { AmbientController(owner, scope) }
    val mayCount = ambientMayCount(AppForeground.browsing.value, LocalAmbientBlock.current.open, WindowInsets.isImeVisible)
    LaunchedEffect(controller, mayCount) { controller.setMayCount(mayCount) }
    val activity = LocalActivity.current as? MainActivity
    DisposableEffect(activity, controller) {
        if (activity == null) return@DisposableEffect onDispose {}
        val swallowed = mutableSetOf<Int>()
        activity.ambientKeys = { event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> ambientKeyConsumed(down = true, event.keyCode, swallowed, controller::key)
                KeyEvent.ACTION_UP -> ambientKeyConsumed(down = false, event.keyCode, swallowed, controller::key)
                else -> false
            }
        }
        onDispose { activity.ambientKeys = null }
    }
    val ambient by controller.state.collectAsState()
    when (val a = ambient) {
        is Ambient.Drawing -> AmbientDrawing(owner, a)
        Ambient.Released -> AmbientReleased()
        is Ambient.Off -> Unit
    }
}

/** Reads the player only while drawing, so its position ticks never recompose the host (docs/29 §7). */
@Composable
private fun AmbientDrawing(owner: MusicPlaybackOwner, drawing: Ambient.Drawing) {
    val flow by owner.state.collectAsState()
    val now by owner.now.collectAsState()
    val view = remember(flow, now) { musicQueueView(flow, now) } ?: return
    val strings = rememberUiStrings()
    val model = remember(view, strings) { nowPlayingModel(view, owner::card, strings) }
    // docs/30 §7: the style only changes what draws; the controller still decides when.
    val settings by AppGraph.settingsStore.settings.collectAsState()
    val context = LocalContext.current
    val supported = remember { visualizerSupported(context) }
    if (ambientDrawsVisualizer(ambientStyleFor(settings?.ambientStyle), supported)) {
        AmbientVisualizer(owner, paused = drawing.pausedSince != null, pausedAtMs = model.positionMs)
    } else {
        AmbientScreen(drawing, model, pausedAtMs = model.positionMs, accountEpoch = flow.session?.epoch)
    }
}
