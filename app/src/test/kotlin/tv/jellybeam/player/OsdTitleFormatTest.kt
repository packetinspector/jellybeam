package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies CLAUDE.md's hard rule that server-configured names stay verbatim. */
class OsdTitleFormatTest {

    @Test
    fun `a trailing bracketed group is preserved`() {
        assertEquals(
            "Synthetic Episode Title [Grp]",
            OsdTitleFormat.displayTitle("Synthetic Episode Title [Grp]"),
        )
    }

    @Test
    fun `bracketed words resolution tokens spacing and punctuation are unchanged`() {
        val raw = "  Show [Extended] Cut — 1080p  "
        assertEquals(raw, OsdTitleFormat.displayTitle(raw))
    }
}
