package tv.jellybeam.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.SongSource
import androidx.compose.ui.focus.focusRequester

private val NAME_WIDTH = 410.dp
private val WIDE_NAME_WIDTH = 820.dp
private val RIGHT_COLUMN_START = 490.dp
private val RIGHT_COLUMN_WIDTH = 438.dp

/**
 * docs/29 §9 B (B1–B3): an artist is a name, a few numbers and the albums. Focus opens on Play all;
 * Right from the actions reaches Most played, Down the Albums shelf. Empty shelves are absent.
 */
@Composable
internal fun MusicArtistScreen(
    artistId: String,
    isTop: Boolean,
    nav: MusicNav,
    viewModel: MusicArtistViewModel = viewModel(factory = MusicArtistViewModel.Factory(AppGraph.gateway, artistId)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    val mix = rememberInstantMix(MixSeedFfi.Artist(artistId), isTop)
    val openSong: (Card, String) -> Unit = { card, key -> menuModel.open(MusicMenuTarget(card, MusicMenuKind.Song), key) }
    ActiveWhileTop(isTop, viewModel::setActive)
    val strings = rememberUiStrings()
    val playing = rememberPlayingTrack()
    val memory = rememberFocusMemory(artistId)
    val playRequester = remember { FocusRequester() }
    val squareUrl = rememberMusicImageUrl(SQUARE_ART)
    val thumbUrl = rememberMusicImageUrl(32.dp)
    FocusRestorer(memory, isTop, ready = state.load != MusicLoad.Loading, fallback = { memory.target(KEY_RETRY_PAGE) ?: playRequester.asFocusTarget() }, tag = "music-artist")

    Box(Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        val artist = state.artist
        when (val load = state.load) {
            MusicLoad.Loading -> ArtistSkeleton()
            is MusicLoad.Failed -> MusicUnreachable(load.host, lastTriedLine(load.atMs), viewModel::retry, Modifier.focusKey(memory, KEY_RETRY_PAGE), Modifier.padding(top = 180.dp))
            MusicLoad.Ready -> if (artist != null) FadeInBlock {
                val performerOnly = state.albums.isEmpty() && state.songs.isNotEmpty()
                val backdrop = state.card?.backdropTag?.let { ArtSource.Own(artistId, it, ImageKind.BACKDROP) }
                Box(Modifier.fillMaxSize()) {
                    // docs/29 §9 B3: the 16:9 backdrop is a hard-edged panel in the top-right corner (840×472 px), clear of the action row.
                    if (backdrop != null) {
                        CardArtImage(
                            source = backdrop,
                            imageUrl = rememberMusicImageUrl(420.dp),
                            itemName = artist.name,
                            contentAlpha = 1f,
                            aspect = 16f / 9f,
                            modifier = Modifier.align(Alignment.TopEnd).size(420.dp, 236.dp),
                        )
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = MUSIC_PAGE_START, top = 40.dp, bottom = 24.dp)) {
                        if (artist.primaryTag != null) {
                            SquareArt(artistArt(artist), squareUrl, artist.name, Modifier.size(100.dp).clip(CircleShape))
                            Spacer(Modifier.height(18.dp))
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                        MonoLine(stringResource(R.string.music_eyebrow_artist), LocalAccent.current.accentInk)
                        Spacer(Modifier.height(8.dp))
                        PageTitle(artist.name, 34, Modifier.width(if (performerOnly || (state.mostPlayed.isEmpty() && backdrop == null)) WIDE_NAME_WIDTH else NAME_WIDTH))
                        Spacer(Modifier.height(8.dp))
                        BasicText(artistHeaderLine(artist, strings), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 11))
                        Spacer(Modifier.height(20.dp))
                        MusicActionRow(
                            PageActions(
                                playLabel = stringResource(R.string.music_play_all),
                                isFavorite = state.isFavorite,
                                onPlay = { nav.play(MusicSource.Artist(artistId), 0) },
                                onShuffle = { nav.shuffle(MusicSource.Artist(artistId)) },
                                onInstantMix = { nav.instantMix(MixSeedFfi.Artist(artistId)) },
                                onFavorite = viewModel::toggleFavorite,
                                onMore = { state.card?.let { menuModel.open(MusicMenuTarget(it, MusicMenuKind.Artist), KEY_PAGE_MORE) } },
                                mix = mix,
                                buildingLine = true,
                            ),
                            playModifier = Modifier.focusRequester(playRequester),
                            moreModifier = Modifier.focusKey(memory, KEY_PAGE_MORE),
                        )
                        Spacer(Modifier.height(28.dp))
                        if (performerOnly) {
                            Row(horizontalArrangement = Arrangement.spacedBy(70.dp)) {
                                ArtistSongs(state.songs, artistId, memory, playing, thumbUrl, strings, nav, openSong, viewModel::loadMoreSongs, Modifier.width(550.dp))
                                if (state.appearsOn.isNotEmpty()) Shelf(stringResource(R.string.music_appears_on), state.appearsOn, memory, squareUrl, strings, nav, byYear = false)
                            }
                        } else {
                            if (state.albums.isNotEmpty()) Shelf(stringResource(R.string.music_tab_albums), state.albums, memory, squareUrl, strings, nav, byYear = true)
                            if (state.appearsOn.isNotEmpty()) {
                                Spacer(Modifier.height(24.dp))
                                Shelf(stringResource(R.string.music_appears_on), state.appearsOn, memory, squareUrl, strings, nav, byYear = false)
                            }
                        }
                    }
                    if (state.mostPlayed.isNotEmpty() && !performerOnly) {
                        MostPlayed(
                            songs = state.mostPlayed,
                            artistId = artistId,
                            memory = memory,
                            playing = playing,
                            thumbUrl = thumbUrl,
                            strings = strings,
                            nav = nav,
                            onMore = openSong,
                            modifier = Modifier.padding(start = RIGHT_COLUMN_START, top = if (backdrop != null) 290.dp else 56.dp).width(RIGHT_COLUMN_WIDTH),
                        )
                    }
                }
            }
        }
        MusicActionPanel(
            state = menu,
            controls = menuModel.controls(),
            nav = nav,
            isTop = isTop,
            memory = memory,
            modifier = Modifier.align(Alignment.TopEnd),
            pageFavorite = PageFavorite(artistId, state.isFavorite, viewModel::toggleFavorite),
        )
    }
}

