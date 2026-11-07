package tv.jellybeam.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.SongSource

private val LIST_WIDTH = 848.dp

/**
 * docs/29 §9 D (D1/D1b): one list for every song source; only the right-hand columns change.
 * Play and Shuffle sit top right; Up from the first row reaches them.
 */
@Composable
internal fun MusicSongsScreen(
    source: SongSource,
    title: String,
    isTop: Boolean,
    nav: MusicNav,
    viewModel: MusicSongsViewModel = viewModel(factory = MusicSongsViewModel.Factory(AppGraph.gateway, source)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    // docs/29 §9 I: a genre's list seeds a mix from its header.
    val genreSeed = (source as? SongSource.Genre)?.let { MixSeedFfi.Genre(it.genre) }
    val mix = rememberInstantMix(genreSeed, isTop)
    ActiveWhileTop(isTop, viewModel::setActive)
    val strings = rememberUiStrings()
    val playing = rememberPlayingTrack()
    val memory = rememberFocusMemory(songSourceKey(source))
    val playRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val thumbUrl = rememberMusicImageUrl(32.dp)
    val columns = remember(source) { songColumns(source) }
    val queue = MusicSource.Songs(source)
    memory.scrollTo = { key ->
        val index = state.songs.indexOfFirst { itemKey(it.id) == key }
        if (index >= 0) listState.scrollToItem(index)
        index >= 0
    }
    val firstKey = state.songs.firstOrNull()?.let { itemKey(it.id) }
    FocusRestorer(
        memory,
        isTop,
        ready = state.load != MusicLoad.Loading,
        fallback = { memory.target(KEY_RETRY_PAGE) ?: firstKey?.let(memory::target) ?: playRequester.asFocusTarget() },
        tag = "music-songs",
    )
    PagedList(listState, state.songs.size, viewModel::loadMore)

    Box(Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        Column(Modifier.padding(start = MUSIC_PAGE_START, top = 42.dp).width(LIST_WIDTH)) {
            MonoLine(stringResource(R.string.music_title), LocalAccent.current.accentInk)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    PageTitle(title, 28)
                    Spacer(Modifier.height(6.dp))
                    if (state.load == MusicLoad.Ready) BasicText(songStrip(source, state.count, strings), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 11))
                }
                if (state.load == MusicLoad.Ready && state.songs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MusicPill(stringResource(R.string.music_play), PlayGlyph, primary = true, onClick = { nav.play(queue, 0) }, modifier = Modifier.focusRequester(playRequester))
                        MusicPill(stringResource(R.string.music_shuffle), ShuffleGlyph, primary = false, onClick = { nav.shuffle(queue) })
                        if (genreSeed != null) {
                            MusicPill(
                                stringResource(R.string.music_instant_mix),
                                MixGlyph,
                                primary = false,
                                onClick = { nav.instantMix(genreSeed) },
                                loading = mix == MixShown.Building,
                            )
                        }
                    }
                }
            }
            MixLine(mix, buildingLine = false, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            when (val load = state.load) {
                MusicLoad.Loading -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(skeletonRowCount(null)) {
                        Row(Modifier.height(52.dp).padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                            MusicSkeleton(32.dp, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            MusicSkeleton(240.dp, 14.dp)
                        }
                    }
                }
                is MusicLoad.Failed -> MusicUnreachable(load.host, lastTriedLine(load.atMs), viewModel::retry, Modifier.focusKey(memory, KEY_RETRY_PAGE), Modifier.padding(top = 60.dp))
                MusicLoad.Ready -> if (state.songs.isEmpty()) {
                    MusicEmptyState(stringResource(songListEmptyLine(source)), detail = null, modifier = Modifier.padding(top = 30.dp))
                } else {
                    FadeInBlock {
                        Column {
                            SongListHeader(columns)
                            LazyColumn(state = listState, contentPadding = PaddingValues(top = RING_CLEAR_TOP, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                itemsIndexed(state.songs, key = { _, c -> c.id }, contentType = { _, _ -> "song" }) { index, card ->
                                    TrackRow(
                                        data = songRowData(card, columns, strings, System.currentTimeMillis(), deviceZone()),
                                        layout = TrackRowLayout.TwoLine,
                                        mark = playing.markFor(card.id),
                                        imageUrl = thumbUrl,
                                        onClick = { nav.play(queue, index) },
                                        onMore = { menuModel.open(MusicMenuTarget(card, MusicMenuKind.Song), itemKey(card.id)) },
                                        modifier = Modifier.focusKey(memory, itemKey(card.id)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        MusicActionPanel(menu, menuModel.controls(), nav, isTop, memory, Modifier.align(Alignment.TopEnd))
    }
}

/** `48 SONGS │ LAST PLAYED ↓`: the count, then the source's own order. */
private fun songStrip(source: SongSource, count: Long, strings: UiStrings): String {
    val counted = strings.plural(R.plurals.music_count_songs, count.toInt(), groupedCount(count))
    val order = when (source) {
        is SongSource.RecentlyPlayed -> R.string.music_sort_last_played
        is SongSource.Favorites -> R.string.music_sort_date_added
        is SongSource.All, is SongSource.Genre, is SongSource.Artist -> R.string.music_sort_name
    }
    return if (count == 0L) counted.uppercaseUi() else strings.get(R.string.music_sort_strip, counted, strings.get(order)).uppercaseUi()
}

/** docs/29 §9 D1's mono column header over a hairline. */
@Composable
private fun SongListHeader(columns: SongColumns) {
    val label = if (columns.line2 == SongLine2.AlbumOnly) R.string.music_col_title_album else R.string.music_col_title_artist_album
    val extra = when (columns.extra) {
        SongExtra.Added -> R.string.music_col_added
        SongExtra.Played -> R.string.music_col_played
        SongExtra.Year -> R.string.music_col_year
        null -> null
    }
    Row(Modifier.fillMaxWidth().height(18.dp).padding(start = 74.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLine(stringResource(label), JellybeamTheme.Grigio, 9, Modifier.weight(1f))
        if (extra != null) MonoLine(stringResource(extra), JellybeamTheme.Grigio, 9, Modifier.padding(end = if (columns.plays) 12.dp else 66.dp))
        if (columns.plays) MonoLine(stringResource(R.string.music_col_plays), JellybeamTheme.Grigio, 9, Modifier.padding(end = 66.dp).width(PLAYS_WIDTH))
        MonoLine(stringResource(R.string.music_col_time), JellybeamTheme.Grigio, 9, Modifier.width(55.dp))
    }
    Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(JellybeamTheme.Hairline))
}
