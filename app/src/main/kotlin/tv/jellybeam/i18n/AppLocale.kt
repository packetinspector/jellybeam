package tv.jellybeam.i18n

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import tv.jellybeam.BuildConfig

/**
 * docs/27 §2: text is in a shipped language (US English until another ships), while dates and
 * numbers follow the device locale.
 */
object AppLocale {
    private val shipped: Set<String> = BuildConfig.SHIPPED_LANGUAGES.split(',').toSet()

    /** The language UI text is in; casing must use it, since it's that text being cased. */
    val text: Locale get() = textLocale(Locale.getDefault(), shipped)

    /** Dates, times and numbers. */
    val format: Locale get() = Locale.getDefault()

    /** The device locale when its language ships, else US English, the untranslated text's own. */
    fun textLocale(device: Locale, shipped: Set<String>): Locale =
        if (device.language in shipped) device else Locale.US
}

/** A full date ("Oct 5, 2026", "5 Oct 2026") in [locale]'s own field order. */
fun mediumDateFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

/** All-caps UI text, cased by the rules of the language it's written in. */
fun String.uppercaseUi(): String = uppercase(AppLocale.text)