/** docs/29 §9 B1: the five most-played songs, two-line with the album as line 2; each plays as a single. */
@Composable
private fun MostPlayed(
    songs: List<Card>,
    artistId: String,
    memory: FocusMemory,
    playing: PlayingTrack?,
    thumbUrl: MusicImageUrl,
    strings: UiStrings,
    nav: MusicNav,
    onMore: (Card, String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        MonoLine(stringResource(R.string.music_most_played), JellybeamTheme.Grigio, 10, Modifier.padding(start = 10.dp, bottom = 8.dp))
        songs.forEachIndexed { index, card ->
            TrackRow(
                data = TrackRowData(
                    title = card.name,
                    duration = trackDurationLabel(card),
                    number = (index + 1).toString(),
                    line2 = card.album,
                    extra = mostPlayedCount(card, strings),
                    hasLyrics = card.hasLyrics,
                    isFavorite = card.isFavorite,
                ),
                layout = TrackRowLayout.TwoLine,
                mark = playing.markFor(card.id),
                imageUrl = thumbUrl,
                onClick = { nav.play(MusicSource.Single(card), 0) },
                onMore = { onMore(card, "most:${card.id}") },
                modifier = Modifier.focusKey(memory, "most:${card.id}"),
            )
        }
    }
}

/** docs/29 §9 B2: a per-track performer's songs (ArtistIds), played as the artist's queue. */
@Composable
private fun ArtistSongs(
    songs: List<Card>,
    artistId: String,
    memory: FocusMemory,
    playing: PlayingTrack?,
    thumbUrl: MusicImageUrl,
    strings: UiStrings,
    nav: MusicNav,
    onMore: (Card, String) -> Unit,
    loadMore: () -> Unit,
    modifier: Modifier,
) {
    val columns = songColumns(SongSource.Artist(artistId, GridSort(GridSortField.YEAR, descending = false)))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MonoLine(stringResource(R.string.music_tab_songs), JellybeamTheme.Grigio, 10, Modifier.padding(start = 10.dp, bottom = 8.dp))
        songs.forEachIndexed { index, card ->
            TrackRow(
                data = songRowData(card, columns, strings, System.currentTimeMillis(), deviceZone(), number = (index + 1).toString()),
                layout = TrackRowLayout.OneLine,
                mark = playing.markFor(card.id),
                imageUrl = thumbUrl,
                onClick = { nav.play(MusicSource.Artist(artistId), index) },
                onMore = { onMore(card, itemKey(card.id)) },
                // The next page loads as focus nears the last rows, as a paged list's scroll does.
                modifier = Modifier.focusKey(memory, itemKey(card.id)).onFocusChanged { if (it.isFocused && index >= songs.size - SONGS_AHEAD) loadMore() },
            )
        }
    }
}

/** Rows from the end at which the next page of a performer's songs is asked for. */
private const val SONGS_AHEAD = 12

/** docs/29 §9 B: a titled shelf of square album cards with its count; OK opens the album. */
@Composable
private fun Shelf(title: String, albums: List<Card>, memory: FocusMemory, imageUrl: MusicImageUrl, strings: UiStrings, nav: MusicNav, byYear: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(title, style = archivo(JellybeamTheme.Panna, 20.sp, FontWeight.SemiBold))
            BasicText(albums.size.toString(), style = archivo(JellybeamTheme.Grigio, 13.sp, FontWeight.Normal))
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(end = MUSIC_PAGE_START), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(albums, key = { it.id }, contentType = { "album" }) { card ->
                SquareCard(
                    title = card.name,
                    subline = if (byYear) card.productionYear?.toString() else albumSubline(card, strings),
                    art = albumArt(card),
                    imageUrl = imageUrl,
                    onClick = { nav.openAlbum(card.id) },
                    modifier = Modifier.focusKey(memory, itemKey(card.id)),
                )
            }
        }
    }
}

/** docs/29 §10 S1 for the artist page: name, line, actions and one shelf at their final sizes. */
@Composable
private fun ArtistSkeleton() {
    Column(Modifier.padding(start = MUSIC_PAGE_START, top = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicSkeleton(60.dp, 11.dp)
        MusicSkeleton(300.dp, 34.dp, radius = 3.dp)
        MusicSkeleton(200.dp, 11.dp)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MusicSkeleton(110.dp, 36.dp, radius = 18.dp)
            MusicSkeleton(110.dp, 36.dp, radius = 18.dp)
            MusicSkeleton(139.dp, 36.dp, radius = 18.dp)
            MusicSkeleton(36.dp, 36.dp, radius = 18.dp)
            MusicSkeleton(36.dp, 36.dp, radius = 18.dp)
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(4) { MusicSkeleton(SQUARE_ART, SQUARE_ART) } }
    }
}
