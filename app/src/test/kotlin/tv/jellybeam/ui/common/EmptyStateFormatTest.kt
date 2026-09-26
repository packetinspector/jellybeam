package tv.jellybeam.ui.common

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class EmptyStateFormatTest {
    @Test
    fun host_label_keeps_non_default_port_and_drops_default_port() {
        assertEquals("jellyfin.example.test:8096", serverHostLabel("http://jellyfin.example.test:8096/"))
        assertEquals("jellyfin.example.test", serverHostLabel("https://jellyfin.example.test:443"))
        assertEquals("192.0.2.10", serverHostLabel("http://192.0.2.10"))
    }

    @Test
    fun host_label_is_null_without_a_host() {
        assertNull(serverHostLabel(null))
        assertNull(serverHostLabel("not a url"))
    }

    @Test
    fun spec_line_joins_host_libraries_and_items() {
        assertEquals("jellyfin.example.test:8096 │ 3 LIBRARIES │ 0 ITEMS", emptyLibrarySpecLine("jellyfin.example.test:8096", 3, 0))
        assertEquals("0 LIBRARIES │ 0 ITEMS", emptyLibrarySpecLine(null, 0, 0))
        assertEquals("1 LIBRARY │ 1 ITEM", emptyLibrarySpecLine(null, 1, 1))
    }
}
