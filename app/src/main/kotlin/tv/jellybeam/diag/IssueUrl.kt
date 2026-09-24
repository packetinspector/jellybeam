package tv.jellybeam.diag

import java.net.URLEncoder

/** docs/21 §7: the prefilled GitHub issue-form URL. The summary is the only sizeable content in
 * the query string, so the URL stays well under GitHub's length limits by construction.
 */
object IssueUrl {
    /** The public repository whose issue form receives reports; the one place it is named. */
    const val REPO = "packetinspector/jellybeam"

    fun build(snapshot: ReportSnapshot): String {
        val summaryMarkdown = snapshot.summary.joinToString("\n") { (k, v) -> "- **$k:** $v" }
        val query = listOf(
            "template" to "tv-bug.yml",
            "title" to "[TV] ",
            "category" to snapshot.category,
            "summary" to summaryMarkdown,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "https://github.com/$REPO/issues/new?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
