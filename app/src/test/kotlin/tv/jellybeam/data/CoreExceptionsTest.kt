package tv.jellybeam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.CoreException
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.UnreachableReason

/** [CoreException.displayMessage] copy for sign-in failures (docs/13-feature-list.md "sign-in"). */
class CoreExceptionsTest {

    private val host = "media.example.test:8096"
    private val strings = ResourceUiStrings.default

    @Test
    fun `seerr credentials rejected names the identity each method expects`() {
        assertEquals(
            "Wrong email or password. A Seerr account signs in with its email address.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.LOCAL).displayMessage(strings),
        )
        assertEquals(
            "Wrong username or password.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.JELLYFIN).displayMessage(strings),
        )
        assertEquals(
            "The server rejected that API key.",
            CoreException.SeerrInvalidCredentials(SeerrAuthMethod.API_KEY).displayMessage(strings),
        )
    }

    @Test
    fun `seerr unreachable names the reason and never the url`() {
        assertEquals(
            "Couldn't reach Seerr (connection refused). Check that it's running, or its address in Settings.",
            CoreException.SeerrUnreachable(UnreachableReason.CONNECTION_REFUSED).displayMessage(strings),
        )
        assertEquals(
            "Couldn't reach Seerr. Check that it's running, or its address in Settings.",
            CoreException.SeerrUnreachable(UnreachableReason.OTHER).displayMessage(strings),
        )
    }

    @Test
    fun `invalid server address`() {
        assertEquals(
            "That doesn't look like a server address. Enter it the way your browser shows it, like http://192.0.2.10:8096.",
            CoreException.InvalidServerAddress("not a url").displayMessage(strings),
        )
    }

    @Test
    fun `server unreachable includes host and reason`() {
        assertEquals(
            "Couldn't reach $host (timed out). Check the address and port, and that the server is running.",
            CoreException.ServerUnreachable(host, UnreachableReason.TIMED_OUT, "").displayMessage(strings),
        )
    }

    @Test
    fun `server unreachable words each known reason and shows an unknown one verbatim`() {
        val words = mapOf(
            UnreachableReason.NAME_NOT_RESOLVED to "the name could not be resolved",
            UnreachableReason.CONNECTION_REFUSED to "connection refused",
            UnreachableReason.NETWORK_UNREACHABLE to "network unreachable",
            UnreachableReason.OTHER to "tls handshake eof",
        )
        words.forEach { (reason, text) ->
            assertEquals(
                "Couldn't reach $host ($text). Check the address and port, and that the server is running.",
                CoreException.ServerUnreachable(host, reason, "tls handshake eof").displayMessage(strings),
            )
        }
    }

    @Test
    fun `update storage unavailable has its own words and label`() {
        assertEquals("Update storage is unavailable", CoreException.UpdateStorageUnavailable().displayMessage(strings))
        assertEquals("update_storage_unavailable", CoreException.UpdateStorageUnavailable().diagLabel())
    }

    @Test
    fun `https not offered suggests http`() {
        assertEquals(
            "$host isn't answering over HTTPS. Try the same address with http:// instead.",
            CoreException.HttpsNotOffered(host).displayMessage(strings),
        )
    }

    @Test
    fun `not jellyfin server includes status`() {
        assertEquals(
            "$host answered, but it isn't a Jellyfin server (HTTP 404). Check the port; Jellyfin's default is 8096.",
            CoreException.NotJellyfinServer(host, 404u).displayMessage(strings),
        )
    }

    @Test
    fun `invalid credentials`() {
        assertEquals("Wrong username or password.", CoreException.InvalidCredentials().displayMessage(strings))
    }

    @Test
    fun `unauthorized is unchanged`() {
        assertEquals("Authorization expired.", CoreException.Unauthorized(account = null).displayMessage(strings))
    }

    @Test
    fun `a dead Jellyfin token routes to re-authorization from any browse call`() {
        val unauthorized = CoreException.Unauthorized(account = null)

        assertTrue(routesToReauthorization("ffi.getItemDetail", unauthorized))
        assertTrue(routesToReauthorization("ffi.getSimilar", unauthorized))
    }

    @Test
    fun `Seerr's own 401, the sign-in flows and other failures never open re-authorization`() {
        val unauthorized = CoreException.Unauthorized(account = null)

        assertFalse(routesToReauthorization("ffi.seerrHome", unauthorized))
        assertFalse(routesToReauthorization("ffi.seerrPersonDiscoverCredits", unauthorized))
        assertFalse(routesToReauthorization("ffi.pollQuickConnect", unauthorized))
        assertFalse(routesToReauthorization("ffi.completeQuickConnectReauthorization", unauthorized))
        assertFalse(routesToReauthorization("ffi.getItemDetail", CoreException.Api("boom")))
    }

    @Test
    fun `the diagnostic label is the variant, never its text`() {
        assertEquals("unauthorized", CoreException.Unauthorized(account = null).diagLabel())
        assertEquals("api", CoreException.Api("GET http://media.example.test/Items failed").diagLabel())
        assertEquals("server_unreachable", CoreException.ServerUnreachable(host, UnreachableReason.TIMED_OUT, "").diagLabel())
    }
}
