package tv.jellybeam.ui.home.classic

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/07 §1: progressive shelf mounting's pure helpers, plain-JVM-testable per [ShelfAssembly.kt]. */
class ShelfAssemblyTest {

    @Test
    fun `initial mount count is two, or fewer if the list is shorter`() {
        assertEquals(0, initialMountCount(0))
        assertEquals(1, initialMountCount(1))
        assertEquals(2, initialMountCount(2))
        assertEquals(2, initialMountCount(6))
    }

    @Test
    fun `mounted count starts at the initial seed with no growth yet`() {
        assertEquals(2, mountedShelfCount(grown = 0, shelfCount = 6))
        assertEquals(1, mountedShelfCount(grown = 0, shelfCount = 1))
    }

    @Test
    fun `mounted count grows by one per call`() {
        assertEquals(3, mountedShelfCount(grown = 3, shelfCount = 6))
        assertEquals(4, mountedShelfCount(grown = 4, shelfCount = 6))
    }

    @Test
    fun `mounted count jumps to all shelves on demand`() {
        assertEquals(6, mountedShelfCount(grown = 6, shelfCount = 6))
    }

    @Test
    fun `mounted count never exceeds what the list currently holds`() {
        assertEquals(3, mountedShelfCount(grown = 6, shelfCount = 3))
    }

    @Test
    fun `mounted count resumes growth once a shrunk list grows back`() {
        // A shorter list clamps the visible count without discarding `grown`'s own progress.
        val grown = 5
        assertEquals(3, mountedShelfCount(grown = grown, shelfCount = 3))
        assertEquals(5, mountedShelfCount(grown = grown, shelfCount = 6))
    }

    @Test
    fun `Play and Play-Pause on a focused album play it on the first press`() {
        assertEquals(AlbumKey.PLAY, albumPlayKey(Key.MediaPlay, KeyEventType.KeyDown, repeatCount = 0))
        assertEquals(AlbumKey.PLAY, albumPlayKey(Key.MediaPlayPause, KeyEventType.KeyDown, repeatCount = 0))
    }

    @Test
    fun `a held Play and its release are consumed without playing again`() {
        assertEquals(AlbumKey.CONSUME, albumPlayKey(Key.MediaPlay, KeyEventType.KeyDown, repeatCount = 1))
        assertEquals(AlbumKey.CONSUME, albumPlayKey(Key.MediaPlay, KeyEventType.KeyUp, repeatCount = 0))
        assertEquals(AlbumKey.CONSUME, albumPlayKey(Key.MediaPlayPause, KeyEventType.KeyUp, repeatCount = 0))
    }

    @Test
    fun `other keys pass to the card and the media session`() {
        assertEquals(AlbumKey.PASS, albumPlayKey(Key.DirectionCenter, KeyEventType.KeyDown, repeatCount = 0))
        assertEquals(AlbumKey.PASS, albumPlayKey(Key.MediaPause, KeyEventType.KeyDown, repeatCount = 0))
        assertEquals(AlbumKey.PASS, albumPlayKey(Key.MediaNext, KeyEventType.KeyDown, repeatCount = 0))
    }
}
