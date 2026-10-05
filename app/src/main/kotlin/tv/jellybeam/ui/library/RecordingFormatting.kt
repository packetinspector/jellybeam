package tv.jellybeam.ui.library

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.mediumDateFormatter

/** Pure Kotlin date/time formatting for [ChannelFolderList]'s recording rows, plain-JVM-testable
 * like [tv.jellybeam.ui.cards.CardFormatting]. [Card.premiereDate] is the recording's start instant,
 * UTC RFC3339; every formatter converts it to the viewer's local [ZoneId] first.
 */
object RecordingFormatting {

    private fun formatter(pattern: String, locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale)

    /**
     * A recording row's date line: `"EEE, MMM d"` when [premiereDate]'s local date falls in
     * [today]'s
     * year, else `"MMM d, yyyy"` so a prior-year recording doesn't read as this year's. `null` for
     * a
     * missing or unparseable [premiereDate] -- never throws.
     */
    fun dateLine(premiereDate: String?, zone: ZoneId, today: LocalDate, locale: Locale = AppLocale.format): String? {
        val localDate = localDateOf(premiereDate, zone) ?: return null
        return if (localDate.year == today.year) {
            formatter("EEE, MMM d", locale).format(localDate)
        } else {
            mediumDateFormatter(locale).format(localDate)
        }
    }

    /** A recording row's time line: `"h:mm a"`, or `"HH:mm"` when [use24h]. `null` for a missing or
     * unparseable [premiereDate] -- never throws.
     */
    fun timeLine(premiereDate: String?, zone: ZoneId, use24h: Boolean, locale: Locale = AppLocale.format): String? {
        val local = zonedDateTimeOf(premiereDate, zone) ?: return null
        return formatter(if (use24h) "HH:mm" else "h:mm a", locale).format(local)
    }

    private fun localDateOf(premiereDate: String?, zone: ZoneId): LocalDate? =
        zonedDateTimeOf(premiereDate, zone)?.toLocalDate()

    private fun zonedDateTimeOf(premiereDate: String?, zone: ZoneId) =
        premiereDate?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.atZoneSameInstant(zone)
}
