package tv.jellybeam.i18n

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import tv.jellybeam.R

/**
 * docs/27 §3: [UiStrings] read straight from a res/values folder, so JVM tests assert the text
 * the app really ships, formatted as US English (English plural rules, Locale.US numbers).
 */
class ResourceUiStrings(valuesDir: File = File("src/main/res/values")) : UiStrings {
    private val file = ResourceFile.parseDir(valuesDir)

    override fun get(id: Int, vararg args: Any): String =
        format(file.strings.getValue(name(R.string::class.java, id)), args)

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val items = file.plurals.getValue(name(R.plurals::class.java, id))
        val quantity = if (count == 1) "one" else "other"
        return format(items[quantity] ?: items.getValue("other"), args)
    }

    private fun format(template: String, args: Array<out Any>): String =
        if (args.isEmpty()) template else String.format(Locale.US, template, *args)

    private fun name(type: Class<*>, id: Int): String =
        type.fields.first { it.getInt(null) == id }.name

    companion object {
        /** Shared so the dozens of formatter tests parse strings.xml once. */
        val default: ResourceUiStrings by lazy { ResourceUiStrings() }
    }
}

/** Parsed strings*.xml: Android's escaping undone, as `Resources.getString` returns it. */
class ResourceFile(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>) {
    companion object {
        /** Every strings*.xml in one res/values* folder, merged as aapt2 merges them. */
        fun parseDir(dir: File): ResourceFile {
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
                .sortedBy { it.name }.map(::parse)
            return ResourceFile(files.flatMap { it.strings.entries }.associate { it.toPair() }, files.flatMap { it.plurals.entries }.associate { it.toPair() })
        }

        fun parse(file: File): ResourceFile {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val strings = root.children("string").associate { it.getAttribute("name") to unescape(it.textContent) }
            val plurals = root.children("plurals").associate { plural ->
                plural.getAttribute("name") to plural.children("item")
                    .associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
            return ResourceFile(strings, plurals)
        }

        private fun Element.children(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode == this }
        }

        /** aapt2's rules: quoted runs keep whitespace, other whitespace collapses, `\x` escapes. */
        private fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && raw.startsWith("u", i + 1) && i + 6 <= raw.length -> {
                        out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 5
                    }
                    c == '\\' && i + 1 < raw.length -> {
                        out.append(
                            when (val next = raw[i + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> next
                            },
                        )
                        i++
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> if (out.isEmpty() || out.last() != ' ') out.append(' ')
                    else -> out.append(c)
                }
                i++
            }
            return if (raw.contains('"')) out.toString() else out.toString().trim()
        }
    }
}
