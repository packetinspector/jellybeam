package tv.jellybeam

import kotlinx.coroutines.flow.MutableStateFlow
import uniffi.jellybeam_core.AccountEpochs

/** A core epoch pair at epoch 0 with nothing parked, as [tv.jellybeam.data.CoreGateway.accountEpochs] publishes it. */
internal fun epochsFlow() = MutableStateFlow(AccountEpochs(epoch = 0uL, parked = null))

/** Moves the epoch alone, keeping what is parked. */
internal var MutableStateFlow<AccountEpochs>.epoch: ULong
    get() = value.epoch
    set(next) {
        value = value.copy(epoch = next)
    }
