package tv.jellybeam.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.jellybeam_core.Settings

/**
 * docs/09: the one holder and writer of [Settings]. [update] applies its transform to the newest
 * published record at once and persists the whole record in call order, so no change is lost to a
 * stale copy and a later [update] never overwrites an earlier field.
 */
class SettingsStore(private val gateway: CoreGateway, scope: CoroutineScope) {
    private val _settings = MutableStateFlow<Settings?>(null)

    /** The newest record; `null` until the first read completes. */
    val settings: StateFlow<Settings?> = _settings.asStateFlow()

    private val loading = Mutex()
    private val writes = Mutex()

    init {
        // Seeds the first read; a failure leaves it to the next load()/update().
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatchingCancellable { load() } }
    }

    /** The published record, read from the core first if nothing has been published yet. */
    suspend fun load(): Settings = _settings.value ?: loading.withLock {
        _settings.value ?: gateway.getSettings().also { _settings.value = it }
    }

    /** Publishes `transform(newest)` immediately, then persists the newest record behind earlier writes. */
    suspend fun update(transform: (Settings) -> Settings): Settings {
        load()
        var updated: Settings? = null
        _settings.update { current -> transform(checkNotNull(current)).also { updated = it } }
        // NonCancellable: the record is already published, so a caller that goes away still persists it.
        // The newest record, not this call's: a write queued behind a slower one never persists a stale copy.
        withContext(NonCancellable) { writes.withLock { gateway.setSettings(checkNotNull(_settings.value)) } }
        return checkNotNull(updated)
    }
}
