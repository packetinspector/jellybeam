package tv.jellybeam.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "JellybeamPip"

/**
 * Process-local PiP state (docs/17 §2), one instance backing whichever `singleInstance`
 * [tv.jellybeam.player.PlaybackActivity] is alive, so [tv.jellybeam.MainActivity]'s app-exit cleanup
 * (docs/17 §3) can reach an active PiP session without a direct Activity reference.
 */
internal class PipController {
    private val _isInPip = MutableStateFlow(false)

    /** Mirrors [tv.jellybeam.player.PlaybackActivity]'s own `onPictureInPictureModeChanged` state. */
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    fun setInPip(value: Boolean) {
        _isInPip.value = value
    }

    /**
     * Current video aspect, clamped via [PipAspect.clamp]; fed by `onVideoSizeChanged`, read by
     * [params]. `@Volatile` since it's written from a player-listener callback.
     */
    @Volatile
    var aspect: Pair<Int, Int> = 16 to 9
        private set

    fun updateVideoSize(width: Int, height: Int) {
        aspect = PipAspect.clamp(width, height)
    }

    /**
     * Teardown callback for [tv.jellybeam.MainActivity]'s app-exit cleanup; set on create, cleared on
     * [onActivityDestroyed]. `singleInstance` routes new playback via `onNewIntent` instead.
     */
    var finishPlayback: (() -> Unit)? = null

    /** Stops active PiP playback (docs/17 §3) to avoid an orphan headless window. */
    fun stopPlayback() {
        if (_isInPip.value) {
            Log.i(TAG, "Stopping PiP playback via callback")
            finishPlayback?.invoke()
        }
    }

    /** Clears all state on [tv.jellybeam.player.PlaybackActivity.onDestroy] so nothing outlives it. */
    fun onActivityDestroyed() {
        _isInPip.value = false
        finishPlayback = null
    }

    /** API 26+, [PackageManager.FEATURE_PICTURE_IN_PICTURE], and the AppOps grant must all hold. */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check PiP support", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun params(): PictureInPictureParams {
        val (width, height) = aspect
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
    }

    /** Enters PiP for [activity]; returns `false` on failure, never throws. */
    fun enter(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isSupported(activity)) return false

        return try {
            activity.enterPictureInPictureMode(params())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enter PiP mode", e)
            false
        }
    }
}

internal val pipController = PipController()
