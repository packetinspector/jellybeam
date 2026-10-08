package tv.jellybeam.ui.detail

import java.time.LocalDate
import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.mediumDateFormatter
import uniffi.jellybeam_core.Card

/** Pure decisions for the library person page (docs/11 §Person page). */
object PersonPageLogic {
    /**
     * docs/11 §Person page: a return refresh updates the cards already shown (badges, progress)
     * and never the row's membership, so the poster focus is restored to can't vanish under it.
     */
    fun refreshedLibrary(shown: List<Card>, fresh: List<Card>): List<Card> {
        val byId = fresh.associateBy { it.id }
        return shown.map { byId[it.id] ?: it }
    }

    /** Where a fresh entry lands: a poster row, else the biography's MORE stop, else the name. */
    enum class InitialRow { LIBRARY, DISCOVER, MORE, HEADER }

    /**
     * docs/11 §Person page: first library poster, else the first Not-in-your-library card, else
     * MORE when the biography is clamped, else the name, so the D-pad always has an anchor.
     */
    fun initialRow(libraryCount: Int, discoverCount: Int, hasMore: Boolean = false): InitialRow = when {
        libraryCount > 0 -> InitialRow.LIBRARY
        discoverCount > 0 -> InitialRow.DISCOVER
        hasMore -> InitialRow.MORE
        else -> InitialRow.HEADER
    }

    enum class EmptyPageAnchor { MORE, WAIT, HEADER }

    /**
     * With both rows empty: MORE once its stop is composed. A biography's clamp is only known a
     * frame after layout, so a present overview gets [MORE_GRACE_ATTEMPTS] misses before the name.
     */
    fun emptyPageAnchor(moreRegistered: Boolean, overviewPresent: Boolean, misses: Int): EmptyPageAnchor = when {
        moreRegistered -> EmptyPageAnchor.MORE
        overviewPresent && misses < MORE_GRACE_ATTEMPTS -> EmptyPageAnchor.WAIT
        else -> EmptyPageAnchor.HEADER
    }

    const val MORE_GRACE_ATTEMPTS = 3

    /** The name is a focus stop only on a page with no poster to land on. */
    fun headerFocusable(pageLoaded: Boolean, libraryCount: Int, discoverCount: Int, discoverPending: Boolean): Boolean =
        pageLoaded && libraryCount == 0 && discoverCount == 0 && !discoverPending

    /** Focus waits for the Seerr row only when it is the page's one possible landing spot. */
    fun focusReady(pageLoaded: Boolean, libraryCount: Int, discoverPending: Boolean): Boolean =
        pageLoaded && (libraryCount > 0 || !discoverPending)

    /**
     * Born / died lines, each present only when its data is. Dates are the server's RFC3339
     * calendar day, formatted in [locale]'s own order without a zone shift (docs/27 §1).
     */
    fun lifeLines(
        strings: UiStrings,
        birthDate: String?,
        deathDate: String?,
        birthPlace: String?,
        locale: Locale = AppLocale.format,
    ): List<String> {
        val born = formatDay(birthDate, locale)
        val died = formatDay(deathDate, locale)
        val place = birthPlace?.takeIf { it.isNotBlank() }
        return listOfNotNull(
            when {
                born != null && place != null -> strings.get(R.string.person_born_in, born, place)
                born != null -> strings.get(R.string.person_born, born)
                place != null -> strings.get(R.string.person_born_place, place)
                else -> null
            },
            died?.let { strings.get(R.string.person_died, it) },
        )
    }

    private fun formatDay(rfc3339: String?, locale: Locale): String? =
        rfc3339?.take(10)?.let { runCatching { LocalDate.parse(it).format(mediumDateFormatter(locale)) }.getOrNull() }
}
