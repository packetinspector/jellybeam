package tv.jellybeam.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.jellybeam.data.runCatchingCancellable

/**
 * docs/31 §7: one owner's reports reach the core one at a time in the order sent, so a newer report
 * never lands before an older one; the consumer starts with the first send, so an owner that never reports runs nothing.
 */
internal class ReportQueue(private val scope: CoroutineScope) {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val consumer by lazy {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (_: CancellationException) {
                    // A report's own cancellation ends only that report; the queue's scope ending rethrows here.
                    ensureActive()
                }
            }
        }
    }

    /** Queues [work] behind every earlier send; its failure ends only itself. */
    fun send(work: suspend () -> Unit) {
        consumer
        jobs.trySend { runCatchingCancellable { work() } }
    }

    /** docs/31 §7: returns once every report sent before it has run; at once when the scope has ended, since nothing more runs. */
    suspend fun drain() {
        if (!scope.isActive) return
        val ran = CompletableDeferred<Unit>()
        send { ran.complete(Unit) }
        ran.await()
    }
}
