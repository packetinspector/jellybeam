package tv.jellybeam

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.player.ExternalPlaybackIntentResult
import tv.jellybeam.player.PlayerFlow
import tv.jellybeam.player.Req

/** docs/05 "external", docs/17 §3: what MainActivity hands the owner for an intent, and the root it gates on. */
class ExternalArrivalTest {
    private val play = ExternalPlaybackIntentResult.Play("item-1")

    /** docs/17 §3: a recreate keeps the last intent, already handled, so it neither arrives nor toasts again. */
    @Test
    fun `only a fresh create's intent arrives`() {
        assertEquals(play, createArrival(restored = false, play))
        assertEquals(ExternalPlaybackIntentResult.NotExternal, createArrival(restored = true, play))
        assertEquals(ExternalPlaybackIntentResult.NotExternal, createArrival(restored = true, ExternalPlaybackIntentResult.Invalid))
        assertEquals(ExternalPlaybackIntentResult.Invalid, createArrival(restored = false, ExternalPlaybackIntentResult.Invalid))
    }

    @Test
    fun `the request carries the intent's start fields`() {
        assertEquals(Req.Kind.Play("item-1", Req.Start.Resume), play.kind())
        assertEquals(Req.Kind.Play("item-1", Req.Start.Beginning), play.copy(startFromBeginning = true).kind())
        assertEquals(Req.Kind.Play("item-1", Req.Start.At(90_000L)), play.copy(startMs = 90_000L).kind())
    }

    @Test
    fun `the owner's root is pending until the stack resolves, then Home or signed out`() {
        assertEquals(PlayerFlow.Root.Pending, rootOf(null))
        assertEquals(PlayerFlow.Root.Home, rootOf(NavBackStack.of(Screen.Home)))
        assertEquals(PlayerFlow.Root.SignedOut, rootOf(NavBackStack.of(Screen.SignIn)))
    }
}
