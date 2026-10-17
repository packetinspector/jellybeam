package tv.jellybeam.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * docs/15-focus-and-selection.md §3's one owner of a screen's refresh-restore work. A trigger still
 * waiting in [onContentChanged]'s frame when ownership is lost is stopped by `eligible`, which must
 * read the *latest* ownership inputs (`rememberUpdatedState` for composable parameters, the live
 * lifecycle state, the drawer flag or token) once the frame has passed. A screen under
 * [tv.jellybeam.ui.nav.NavDrawerHost] must wire [tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator]
 * to call [ownershipLost], or a live refresh can steal focus from a drawer holding it outside the
 * root. Thread confinement: plain vars, touched only from the composition/main thread.
 */
internal class RefreshRestoreOwner {
    private var job: Job? = null

    /** Placement tokens this owner's running restore holds (0 or 1), for [FocusMemory.frozenBesides]. */
    val ownTokens: Int get() = if (job?.isActive == true) 1 else 0

    /** An ownership transition (lost top, ON_PAUSE, drawer/panel opened): cancels the running
     * restore, whose token is released with it. */
    fun ownershipLost() {
        job?.cancel()
        job = null
    }

    /**
     * Body of a screen's content-change refresh-restore trigger effect. [frame] is a one-frame
     * settle (`{ withFrameNanos {} }` in production) letting Compose apply new content -- and
     * clear focus, if it's going to -- before [eligible] reads whether anything still holds focus.
     * [eligible] receives [ownTokens] so a refresh supersedes its own running predecessor.
     */
    suspend fun onContentChanged(
        scope: CoroutineScope,
        frame: suspend () -> Unit,
        eligible: (ownTokens: Int) -> Boolean,
        restore: suspend () -> Unit,
    ) {
        frame()
        if (!eligible(ownTokens)) return
        job?.cancel() // latest refresh wins, same as a second drawer close superseding the first
        job = scope.launch { restore() }
    }
}

/** docs/15 §3: a screen's [RefreshRestoreOwner], losing ownership when the screen leaves top or the Activity pauses. */
@Composable
internal fun rememberRefreshRestoreOwner(isTop: Boolean): RefreshRestoreOwner {
    val owner = remember { RefreshRestoreOwner() }
    LaunchedEffect(isTop) {
        if (!isTop) owner.ownershipLost()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) owner.ownershipLost()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return owner
}
