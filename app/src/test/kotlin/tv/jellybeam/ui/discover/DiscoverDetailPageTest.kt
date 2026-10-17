package tv.jellybeam.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/15 §2: one derived page for the render branch and the restore's ready/fallback. */
class DiscoverDetailPageTest {

    private data class Row(
        val loading: Boolean,
        val notConfigured: Boolean,
        val error: Boolean,
        val card: Boolean,
        val page: DiscoverDetailPage,
        val fallback: DiscoverDetailFallback,
    )

    private val rows = listOf(
        Row(loading = true, notConfigured = false, error = false, card = false, page = DiscoverDetailPage.Loading, fallback = DiscoverDetailFallback.None),
        Row(loading = true, notConfigured = false, error = false, card = true, page = DiscoverDetailPage.Loading, fallback = DiscoverDetailFallback.None),
        // The VM sets both on SeerrNotConfigured: the page draws the message, which has no Retry.
        Row(loading = false, notConfigured = true, error = true, card = false, page = DiscoverDetailPage.NotConfigured, fallback = DiscoverDetailFallback.None),
        Row(loading = false, notConfigured = true, error = false, card = false, page = DiscoverDetailPage.NotConfigured, fallback = DiscoverDetailFallback.None),
        Row(loading = false, notConfigured = true, error = true, card = true, page = DiscoverDetailPage.NotConfigured, fallback = DiscoverDetailFallback.None),
        Row(loading = false, notConfigured = false, error = true, card = false, page = DiscoverDetailPage.Error, fallback = DiscoverDetailFallback.Retry),
        // A failed re-read keeps the card on screen.
        Row(loading = false, notConfigured = false, error = true, card = true, page = DiscoverDetailPage.Content, fallback = DiscoverDetailFallback.Actions),
        Row(loading = false, notConfigured = false, error = false, card = true, page = DiscoverDetailPage.Content, fallback = DiscoverDetailFallback.Actions),
        Row(loading = false, notConfigured = false, error = false, card = false, page = DiscoverDetailPage.Loading, fallback = DiscoverDetailFallback.None),
    )

    @Test
    fun `page and fallback table`() {
        rows.forEach { row ->
            val page = discoverDetailPage(row.loading, row.notConfigured, row.error, row.card)
            assertEquals(row.toString(), row.page, page)
            assertEquals(row.toString(), row.fallback, discoverDetailFallback(page))
        }
    }

    @Test
    fun `the page owns focus only while top with its drawer closed`() {
        assertEquals(true, discoverPageOwnsFocus(isTop = true, drawerOpen = false))
        assertEquals(false, discoverPageOwnsFocus(isTop = true, drawerOpen = true))
        assertEquals(false, discoverPageOwnsFocus(isTop = false, drawerOpen = false))
        assertEquals(false, discoverPageOwnsFocus(isTop = false, drawerOpen = true))
    }

    @Test
    fun `only an in-place drawer close over an open dialog hands focus to the dialog`() {
        assertEquals(true, drawerCloseHandledByDialog(navigatingAway = false, dialogOpen = true))
        assertEquals(false, drawerCloseHandledByDialog(navigatingAway = false, dialogOpen = false))
        assertEquals(false, drawerCloseHandledByDialog(navigatingAway = true, dialogOpen = true))
        assertEquals(false, drawerCloseHandledByDialog(navigatingAway = true, dialogOpen = false))
    }
}
