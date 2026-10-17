package tv.jellybeam.data

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.JellybeamCoreInterface
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrRequestInput
import uniffi.jellybeam_core.SeerrStatus

/**
 * docs/14: `seerrRevision` moves exactly once per successful Seerr connect, disconnect, submit
 * and cancel, even when the caller is cancelled while the core call runs (Back mid-submit).
 */
class RealCoreGatewaySeerrRevisionTest {

    private val movie = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = 1L,
        title = "Synthetic Title",
        year = null,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    private val detail = SeerrRequestDetail.Movie(
        SeerrMovieDetail(
            card = movie,
            runtimeMinutes = null,
            genres = emptyList(),
            cast = emptyList(),
            similar = emptyList(),
            recommendations = emptyList(),
            trailerUrl = null,
            criticsScore = null,
            audienceScore = null,
            activeRequest = null,
            canRequest = true,
            canRequest4k = false,
        ),
    )

    private val status = SeerrStatus(configured = true, seerrUrl = "http://seerr.test", method = SeerrAuthMethod.API_KEY, identity = null, appTitle = null)

    /** Every call announces itself, waits for [release] (when given), then answers or fails. */
    private class Core(
        private val failure: CoreException?,
        private val entered: CountDownLatch,
        private val release: CountDownLatch?,
        private val answers: Map<Class<*>, Any>,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            entered.countDown()
            release?.await(5, TimeUnit.SECONDS)
            if (failure != null) throw failure
            return answers[method.returnType]
        }
    }

    /**
     * The generated interface declares no `throws`, so a [Proxy] wraps the checked [CoreException]
     * the real binding throws directly; this hands the four Seerr calls back unwrapped.
     */
    private class Unwrapped(private val core: JellybeamCoreInterface) : JellybeamCoreInterface by core {
        private inline fun <T> unwrapped(call: () -> T): T =
            try {
                call()
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }

        override fun seerrSubmitRequestDetail(input: SeerrRequestInput): SeerrRequestDetail = unwrapped { core.seerrSubmitRequestDetail(input) }

        override fun seerrCancelRequestDetail(mediaType: SeerrMediaType, tmdbId: Long, requestId: Long): SeerrRequestDetail =
            unwrapped { core.seerrCancelRequestDetail(mediaType, tmdbId, requestId) }

        override fun seerrConnect(url: String, method: SeerrAuthMethod, identity: String, secret: String): SeerrStatus =
            unwrapped { core.seerrConnect(url, method, identity, secret) }

        override fun seerrDisconnect() = unwrapped { core.seerrDisconnect() }
    }

    private val seerrOps: List<Pair<String, suspend CoreGateway.() -> Unit>> = listOf(
        "submit" to { seerrSubmitRequestDetail(SeerrRequestInput(SeerrMediaType.MOVIE, 1L, false, emptyList(), null, null, null)); Unit },
        "cancel" to { seerrCancelRequestDetail(SeerrMediaType.MOVIE, 1L, 7L); Unit },
        "connect" to { seerrConnect("http://seerr.test", SeerrAuthMethod.API_KEY, "", "synthetic-key"); Unit },
        "disconnect" to { seerrDisconnect() },
    )

    /** Runs [op] against a core that answers or fails with [failure]; returns the gateway's revision once the call is done. */
    private suspend fun revisionAfter(op: suspend CoreGateway.() -> Unit, failure: CoreException?, callerCancelled: Boolean, label: String): Long {
        val entered = CountDownLatch(1)
        val release = if (callerCancelled) CountDownLatch(1) else null
        val core = Core(failure, entered, release, mapOf(SeerrRequestDetail::class.java to detail, SeerrStatus::class.java to status))
        val gateway = RealCoreGateway(
            core = CompletableDeferred(
                Unwrapped(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core) as JellybeamCoreInterface),
            ),
            backgroundScope = CoroutineScope(Job()),
        )

        val call = CoroutineScope(Job()).launch { runCatching { gateway.op() } }
        if (callerCancelled) {
            assertTrue(label, entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            release!!.countDown()
        }
        call.join()
        return gateway.seerrRevision.value
    }

    @Test
    fun `the revision moves once per successful Seerr change whether or not the caller waited`() = runBlocking {
        for ((name, op) in seerrOps) {
            for (callerCancelled in listOf(false, true)) {
                for (fail in listOf(false, true)) {
                    val label = "$name callerCancelled=$callerCancelled fail=$fail"
                    val failure = if (fail) CoreException.Api("synthetic failure") else null
                    assertEquals(label, if (fail) 0L else 1L, revisionAfter(op, failure, callerCancelled, label))
                }
            }
        }
    }

    /** docs/14 Errors: a submit/cancel that changed Seerr but whose detail could not be read still announces the change. */
    @Test
    fun `a change that landed but could not be read back still moves the revision`() = runBlocking {
        for ((name, op) in seerrOps.take(2)) {
            for (callerCancelled in listOf(false, true)) {
                val label = "$name callerCancelled=$callerCancelled"
                assertEquals(label, 1L, revisionAfter(op, CoreException.SeerrChangeUnconfirmed(), callerCancelled, label))
            }
        }
    }
}
