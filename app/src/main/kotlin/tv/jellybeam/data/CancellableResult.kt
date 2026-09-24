package tv.jellybeam.data

import kotlinx.coroutines.CancellationException

/** Captures recoverable failures without swallowing coroutine cancellation or fatal errors. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
