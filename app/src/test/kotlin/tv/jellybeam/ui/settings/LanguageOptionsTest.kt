package tv.jellybeam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the one language option list behind the Audio and Subtitle language rows (docs/09). */
class LanguageOptionsTest {

    @Test
    fun `Any comes first and every code is unique`() {
        val options = languageOptions(selected = null)

        assertNull(options.first())
        assertEquals(options.size, options.toSet().size)
        assertTrue("eng" in options && "fre" in options && "jpn" in options)
    }

    @Test
    fun `a selected code outside the list is appended so the selection stays visible`() {
        val options = languageOptions(selected = "xyz")

        assertEquals("xyz", options.last())
        assertEquals(languageOptions(selected = null) + "xyz", options)
    }

    @Test
    fun `a selected code inside the list adds nothing`() {
        assertEquals(languageOptions(selected = null), languageOptions(selected = "ger"))
    }
}
