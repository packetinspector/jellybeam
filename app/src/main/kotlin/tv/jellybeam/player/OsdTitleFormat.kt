package tv.jellybeam.player

/** OSD title policy: server-configured names are shown verbatim, never guessed at or stripped
 * (CLAUDE.md).
 */
object OsdTitleFormat {
    fun displayTitle(raw: String): String = raw
}
