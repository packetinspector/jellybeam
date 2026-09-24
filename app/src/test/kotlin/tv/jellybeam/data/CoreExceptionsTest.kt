package tv.jellybeam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAuthMethod

/** [CoreException.displayMessage] copy for sign-in failures (docs/13-feature-list.md "sign-in"). */
class CoreExceptionsTest {

    private val host = "media.example.test:8096"

    @Test
    fun `seerr credentials rejected names the identity each method expects`() {
        assertEquals(
            "Wrong email or password. A Seerr account signs in with its email address.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.LOCAL).displayMessage(),
        )
        assertEquals(
            "Wrong username or password.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.JELLYFIN).displayMessage(),
        )
        assertEquals(
            "The server rejected that API key.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.API_KEY).displayMessage(),
        )
    }

    @Test
    fun `invalid server address`() {
        assertEquals(
            "That doesn't look like a server address. Enter it the way your browser shows it, like http://192.0.2.10:8096.",
            CoreException.InvalidServerAddress("not a url").displayMessage(),
        )
    }

    @Test
    fun `server unreachable includes host and reason`() {
        assertEquals(
            "Couldn't reach $host (connection timed out). Check the address and port, and that the server is running.",
            CoreException.ServerUnreachable(host, "connection timed out").displayMessage(),
        )
    }

    @Test
    fun `https not offered suggests http`() {
        assertEquals(
            "$host isn't answering over HTTPS. Try the same address with http:// instead.",
            CoreException.HttpsNotOffered(host).displayMessage(),
        )
    }

    @Test
    fun `not jellyfin server includes status`() {
        assertEquals(
            "$host answered, but it isn't a Jellyfin server (HTTP 404). Check the port; Jellyfin's default is 8096.",
            CoreException.NotJellyfinServer(host, 404u).displayMessage(),
        )
    }

    @Test
    fun `invalid credentials`() {
        assertEquals("Wrong username or password.", CoreException.InvalidCredentials().displayMessage())
    }

    @Test
    fun `unauthorized is unchanged`() {
        assertEquals("Authorization expired.", CoreException.Unauthorized().displayMessage())
    }

    @Test
    fun `a dead Jellyfin token routes to re-authorization from any browse call`() {
        val unauthorized = CoreException.Unauthorized()

        assertTrue(routesToReauthorization("ffi.getItemDetail", unauthorized))
        assertTrue(routesToReauthorization("ffi.getSimilar", unauthorized))
    }

    @Test
    fun `Seerr's own 401, the sign-in flows and other failures never open re-authorization`() {
        val unauthorized = CoreException.Unauthorized()

        assertFalse(routesToReauthorization("ffi.seerrHome", unauthorized))
        assertFalse(routesToReauthorization("ffi.pollQuickConnect", unauthorized))
        assertFalse(routesToReauthorization("ffi.completeQuickConnectReauthorization", unauthorized))
        assertFalse(routesToReauthorization("ffi.getItemDetail", CoreException.Api("boom")))
    }

    @Test
    fun `the diagnostic label is the variant, never its text`() {
        assertEquals("unauthorized", CoreException.Unauthorized().diagLabel())
        assertEquals("api", CoreException.Api("GET http://media.example.test/Items failed").diagLabel())
        assertEquals("server_unreachable", CoreException.ServerUnreachable(host, "timed out").diagLabel())
    }
}
