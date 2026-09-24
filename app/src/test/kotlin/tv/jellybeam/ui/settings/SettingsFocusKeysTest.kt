package tv.jellybeam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure key-scheme helpers behind docs/15-focus-and-selection.md §5's Settings key namespace
 * ([railFocusKey]/[isPaneKey]/[combineChipKey]) plus §3's per-section pane-memory
 * flatten/unflatten round-trip, extracted from Compose state so they're JVM-testable.
 */
class SettingsFocusKeysTest {

    // -- railFocusKey / isPaneKey ---------------------------------------

    @Test
    fun `railFocusKey namespaces a section under rail colon`() {
        assertEquals("rail:HOME", railFocusKey(SettingsSection.HOME))
        assertEquals("rail:DISCOVER", railFocusKey(SettingsSection.DISCOVER))
    }

    @Test
    fun `isPaneKey is false for a rail key`() {
        assertFalse(isPaneKey(railFocusKey(SettingsSection.PLAYBACK)))
    }

    @Test
    fun `isPaneKey is false for null`() {
        assertFalse(isPaneKey(null))
    }

    @Test
    fun `isPaneKey is true for an ordinary section row rowId key`() {
        assertTrue(isPaneKey("playback/autoplay_enabled"))
        assertTrue(isPaneKey("playback/still_watching_mode/after_episodes"))
    }

    // -- combineChipKey ---------------------------------------------------

    @Test
    fun `combineChipKey prefixes with the row key when one is provided`() {
        assertEquals("playback/skip_back/10", combineChipKey("playback/skip_back", "10"))
    }

    @Test
    fun `combineChipKey passes the chip key through unchanged with no row key`() {
        assertEquals("discover/connect", combineChipKey(null, "discover/connect"))
    }

    // -- flattenPaneLastKey / unflattenPaneLastKey ------------------------

    @Test
    fun `flatten then unflatten round-trips a per-section map`() {
        val original = mapOf(
            "HOME" to "home/cutoff",
            "PLAYBACK" to "playback/skip_back/10",
        )

        val flat = flattenPaneLastKey(original)
        assertEquals(unflattenPaneLastKey(flat), original)
    }

    @Test
    fun `flatten emits two entries per mapping, section then key`() {
        val flat = flattenPaneLastKey(mapOf("OSD" to "osd/osd_detail/full"))
        assertEquals(listOf("OSD", "osd/osd_detail/full"), flat)
    }

    @Test
    fun `unflatten of an empty list is an empty map`() {
        assertEquals(emptyMap<String, String>(), unflattenPaneLastKey(emptyList()))
    }
}
