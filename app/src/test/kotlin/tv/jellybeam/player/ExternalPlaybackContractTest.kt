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
    fun `a deep link with an undashed UUID plays the same item id the UI uses`() {
        assertEquals(
            ExternalPlaybackIntentResult.Play("0123abcd-4567-89ef-0123-456789abcdef"),
            ExternalPlaybackContract.parse(
                action = Intent.ACTION_VIEW,
                itemIdExtra = null,
                dataUri = "jellybeam://play/0123ABCD456789EF0123456789ABCDEF",
            ),
        )
    }

    @Test
    fun `canonical item ids lowercase any UUID spelling and pass other ids through`() {
        val canonical = "0123abcd-4567-89ef-0123-456789abcdef"
        assertEquals(canonical, ExternalPlaybackContract.canonicalItemId("0123abcd456789ef0123456789abcdef"))
        assertEquals(canonical, ExternalPlaybackContract.canonicalItemId("0123ABCD-4567-89EF-0123-456789ABCDEF"))
        assertEquals(canonical, ExternalPlaybackContract.canonicalItemId(canonical))
        assertEquals("0123-abcd456789ef0123456789abcdef", ExternalPlaybackContract.canonicalItemId("0123-abcd456789ef0123456789abcdef"))
        assertEquals("0123456789abcdef", ExternalPlaybackContract.canonicalItemId("0123456789abcdef"))
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

    private fun play(startMsExtra: Any? = null, startFromBeginningExtra: Any? = null) = ExternalPlaybackContract.parse(
        action = ExternalPlaybackContract.ACTION_PLAY,
        itemIdExtra = "item-1",
        dataUri = null,
        startMsExtra = startMsExtra,
        startFromBeginningExtra = startFromBeginningExtra,
    )

    private fun link(uri: String) = ExternalPlaybackContract.parse(Intent.ACTION_VIEW, null, uri)

    @Test
    fun `START_FROM_BEGINNING alone starts the item from the beginning`() {
        assertEquals(ExternalPlaybackIntentResult.Play("item-1", startFromBeginning = true), play(startFromBeginningExtra = true))
        assertEquals(ExternalPlaybackIntentResult.Play("item-1"), play(startFromBeginningExtra = false))
    }

    @Test
    fun `START_MS alone starts at that position, zero included, Int or Long`() {
        assertEquals(ExternalPlaybackIntentResult.Play("item-1", startMs = 90_000L), play(startMsExtra = 90_000L))
        assertEquals(ExternalPlaybackIntentResult.Play("item-1", startMs = 90_000L), play(startMsExtra = 90_000))
        assertEquals(ExternalPlaybackIntentResult.Play("item-1", startMs = 0L), play(startMsExtra = 0L))
    }

    @Test
    fun `START_MS and START_FROM_BEGINNING together, a negative, or a wrong type are rejected`() {
        val invalid = ExternalPlaybackIntentResult.Invalid
        assertEquals(invalid, play(startMsExtra = 5L, startFromBeginningExtra = true))
        assertEquals(invalid, play(startMsExtra = 5L, startFromBeginningExtra = false))
        assertEquals(invalid, play(startMsExtra = -1L))
        assertEquals(invalid, play(startMsExtra = "5000"))
        assertEquals(invalid, play(startMsExtra = 1.5))
        assertEquals(invalid, play(startFromBeginningExtra = "true"))
    }

    @Test
    fun `a huge START_MS is accepted and left for the clamp`() {
        assertEquals(ExternalPlaybackIntentResult.Play("item-1", startMs = Long.MAX_VALUE), play(startMsExtra = Long.MAX_VALUE))
    }

    @Test
    fun `the start extras do nothing on OPEN_DETAIL`() {
        assertEquals(
            ExternalPlaybackIntentResult.OpenDetail("item-1"),
            ExternalPlaybackContract.parse(ExternalPlaybackContract.ACTION_OPEN_DETAIL, "item-1", null, startMsExtra = 5L),
        )
    }

    @Test
    fun `deep link from=start and t seconds map to the start fields`() {
        assertEquals(ExternalPlaybackIntentResult.Play("abc", startFromBeginning = true), link("jellybeam://play/abc?from=start"))
        assertEquals(ExternalPlaybackIntentResult.Play("abc", startMs = 90_000L), link("jellybeam://play/abc?t=90"))
        assertEquals(ExternalPlaybackIntentResult.Play("abc", startMs = 0L), link("jellybeam://play/abc?t=0"))
    }

    @Test
    fun `deep link queries outside from=start and one t are rejected`() {
        listOf(
            "?from=start&t=5", "?t=5&t=6", "?from=start&from=start", "?from=end", "?from=", "?from",
            "?t=-1", "?t=+5", "?t=1.5", "?t=abc", "?t=", "?t", "?", "?server=other", "?t=5&x=1", "?t=%35", "?%74=5", "?t=5&", "?t=99999999999999999999",
        ).forEach { query ->
            assertEquals(query, ExternalPlaybackIntentResult.Invalid, link("jellybeam://play/abc$query"))
        }
    }

    @Test
    fun `deep link item host opens the detail page and nothing more`() {
        assertEquals(ExternalPlaybackIntentResult.OpenDetail("abc"), link("jellybeam://item/abc"))
        assertEquals(
            ExternalPlaybackIntentResult.OpenDetail("0123abcd-4567-89ef-0123-456789abcdef"),
            link("jellybeam://item/0123ABCD456789EF" + "0123456789ABCDEF"),
        )
        listOf(
            "jellybeam://item", "jellybeam://item/a/b", "jellybeam://item/abc?t=5", "jellybeam://item/abc?from=start",
            "jellybeam://item/%2F", "jellybeam://item:80/abc", "jellybeam://user@item/abc", "jellybeam://item/abc#x",
        ).forEach { assertEquals(it, ExternalPlaybackIntentResult.Invalid, link(it)) }
    }

    @Test
    fun `play deep links still reject port userinfo and fragment`() {
        listOf("jellybeam://play:80/abc", "jellybeam://user@play/abc", "jellybeam://play/abc#x", "jellybeam://play/abc?t=5#x").forEach {
            assertEquals(it, ExternalPlaybackIntentResult.Invalid, link(it))
        }
    }
}
