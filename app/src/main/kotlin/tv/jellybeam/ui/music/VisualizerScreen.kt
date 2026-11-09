package tv.jellybeam.ui.music

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import tv.jellybeam.visualizer.TapFeatureSource
import tv.jellybeam.visualizer.gl.VisualizerView
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternCycle

/** docs/30 §7 (D10): whether this device can draw the visualizer (GLES 3.0). */
internal fun visualizerSupported(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return visualizerSupported(am.deviceConfigurationInfo.reqGlEsVersion)
}

/**
 * docs/30 §7: the full-screen visualizer over Now Playing. Holds focus and ambient off while open;
 * Left/Right switch and remember the pattern, Up/Down change track, Centre plays or pauses, Back calls
 * [onClose]; media and page keys pass through. A key hint shows briefly on each open. Keeps the screen on only while music plays, like the content it is (D2).
 */
@Composable
internal fun VisualizerScreen(
    owner: MusicPlaybackOwner,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onClose: () -> Unit,
) {
    BlocksAmbient()
    val start = remember { PatternCycle.fromKey(AppGraph.settingsStore.settings.value?.visualizerPattern) }
    val handle = remember { VisualizerHandle() }
    var toast by remember { mutableStateOf<Pattern?>(null) }
    var toastSerial by remember { mutableIntStateOf(0) }
    // docs/30 §7: the key hint shows once per open and yields to the first pattern name.
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MS)
        hint = false
    }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    val focusGate = LocalFocusGate.current
    LaunchedEffect(Unit) { requestFocusUntilSuccess(focusGate) { focus.requestFocus() } }
    // docs/30 §7: Back through the dispatcher, so predictive back (API 33+) closes the overlay, not Now Playing.
    BackHandler(onBack = onClose)
    LaunchedEffect(toastSerial) {
        if (toastSerial == 0) return@LaunchedEffect
        delay(TOAST_MS)
        toast = null
    }
    // docs/30 §5: the engine owns the pattern; every change is shown, and only a Left/Right step is remembered.
    fun changed(p: Pattern, manual: Boolean) {
        hint = false
        toast = p
        toastSerial++
        if (!manual) return
        // Undispatched: the store publishes before suspending, so a Back right after still keeps the pattern.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { AppGraph.settingsStore.update { it.copy(visualizerPattern = p.key) } }
    }
    Box(
        Modifier
            .fillMaxSize()
            // docs/30 §4: the visualizer surface is true black, not NOTTE.
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                val action = visualizerKey(e.key.nativeKeyCode)
                if (action == VisualizerKey.PASS) return@onKeyEvent false
                // Act on the first down, like the other overlays; repeats and the up are consumed.
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    when (action) {
                        VisualizerKey.PREVIOUS -> handle.view?.step(-1)
                        VisualizerKey.NEXT -> handle.view?.step(1)
                        VisualizerKey.NEXT_TRACK -> onNextTrack()
                        VisualizerKey.PREVIOUS_TRACK -> onPreviousTrack()
                        VisualizerKey.PLAY_PAUSE -> onPlayPause()
                        VisualizerKey.CLOSE -> onClose()
                        VisualizerKey.PASS -> Unit
                    }
                }
                true
            }
            .focusable(),
    ) {
        VisualizerSurface(owner, start, handle, ::changed)
        val line = toast?.let { stringResource(patternName(it)) } ?: if (hint) stringResource(R.string.music_visualizer_hint) else null
        line?.let {
            BasicText(
                it.uppercaseUi(),
                Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 48.dp),
                style = monoStyle(JellybeamTheme.Grigio, 13),
            )
        }
    }
}

/**
 * docs/30 §6–7: the [VisualizerView] for [owner]'s tap in [pattern] and the user's accent, plus D10's
 * still-fallback line when the audio is encoded. The engine exists only while this is composed.
 */
@Composable
internal fun VisualizerSurface(
    owner: MusicPlaybackOwner,
    start: Pattern,
    handle: VisualizerHandle?,
    onPatternChanged: (pattern: Pattern, manual: Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    val palettes = remember(accent) { visualizerPalettes(accent) }
    val settings by AppGraph.settingsStore.settings.collectAsState()
    val autoChange = settings?.visualizerAutoChange ?: true
    var encoded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                VisualizerView(ctx, { TapFeatureSource(owner.tap) }, start, palettes, { encoded = it }, onPatternChanged).also { handle?.view = it }
            },
            onRelease = { if (handle?.view === it) handle.view = null },
            update = {
                it.setPalettes(palettes)
                it.setAutoChange(autoChange)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (encoded) {
            BasicText(
                stringResource(R.string.music_visualizer_needs_decoded),
                Modifier.align(Alignment.Center).padding(top = 160.dp),
                style = archivo(JellybeamTheme.Panna2, 14.sp, FontWeight.Normal),
            )
        }
    }
}

/** docs/30 §7: the visualizer as the ambient screen; the controller (docs/29 §6) still owns when it shows. */
@Composable
internal fun AmbientVisualizer(owner: MusicPlaybackOwner, paused: Boolean, pausedAtMs: Long) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val start = remember { PatternCycle.fromKey(AppGraph.settingsStore.settings.value?.visualizerPattern) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Ambient takes no keys, so it needs no handle; automatic changes are shown by the picture alone.
        VisualizerSurface(owner, start, handle = null) { _, _ -> }
        if (paused) {
            BasicText(
                stringResource(R.string.music_paused_at, formatTrackTime(pausedAtMs)).uppercaseUi(),
                Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 48.dp),
                style = monoStyle(JellybeamTheme.Grigio, 11),
            )
        }
    }
}

/** The live view, so Left/Right reach the engine at once instead of waiting for a recomposition. */
internal class VisualizerHandle {
    var view: VisualizerView? = null
}

private const val TOAST_MS = 1_500L
private const val HINT_MS = 2_000L
