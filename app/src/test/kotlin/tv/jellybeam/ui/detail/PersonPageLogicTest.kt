package tv.jellybeam.ui.detail

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard
import tv.jellybeam.ui.detail.PersonPageLogic.InitialRow

class PersonPageLogicTest {
    private val strings = ResourceUiStrings()

    private fun lines(birth: String?, death: String?, place: String?, locale: Locale = Locale.US) =
        PersonPageLogic.lifeLines(strings, birth, death, place, locale)

    @Test
    fun `birth date and place read as one line`() {
        assertEquals(listOf("Born May 4, 1970 in Sampletown"), lines("1970-05-04T00:00:00+00:00", null, "Sampletown"))
    }

    @Test
    fun `death adds its own line`() {
        assertEquals(
            listOf("Born May 4, 1970", "Died Jan 2, 2020"),
            lines("1970-05-04T00:00:00+00:00", "2020-01-02T00:00:00+00:00", null),
        )
    }

    @Test
    fun `place alone and death alone still show`() {
        assertEquals(listOf("Born in Sampletown"), lines(null, null, "Sampletown"))
        assertEquals(listOf("Died Jan 2, 2020"), lines(null, "2020-01-02T00:00:00+00:00", null))
    }

    @Test
    fun `nothing known gives no lines and blank or malformed values are ignored`() {
        assertTrue(lines(null, null, null).isEmpty())
        assertTrue(lines("not a date", "", "  ").isEmpty())
    }

    @Test
    fun `dates follow the device locale order`() {
        assertEquals(listOf("Born 4 May 1970"), lines("1970-05-04T00:00:00+00:00", null, null, Locale.UK))
    }

    @Test
    fun `initial focus prefers the library row, then Seerr, then MORE, then the name`() {
        assertEquals(InitialRow.LIBRARY, PersonPageLogic.initialRow(libraryCount = 2, discoverCount = 5, hasMore = true))
        assertEquals(InitialRow.DISCOVER, PersonPageLogic.initialRow(libraryCount = 0, discoverCount = 5, hasMore = true))
        assertEquals(InitialRow.MORE, PersonPageLogic.initialRow(libraryCount = 0, discoverCount = 0, hasMore = true))
        assertEquals(InitialRow.HEADER, PersonPageLogic.initialRow(libraryCount = 0, discoverCount = 0, hasMore = false))
    }

    @Test
    fun `an empty page waits briefly for MORE before settling on the name`() {
        val grace = PersonPageLogic.MORE_GRACE_ATTEMPTS
        assertEquals(PersonPageLogic.EmptyPageAnchor.MORE, PersonPageLogic.emptyPageAnchor(true, true, 0))
        assertEquals(PersonPageLogic.EmptyPageAnchor.WAIT, PersonPageLogic.emptyPageAnchor(false, true, 0))
        assertEquals(PersonPageLogic.EmptyPageAnchor.HEADER, PersonPageLogic.emptyPageAnchor(false, true, grace))
        assertEquals(PersonPageLogic.EmptyPageAnchor.HEADER, PersonPageLogic.emptyPageAnchor(false, false, 0))
    }

    @Test
    fun `the name is a focus stop only on a loaded page with no posters and nothing pending`() {
        assertTrue(PersonPageLogic.headerFocusable(true, 0, 0, false))
        assertFalse(PersonPageLogic.headerFocusable(true, 0, 0, true))
        assertFalse(PersonPageLogic.headerFocusable(true, 1, 0, false))
        assertFalse(PersonPageLogic.headerFocusable(true, 0, 2, false))
        assertFalse(PersonPageLogic.headerFocusable(false, 0, 0, false))
    }

    @Test
    fun `focus waits for Seerr only when the library row is empty`() {
        assertFalse(PersonPageLogic.focusReady(pageLoaded = false, libraryCount = 3, discoverPending = false))
        assertTrue(PersonPageLogic.focusReady(pageLoaded = true, libraryCount = 3, discoverPending = true))
        assertFalse(PersonPageLogic.focusReady(pageLoaded = true, libraryCount = 0, discoverPending = true))
        assertTrue(PersonPageLogic.focusReady(pageLoaded = true, libraryCount = 0, discoverPending = false))
    }

    @Test
    fun `a return refresh updates shown cards and never changes which are shown`() {
        val shown = listOf(testCard(id = "a", name = "Old A"), testCard(id = "b", name = "Old B"))
        val fresh = listOf(testCard(id = "c", name = "New C"), testCard(id = "a", name = "New A"))
        assertEquals(listOf("New A", "Old B"), PersonPageLogic.refreshedLibrary(shown, fresh).map { it.name })
    }
}
