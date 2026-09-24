package tv.jellybeam.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fills docs/17-mini-player.md's gap: dismissing PiP reports the final position with no live
 * Detail/Home screen in that call stack, and their `changeEvents()` collectors are debounced with
 * a `DROP_OLDEST` buffer, so neither promises a prompt refresh. [stopEpoch] is bumped once, after
 * [tv.jellybeam.data.CoreGateway.stopPlayback] returns past its commit barrier, so a collector
 * reacting
 * to it always reads the committed position. Process-local singleton (like [PipController]):
 * [PlaybackViewModel] doesn't outlive its `PlaybackActivity`, but Detail/Home have no reference to
 * it.
 */
internal object PlaybackReports {
    private val _stopEpoch = MutableStateFlow(0L)

    /** Bumped once per landed stop report; carries no payload, collectors re-query via
     * [tv.jellybeam.data.CoreGateway.cardById]/`homeSnapshot`.
     */
    val stopEpoch: StateFlow<Long> = _stopEpoch.asStateFlow()

    fun onStopLanded() {
        _stopEpoch.update { it + 1 }
    }
}
