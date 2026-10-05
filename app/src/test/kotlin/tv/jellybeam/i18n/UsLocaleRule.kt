package tv.jellybeam.i18n

import java.util.Locale
import org.junit.rules.ExternalResource

/** Formatters default to the device locale; this pins it to US English for one test and restores it. */
class UsLocaleRule : ExternalResource() {
    private lateinit var previous: Locale

    override fun before() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    override fun after() = Locale.setDefault(previous)
}
