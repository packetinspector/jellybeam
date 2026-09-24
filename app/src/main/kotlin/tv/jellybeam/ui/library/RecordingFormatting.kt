package tv.jellybeam.ui.library

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure Kotlin date/time formatting for [ChannelFolderList]'s recording rows, plain-JVM-testable
 * like [tv.jellybeam.ui.cards.CardFormatting]. [Card.premiereDate] is the recording's start instant,
 * UTC RFC3339; every formatter converts it to the viewer's local [ZoneId] first.
 */
object RecordingFormatting {

    private val WEEKDAY_MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val MONTH_DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val TIME_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TIME_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * A recording row's date line: `"EEE, MMM d"` when [premiereDate]'s local date falls in
     * [today]'s
     * year, else `"MMM d, yyyy"` so a prior-year recording doesn't read as this year's. `null` for
     * a
     * missing or unparseable [premiereDate] -- never throws.
     */
    fun dateLine(premiereDate: String?, zone: ZoneId, today: LocalDate): String? {
        val localDate = localDateOf(premiereDate, zone) ?: return null
        return if (localDate.year == today.year) {
            WEEKDAY_MONTH_DAY.format(localDate)
        } else {
            MONTH_DAY_YEAR.format(localDate)
        }
    }

    /** A recording row's time line: `"h:mm a"`, or `"HH:mm"` when [use24h]. `null` for a missing or
     * unparseable [premiereDate] -- never throws.
     */
    fun timeLine(premiereDate: String?, zone: ZoneId, use24h: Boolean): String? {
        val local = zonedDateTimeOf(premiereDate, zone) ?: return null
        return if (use24h) TIME_24H.format(local) else TIME_12H.format(local)
    }

    private fun localDateOf(premiereDate: String?, zone: ZoneId): LocalDate? =
        zonedDateTimeOf(premiereDate, zone)?.toLocalDate()

    private fun zonedDateTimeOf(premiereDate: String?, zone: ZoneId) =
        premiereDate?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.atZoneSameInstant(zone)
}
