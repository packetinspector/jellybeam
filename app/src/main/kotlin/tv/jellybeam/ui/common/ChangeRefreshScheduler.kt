package tv.jellybeam.ui.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * docs/16 §4.6: an event [affects] accepts only marks dirty; one loop refreshes while dirty and
 * [active], then rests [restMs], so an active screen refreshes on the leading event plus one
 * trailing run and a hidden one refreshes once when it becomes active.
 */
class ChangeRefreshScheduler<T>(
    scope: CoroutineScope,
    events: Flow<T>,
    private val active: StateFlow<Boolean>,
    private val refresh: suspend () -> Unit,
    private val restMs: Long = 500L,
    private val onError: (Throwable) -> Unit = { Log.w(TAG, "refresh failed", it) },
    /** Whether an event marks dirty; may suspend, which [events] is buffered against (docs/07 §5). */
    private val affects: suspend (T) -> Boolean = { true },
) {
    private val dirty = MutableStateFlow(false)

    /** A change the event stream can't carry; served by the same loop as an event. */
    fun markDirty() {
        dirty.value = true
    }

    init {
        // Unlimited buffer: a suspending [affects] must not make this subscriber lag a DROP_OLDEST bus.
        scope.launch { events.buffer(Channel.UNLIMITED).filter { affects(it) }.collect { dirty.value = true } }
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (true) {
            combine(dirty, active) { isDirty, isActive -> isDirty && isActive }.first { it }
            dirty.value = false
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            delay(restMs)
        }
    }

    private companion object {
        const val TAG = "ChangeRefresh"
    }
}

/** docs/16 §4.6: a screen's view model is active only while its layer is top and composed, so a stack an overlay replaced stays inactive. */
@Composable
fun ActiveWhileTop(isTop: Boolean, setActive: (Boolean) -> Unit) {
    DisposableEffect(isTop) {
        setActive(isTop)
        onDispose { setActive(false) }
    }
}
