package tv.jellybeam.player

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPlaybackContractTest {
    @Test
    fun `PLAY action accepts a path-safe item id from the public extra`() {
        assertEquals(
            ExternalPlaybackIntentResult.Play("01234567-89ab-cdef_ABC"),
            ExternalPlaybackContract.parse(
                action = ExternalPlaybackContract.ACTION_PLAY,
                itemIdExtra = "01234567-89ab-cdef_ABC",
                dataUri = null,
            ),
        )
    }

    @Test
    fun `deep link accepts exactly one item-id path segment`() {
        assertEquals(
            ExternalPlaybackIntentResult.Play("0123456789abcdef"),
            ExternalPlaybackContract.parse(
                action = Intent.ACTION_VIEW,
                itemIdExtra = null,
                dataUri = "jellybeam://play/0123456789abcdef",
            ),
        )
    }

    @Test
    fun `OPEN_DETAIL action accepts a path-safe item id from the public extra`() {
        assertEquals(
            ExternalPlaybackIntentResult.OpenDetail("01234567-89ab-cdef_ABC"),
            ExternalPlaybackContract.parse(
                action = ExternalPlaybackContract.ACTION_OPEN_DETAIL,
                itemIdExtra = "01234567-89ab-cdef_ABC",
                dataUri = null,
            ),
        )
    }

    @Test
    fun `OPEN_DETAIL rejects a missing or malformed item id, same as PLAY`() {
        assertEquals(
            ExternalPlaybackIntentResult.Invalid,
            ExternalPlaybackContract.parse(
                action = ExternalPlaybackContract.ACTION_OPEN_DETAIL,
                itemIdExtra = null,
                dataUri = null,
            ),
        )
        assertEquals(
            ExternalPlaybackIntentResult.Invalid,
            ExternalPlaybackContract.parse(
                action = ExternalPlaybackContract.ACTION_OPEN_DETAIL,
                itemIdExtra = "https://server/item",
                dataUri = null,
            ),
        )
    }

    @Test
    fun `unrelated actions never become playback requests`() {
        assertEquals(
            ExternalPlaybackIntentResult.NotExternal,
            ExternalPlaybackContract.parse(
                action = Intent.ACTION_MAIN,
                itemIdExtra = "0123456789abcdef",
                dataUri = null,
            ),
        )
    }

    @Test
    fun `missing malformed and ambiguous targets are rejected`() {
        val invalidInputs = listOf(
            Triple(ExternalPlaybackContract.ACTION_PLAY, null, null),
            Triple(ExternalPlaybackContract.ACTION_PLAY, "https://server/item", null),
            Triple(Intent.ACTION_VIEW, null, "jellybeam://play"),
            Triple(Intent.ACTION_VIEW, null, "jellybeam://play/one/two"),
            Triple(Intent.ACTION_VIEW, null, "jellybeam://play/one?server=other"),
            Triple(Intent.ACTION_VIEW, null, "jellybeam://other/one"),
            Triple(Intent.ACTION_VIEW, null, "jellybeam://play/%2F"),
        )

        invalidInputs.forEach { (action, extra, uri) ->
            assertEquals(
                "$action / $extra / $uri",
                ExternalPlaybackIntentResult.Invalid,
                ExternalPlaybackContract.parse(action, extra, uri),
            )
        }
    }
}
