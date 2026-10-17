package tv.jellybeam.ui.library

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.cards.CardFormatting
import uniffi.jellybeam_core.Card

/** docs/16 §4: the library header's readout reads only [Card] fields, so these stay pure and JVM-testable. */

private const val MOVIE_ITEM_TYPE = "Movie"
private const val DAYS_PER_WEEK = 7L
private const val WEEKS_FROM_DAYS = 14L
private const val MONTHS_FROM_DAYS = 63L
private const val YEARS_FROM_MONTHS = 24L

/**
 * LAST PLAYED's value: when playback last started, as [relativeDay]; `Never` without a date, or
 * `—` for a title marked played with none. Jellyfin's PlayCount counts starts, not viewings, so no count is shown.
 */
fun lastPlayedValue(strings: UiStrings, lastPlayedDate: String?, played: Boolean, now: Instant, zone: ZoneId): String =
    relativeDay(strings, lastPlayedDate, now, zone)
        ?: if (played) strings.get(R.string.library_readout_no_date) else strings.get(R.string.library_readout_never)

/** LAST PLAYED's sub: `watched`, `in progress` with a resume point, else none. */
fun lastPlayedSub(strings: UiStrings, played: Boolean, positionTicks: Long): String? = when {
    played -> strings.get(R.string.library_readout_watched_state)
    positionTicks > 0L -> strings.get(R.string.library_readout_in_progress)
    else -> null
}

/** Today / Yesterday / N days / N weeks (from 14 days) / N months (from 63 days) / N years (from 24 months); `null` when unparseable. */
fun relativeDay(strings: UiStrings, rfc3339: String?, now: Instant, zone: ZoneId): String? {
    val added = localDate(rfc3339, zone) ?: return null
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(added, today)
    return when {
        days <= 0L -> strings.get(R.string.library_readout_today)
        days == 1L -> strings.get(R.string.library_readout_yesterday)
        days < WEEKS_FROM_DAYS -> strings.plural(R.plurals.library_readout_days_ago, days.toInt(), days.toInt())
        days < MONTHS_FROM_DAYS -> {
            val weeks = (days / DAYS_PER_WEEK).toInt()
            strings.plural(R.plurals.library_readout_weeks_ago, weeks, weeks)
        }
        else -> {
            val months = ChronoUnit.MONTHS.between(added, today)
            if (months < YEARS_FROM_MONTHS) {
                strings.plural(R.plurals.library_readout_months_ago, months.toInt(), months.toInt())
            } else {
                val years = ChronoUnit.YEARS.between(added, today).toInt()
                strings.plural(R.plurals.library_readout_years_ago, years, years)
            }
        }
    }
}

/** `Mar 6, 2014` in [locale]'s own field order; `null` when unparseable. */
fun addedAbsolute(dateCreated: String?, zone: ZoneId, locale: Locale): String? =
    localDate(dateCreated, zone)?.let { DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(it) }

/** `if started now` for an unstarted title, else `1h 4m left`; `null` without a runtime. */
fun endsSub(strings: UiStrings, runtimeTicks: Long?, positionTicks: Long): String? {
    if (runtimeTicks == null || runtimeTicks <= 0L) return null
    if (positionTicks <= 0L) return strings.get(R.string.library_readout_if_started_now)
    return CardFormatting.remainingLabel(strings, runtimeTicks, positionTicks)
}

/**
 * The card the readout shows: the rested id's current grid entry, only for a Movie. `null` while
 * loading or once the id has left [items] (sort/filter change), so nothing stale is shown.
 */
fun readoutCard(restedId: String?, items: List<Card>, isLoading: Boolean): Card? {
    if (restedId == null || isLoading) return null
    return items.firstOrNull { it.id == restedId }?.takeIf { it.itemType == MOVIE_ITEM_TYPE }
}

private fun localDate(rfc3339: String?, zone: ZoneId): LocalDate? {
    val parsed = try {
        OffsetDateTime.parse(rfc3339 ?: return null)
    } catch (_: DateTimeParseException) {
        return null
    }
    return parsed.atZoneSameInstant(zone).toLocalDate()
}
