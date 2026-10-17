package tv.jellybeam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import uniffi.jellybeam_core.AccountEpochs

/**
 * docs/18 §2.1: one account-tree reset per core account change (Main thread only), then a bump of [epoch]
 * that re-keys the startup and drawer effects; an Int, so a second change before the first finishes is a new key.
 */
class AccountTreeOwner(private val accountEpochs: StateFlow<AccountEpochs>, private val reset: () -> Unit) {
    var epoch: Int by mutableIntStateOf(0)
        private set

    /** The core epoch the tree last reset for; `null` until the restore baseline is taken. */
    private var seen: ULong? = null

    /**
     * Once restore completed (a cold-start restore is not an account change), takes the core epoch as the
     * baseline, then resets once and advances once per later change.
     */
    suspend fun follow(awaitRestored: suspend () -> Unit) {
        awaitRestored()
        seen = accountEpochs.value.epoch
        accountEpochs.collect { pair ->
            if (pair.epoch == seen) return@collect
            seen = pair.epoch
            reset()
            epoch++
        }
    }
}
