package tv.jellybeam.nav

import tv.jellybeam.ui.cards.testCard
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

class ScreenForLibraryCardTest {

    @Test
    fun `a ChannelFolderItem card opens a nested CHANNEL_FOLDER Library screen keyed by its own id and name`() {
        val card = testCard(id = "folder-1", itemType = "ChannelFolderItem", name = "Recorded Channel")

        val screen = screenForLibraryCard(card)

        assertEquals(
            Screen.Library(ViewSnapshot(id = "folder-1", name = "Recorded Channel", kind = ViewKind.CHANNEL_FOLDER)),
            screen,
        )
    }

    @Test
    fun `every other item type opens Detail with the card unchanged`() {
        val card = testCard(id = "movie-1", itemType = "Movie", name = "A Movie")

        val screen = screenForLibraryCard(card)

        assertEquals(Screen.Detail(card), screen)
    }
}
