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

    @Test
    fun `music cards open the music pages`() {
        assertEquals(Screen.MusicAlbum("al-1"), screenForLibraryCard(testCard(id = "al-1", itemType = "MusicAlbum")))
        assertEquals(Screen.MusicArtist("ar-1"), screenForLibraryCard(testCard(id = "ar-1", itemType = "MusicArtist")))
    }

    @Test
    fun `an audio playlist opens the music playlist page, any other playlist Detail`() {
        val audio = testCard(id = "pl-1", itemType = "Playlist").copy(mediaType = "Audio")
        assertEquals(Screen.MusicPlaylist(audio), screenForLibraryCard(audio))
        val video = testCard(id = "pl-2", itemType = "Playlist").copy(mediaType = "Video")
        assertEquals(Screen.Detail(video), screenForLibraryCard(video))
        val unsent = testCard(id = "pl-3", itemType = "Playlist")
        assertEquals(Screen.Detail(unsent), screenForLibraryCard(unsent))
    }

    @Test
    fun `a song opens its album, or Detail when it has none`() {
        val song = testCard(id = "s-1", itemType = "Audio").copy(albumId = "al-1")
        assertEquals(Screen.MusicAlbum("al-1"), screenForLibraryCard(song))
        val loose = testCard(id = "s-2", itemType = "Audio")
        assertEquals(Screen.Detail(loose), screenForLibraryCard(loose))
    }

    @Test
    fun `a music library opens the music landing and every other library the grid`() {
        val music = ViewSnapshot(id = "m", name = "Synthetic Music", kind = ViewKind.LIBRARY, collectionType = "music")
        val films = ViewSnapshot(id = "f", name = "Synthetic Films", kind = ViewKind.LIBRARY, collectionType = "movies")
        assertEquals(Screen.MusicLibrary(music), screenForView(music))
        assertEquals(Screen.Library(films), screenForView(films))
    }
}
