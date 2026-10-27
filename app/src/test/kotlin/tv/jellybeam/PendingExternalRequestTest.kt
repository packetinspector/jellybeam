package tv.jellybeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.player.HostEvent
import tv.jellybeam.player.PlaybackActivityTracker
import tv.jellybeam.player.PlayerFlow
import tv.jellybeam.player.Req
import tv.jellybeam.player.RequestHost

/** docs/18 §2.1: an account change drops a waiting external request, so it never runs on the new account. */
class PendingExternalRequestTest {
    private val tracker = PlaybackActivityTracker()
    private val started = mutableListOf<Req<*>>()

    init {
        tracker.attachHost(RequestHost(start = { started += it }, signInRequired = {}, leaveGroup = {}))
    }

    @Test
    fun `an account change drops a waiting request of either kind`() {
        listOf(Req.Kind.Play("item-1"), Req.Kind.OpenDetail("item-2")).forEach { kind ->
            tracker.send(HostEvent.RootChanged(PlayerFlow.Root.Pending))
            tracker.arrive(kind, Req.Origin.External)

            tracker.send(HostEvent.AccountReset)
            tracker.send(HostEvent.RootChanged(PlayerFlow.Root.Home))
            tracker.send(HostEvent.MainShown)

            assertEquals("$kind never starts", emptyList<Req<*>>(), started)
            assertNull("$kind is not left for the detail handler", tracker.state.value.waitingDetail())
            tracker.send(HostEvent.MainHidden)
        }
    }

    @Test
    fun `a detail request waits on Home for the detail handler until it is done`() {
        tracker.send(HostEvent.RootChanged(PlayerFlow.Root.Home))
        tracker.arrive(Req.Kind.OpenDetail("item-2"), Req.Origin.External)

        val waiting = tracker.state.value.waitingDetail()
        assertEquals(Req(1, Req.Kind.OpenDetail("item-2"), Req.Origin.External), waiting)
        tracker.send(HostEvent.DetailDone(waiting!!.id))
        assertNull(tracker.state.value.waitingDetail())
    }
}
