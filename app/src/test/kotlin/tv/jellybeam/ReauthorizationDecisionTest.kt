package tv.jellybeam

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.ReauthorizationDecision.Drop
import tv.jellybeam.ReauthorizationDecision.Show
import tv.jellybeam.ReauthorizationDecision.Wait
import tv.jellybeam.ui.signin.ReauthorizationTarget
import uniffi.jellybeam_core.AccountIdentity

/** docs/18 §2.1: one sign-in surface at a time; a request is answered, shown, held or dropped by what is on screen. */
class ReauthorizationDecisionTest {

    private val a = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")
    private val b = AccountIdentity(serverUrl = "http://b.test", userId = "u-b")
    private val showingA = ReauthorizationTarget(index = 0u, serverUrl = "http://a.test", userId = "u-a", userName = "a")

    private class Case(
        val name: String,
        val requestAccount: AccountIdentity,
        val currentRequestId: Long?,
        val showing: ReauthorizationTarget?,
        val expected: ReauthorizationDecision,
    )

    @Test
    fun `a request is decided by what is on screen and whether it is still pending`() {
        val cases = listOf(
            Case("nothing showing, request pending", a, 4L, null, Show),
            Case("the account already on screen is answered, never shown again", a, 4L, showingA, Drop),
            Case("same account at another slot is still that account", a, 4L, showingA.copy(index = 3u), Drop),
            Case("another account's request waits for the surface", b, 4L, showingA, Wait),
            Case("a request a reauthorization already answered is dropped", a, null, null, Drop),
            Case("a newer request replaced it", a, 5L, null, Drop),
            Case("answered even though its surface is up", a, null, showingA, Drop),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.expected,
                reauthorizationDecision(case.requestAccount, case.currentRequestId, requestId = 4L, showing = case.showing),
            )
        }
    }
}
