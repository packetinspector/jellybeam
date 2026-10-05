package tv.jellybeam.i18n

import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.R

class I18nTest {
    @Test
    fun `text stays US English until the device language ships`() {
        val shipped = setOf("en")
        assertEquals(Locale.US, AppLocale.textLocale(Locale.forLanguageTag("es-MX"), shipped))
        assertEquals(Locale.UK, AppLocale.textLocale(Locale.UK, shipped))
        assertEquals(Locale.forLanguageTag("es-MX"), AppLocale.textLocale(Locale.forLanguageTag("es-MX"), shipped + "es"))
    }

    @Test
    fun `resource strings read back the way Android returns them`() {
        val strings = ResourceUiStrings.default
        assertEquals("Discover isn't answering right now", strings.get(R.string.search_discover_unavailable))
        assertEquals("1 collection", strings.plural(R.plurals.detail_menu_collections_count, 1, 1))
        assertEquals("3 collections", strings.plural(R.plurals.detail_menu_collections_count, 3, 3))
    }

    /** docs/27 §4: a translation must carry exactly the English placeholders, or it crashes or lies. */
    @Test
    fun `every translation keeps the English placeholders`() {
        val problems = translationProblems(File("src/main/res"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the placeholder check accepts reordering and catches a lost count`() {
        val res = Files.createTempDirectory("res").toFile()
        try {
            write(res, "values", """<string name="a">%s left</string><string name="b">%1${'$'}s of %2${'$'}d</string>
                <plurals name="p"><item quantity="one">%d day</item><item quantity="other">%d days</item></plurals>""")
            write(res, "values-xx", """<string name="a">quedan %1${'$'}s</string><string name="b">%2${'$'}d: %1${'$'}s</string>
                <plurals name="p"><item quantity="one">un día</item><item quantity="other">días</item></plurals>""")
            assertEquals(listOf("values-xx/p[other]: [], English has [%1${'$'}d]"), translationProblems(res))
        } finally {
            res.deleteRecursively()
        }
    }

    private fun write(res: File, dir: String, body: String) {
        File(res, dir).apply { mkdirs() }.resolve("strings.xml").writeText("<resources>$body</resources>")
    }

    private fun translationProblems(res: File): List<String> {
        val base = ResourceFile.parseDir(File(res, "values"))
        val translations = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        val problems = translations.flatMap { file ->
            val translated = ResourceFile.parseDir(file)
            val strings = translated.strings.mapNotNull { (name, text) ->
                val english = base.strings[name] ?: return@mapNotNull "${file.name}/$name: not in English"
                mismatch(file, name, english, text)
            }
            val plurals = translated.plurals.flatMap { (name, items) ->
                val english = base.plurals[name]?.get("other") ?: return@flatMap listOf("${file.name}/$name: not in English")
                items.mapNotNull { (quantity, text) -> mismatch(file, "$name[$quantity]", english, text, mayDropCount = quantity != "other") }
            }
            strings + plurals
        }
        return problems
    }

    /** Multi-argument English strings number their placeholders, so a translation can reorder them. */
    @Test
    fun `english strings with several arguments number them`() {
        val base = ResourceFile.parseDir(File("src/main/res/values"))
        val all = base.strings + base.plurals.flatMap { (n, items) -> items.map { (q, t) -> "$n[$q]" to t } }
        val unnumbered = all.filter { (_, text) -> PLACEHOLDER.findAll(text.replace("%%", "")).count() > 1 && UNNUMBERED.containsMatchIn(text) }.keys
        assertTrue("unnumbered placeholders: $unnumbered", unnumbered.isEmpty())
    }

    private fun mismatch(file: File, name: String, english: String, text: String, mayDropCount: Boolean = false): String? {
        // A plural's "one"/"two"/... may spell the number out; "other" always shows it.
        val expected = placeholders(english)
        val actual = placeholders(text)
        val ok = actual == expected || (mayDropCount && expected.containsAll(actual))
        return if (ok) null else "${file.name}/$name: $actual, English has $expected"
    }

    /** Each placeholder as `%N$x`, so `%s` and `%1$s` compare equal; order-independent. */
    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).mapIndexed { i, m ->
            "%" + m.groupValues[1].ifEmpty { "${i + 1}$" } + m.groupValues[2]
        }.sorted().toList()

    private companion object {
        val PLACEHOLDER = Regex("%(\\d+\\$)?([-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])")
        val UNNUMBERED = Regex("%(?!\\d+\\$)[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
    }
}
