package tv.jellybeam.ui.settings

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaLibraryInfo
import java.text.NumberFormat
import kotlin.math.roundToInt
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.theme.JellybeamWordmark
import uniffi.jellybeam_core.MirrorItemCounts
import uniffi.jellybeam_core.ServerInfoSnapshot
import uniffi.jellybeam_core.SyncStatus

private val CARD_GAP = 10.dp
private val CARD_CORNER = 4.dp
private val CARD_HPADDING = 15.dp
private val CARD_BOTTOM_PADDING = 13.dp
private val CARD_HEADER_HEIGHT = 31.dp
private val STAT_ROW_HEIGHT = 23.dp
private val TILE_HEIGHT = 65.dp
private val TILE_GAP = 6.dp
private val TILE_HPADDING = 8.dp
private const val TILES_PER_ROW = 3
private val HEADER_SPACER = 19.dp
private val HEADER_MASCOT_HEIGHT = 44.dp
private val HEADER_LOCKUP_GAP = 8.dp

/**
 * The About pane's scroll snap (docs/13-feature-list.md "About section"): [headerBottomPx] is the
 * brand header plus its spacer in scroll-content pixels, 0 until measured; [spec] reads it live.
 */
internal class AboutScrollSnap(private val scrollState: ScrollState) {
    var headerBottomPx by mutableIntStateOf(0)

    /** Trailing space that lifts the scroll range to 0 or at least [headerBottomPx]. */
    var bottomSlackPx by mutableIntStateOf(0)
        private set

    /** The slack as last laid out; [ScrollState.maxValue] includes this, not a pending [bottomSlackPx]. */
    var laidOutSlackPx by mutableIntStateOf(0)

    /** Re-derives the slack from the slack-free range, read against the laid-out slack so it never flips. */
    suspend fun trackBottomSlack() {
        snapshotFlow { aboutBottomSlackPx((scrollState.maxValue - laidOutSlackPx).coerceAtLeast(0), headerBottomPx) }
            .collect { bottomSlackPx = it }
    }

    @OptIn(ExperimentalFoundationApi::class)
    val spec: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val current = scrollState.value
            val defaultTarget = current + super.calculateScrollDistance(offset, size, containerSize).roundToInt()
            return (aboutSnapScrollTarget(current, defaultTarget, headerBottomPx, scrollState.maxValue) - current).toFloat()
        }
    }
}

/** Provides [snap]'s spec to the scrollable beneath it; a null [snap] leaves the ambient spec. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AboutSnapScope(snap: AboutScrollSnap?, content: @Composable () -> Unit) {
    if (snap == null) {
        content()
    } else {
        // verticalScroll reads the spec where it is composed, so this wraps the scrollable itself.
        CompositionLocalProvider(LocalBringIntoViewSpec provides snap.spec, content = content)
    }
}

/**
 * Settings > About (docs/13-feature-list.md "About section"): a centred brand header (local-only,
 * no network, never left half-scrolled: see [AboutScrollSnap]), then the connected server, local
 * mirror and this device as stat cards, backed by [AboutViewModel]. No Refresh control: every entry into the section re-fetches. Reads
 * `PackageInfo` off `PackageManager` rather than `BuildConfig`, since this module's
 * `buildFeatures` never turned `buildConfig` on.
 */
@Composable
internal fun AboutSectionContent(
    viewModel: AboutViewModel = viewModel(factory = AboutViewModelFactory(AppGraph.gateway)),
    scrollSnap: AboutScrollSnap? = null,
    paneTopInset: Dp = 0.dp,
) {
    val state by viewModel.state.collectAsState()
    val snapshot = state.snapshot
    val topInsetPx = with(LocalDensity.current) { paneTopInset.roundToPx() }

    // The ViewModel outlives the section, so its init alone would leave a revisit stale.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(scrollSnap) { scrollSnap?.trackBottomSlack() }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header plus spacer, plus the pane's top padding above them, is what a snap must scroll off.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { scrollSnap?.headerBottomPx = topInsetPx + it.height },
        ) {
            AboutBrandHeader(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(HEADER_SPACER))
        }

        // Nothing below the header until the first snapshot read lands, so the not-signed-in
        // note never flashes on a signed-in account's first frame.
        if (snapshot != null) {
            if (snapshot.serverUrl == null) {
                SettingsNoteRow(stringResource(R.string.settings_about_not_signed_in))
            } else {
                ServerAboutContent(snapshot, state)
            }
        }
        // Without it a range shorter than the header would leave the header half-clipped.
        scrollSnap?.let { snap ->
            Spacer(Modifier.height(with(LocalDensity.current) { snap.bottomSlackPx.toDp() }).onSizeChanged { snap.laidOutSlackPx = it.height })
        }
    }
}

/** Mark beside wordmark, then descriptor, version/build line and the stack-facts pill, all centred. */
@Composable
private fun AboutBrandHeader(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName
    val versionLine = when {
        versionName == null -> null
        else -> stringResource(
            R.string.settings_about_version_build,
            versionName,
            PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
        )
    }
    val facts = stringArrayResource(R.array.settings_about_facts)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HEADER_LOCKUP_GAP)) {
            Image(
                painter = painterResource(R.drawable.jb_mascot_base),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(HEADER_MASCOT_HEIGHT),
            )
            JellybeamWordmark(size = 34.sp)
        }
        BasicText(
            text = stringResource(R.string.settings_about_descriptor),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 12.sp),
        )
        if (versionLine != null) {
            BasicText(
                text = versionLine.uppercaseUi(),
                modifier = Modifier.padding(top = 4.dp),
                style = kickerStyle(JellybeamTheme.Grigio),
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .height(20.dp)
                .border(1.dp, JellybeamTheme.Hairline, CircleShape)
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            facts.forEachIndexed { index, fact ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).height(8.dp).background(JellybeamTheme.Hairline))
                }
                BasicText(
                    text = fact.uppercaseUi(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = kickerStyle(if (index == facts.lastIndex) JellybeamTheme.Pistacchio else JellybeamTheme.Panna2),
                )
            }
        }
    }
}

/**
 * The signed-in cards: Server (with its Library tiles) beside Local Mirror over This Device.
 * Server status and the live-events connection ride in their card's header rather than as rows.
 */
// OptIn: MediaLibraryInfo.VERSION is @UnstableApi (same opt-in the player package takes).
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ServerAboutContent(snapshot: ServerInfoSnapshot, state: AboutUiState) {
    val details = state.details
    val mirror = snapshot.mirror
    val serverUrl = snapshot.serverUrl.orEmpty()
    val unknown = stringResource(R.string.settings_about_unknown)
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val strings = rememberUiStrings()

    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP), verticalAlignment = Alignment.Top) {
        AboutCard(
            key = "about/server",
            title = stringResource(R.string.settings_about_server_group),
            status = {
                // A failed fetch keeps the last good rows, but a stale "Running" would be a claim.
                when {
                    state.detailsFailed -> CardStatus(stringResource(R.string.settings_about_status_unavailable), JellybeamTheme.Grigio)
                    details == null -> Unit
                    details.hasPendingRestart == true -> CardStatus(stringResource(R.string.settings_about_status_restart_pending), JellybeamTheme.Ambra)
                    details.hasUpdateAvailable == true -> CardStatus(stringResource(R.string.settings_about_status_update_available), JellybeamTheme.Ambra)
                    else -> CardStatus(stringResource(R.string.settings_about_status_running), JellybeamTheme.Pistacchio)
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            StatRow(stringResource(R.string.settings_about_row_name), snapshot.serverName ?: AboutFormatting.hostOf(serverUrl) ?: unknown, emphasized = true)
            StatRow(stringResource(R.string.settings_about_row_version), snapshot.serverVersion ?: unknown, emphasized = true)
            if (details != null) {
                val productValue = if (details.systemInfoAvailable) {
                    listOfNotNull(details.productName, details.operatingSystem, details.architecture).filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    stringResource(R.string.settings_about_needs_admin)
                }
                StatRow(stringResource(R.string.settings_about_row_product), productValue)
            }
            StatRow(stringResource(R.string.settings_about_row_address), AboutFormatting.hostOf(serverUrl) ?: unknown)
            StatRow(stringResource(R.string.settings_about_row_account), snapshot.userName ?: unknown)
            mirror?.counts?.let { LibraryTiles(it, numberFormat) }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            if (mirror != null) {
                val neverValue = stringResource(R.string.settings_about_never)
                val sync = state.syncStatus
                val syncValue = when (sync) {
                    is SyncStatus.Idle -> stringResource(R.string.settings_about_sync_idle)
                    is SyncStatus.Syncing -> {
                        val library = sync.libraryName ?: stringResource(R.string.settings_about_sync_library)
                        val total = sync.totalItems
                        if (total != null) {
                            stringResource(R.string.settings_about_sync_progress, library, sync.itemsDone.toString(), total.toString())
                        } else {
                            stringResource(R.string.settings_about_sync_progress_unbounded, library, sync.itemsDone.toString())
                        }
                    }
                }
                AboutCard(
                    key = "about/mirror",
                    title = stringResource(R.string.settings_about_mirror_group),
                    status = {
                        if (snapshot.liveEventsConnected) {
                            CardStatus(stringResource(R.string.settings_about_live_events_connected), JellybeamTheme.Pistacchio)
                        } else {
                            CardStatus(stringResource(R.string.settings_about_live_events_disconnected), JellybeamTheme.Grigio)
                        }
                    },
                ) {
                    StatRow(stringResource(R.string.settings_about_row_items_mirrored), numberFormat.format(mirror.itemCount), emphasized = true)
                    StatRow(stringResource(R.string.settings_about_row_database), AboutFormatting.formatBytes(mirror.dbBytes.toLong(), strings))
                    StatRow(
                        stringResource(R.string.settings_about_row_last_full_sync),
                        mirror.lastFullSyncMs?.let { AboutFormatting.formatSyncInstant(it, state.nowMs, strings) } ?: neverValue,
                    )
                    StatRow(
                        stringResource(R.string.settings_about_row_last_change_check),
                        mirror.lastDeltaSync
                            ?.let(AboutFormatting::parseRfc3339Millis)
                            ?.let { AboutFormatting.formatSyncInstant(it, state.nowMs, strings) }
                            ?: neverValue,
                        emphasized = true,
                    )
                    StatRow(stringResource(R.string.settings_about_row_sync), syncValue, emphasized = sync is SyncStatus.Syncing)
                }
            }

            AboutCard(key = "about/device", title = stringResource(R.string.settings_about_device_group)) {
                StatRow(stringResource(R.string.settings_about_row_android), stringResource(R.string.settings_about_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                StatRow(stringResource(R.string.settings_about_row_media3), MediaLibraryInfo.VERSION)
                // A UUID outgrows the value column, so it sits under its label at full width.
                Hairline()
                BasicText(
                    text = stringResource(R.string.settings_about_row_device_id),
                    modifier = Modifier.padding(top = 5.dp),
                    style = statLabelStyle(),
                )
                BasicText(
                    text = snapshot.deviceId ?: unknown,
                    modifier = Modifier.padding(top = 2.dp),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 11.sp),
                )
            }
        }
    }
}

/**
 * The mirror's item counts as big-number tiles, one per non-empty type, three to a row. Counted
 * locally: a multi-server proxy answers `/Items/Counts` for one backend, not the merged library.
 */
@Composable
private fun LibraryTiles(counts: MirrorItemCounts, numberFormat: NumberFormat) {
    val tiles = listOf(
        counts.movies to R.string.settings_about_unit_movies,
        counts.series to R.string.settings_about_unit_series,
        counts.episodes to R.string.settings_about_unit_episodes,
        counts.boxSets to R.string.settings_about_unit_collections,
        counts.albums to R.string.settings_about_unit_albums,
        counts.songs to R.string.settings_about_unit_songs,
        counts.artists to R.string.settings_about_unit_artists,
        counts.musicVideos to R.string.settings_about_unit_music_videos,
        counts.books to R.string.settings_about_unit_books,
        counts.trailers to R.string.settings_about_unit_trailers,
        counts.programs to R.string.settings_about_unit_programs,
    ).filter { (count, _) -> count > 0 }.map { (count, unitRes) -> numberFormat.format(count) to unitRes }

    Spacer(Modifier.height(11.dp))
    Hairline()
    Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
        BasicText(text = stringResource(R.string.settings_about_library_group).uppercaseUi(), style = kickerStyle(JellybeamTheme.Grigio))
    }
    if (tiles.isEmpty()) {
        BasicText(text = stringResource(R.string.settings_about_media_none), style = statLabelStyle())
        return
    }
    BoxWithConstraints {
        // One number size for every tile, set by the longest, so large libraries shrink together.
        val tileInnerWidth = (maxWidth - TILE_GAP * (TILES_PER_ROW - 1)) / TILES_PER_ROW - TILE_HPADDING * 2
        val numberSp = AboutFormatting.fitMonoFontSp(
            chars = tiles.maxOf { (number, _) -> number.length },
            availableSp = tileInnerWidth.value / LocalDensity.current.fontScale,
            maxSp = 23f,
            minSp = 11f,
        )
        LibraryTileRows(tiles, numberSp)
    }
}

@Composable
private fun LibraryTileRows(tiles: List<Pair<String, Int>>, numberSp: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                rowTiles.forEach { (number, unitRes) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(TILE_HEIGHT)
                            .background(JellybeamTheme.Notte, RoundedCornerShape(3.dp))
                            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(3.dp))
                            .padding(horizontal = TILE_HPADDING),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BasicText(
                            text = number,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = numberSp.sp),
                        )
                        BasicText(
                            text = stringResource(unitRes).uppercaseUi(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                            style = kickerStyle(JellybeamTheme.Grigio),
                        )
                    }
                }
                // A short last row keeps the three-column tile width.
                repeat(TILES_PER_ROW - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One About card: [title] kicker with an optional right-aligned [status], then its rows. Focusable
 * but not clickable, so the D-pad can walk the cards and `focusable()` scrolls one into view; a
 * Pistacchio border stands in for [RowCard]'s focus ring.
 */
@Composable
private fun AboutCard(
    key: String,
    title: String,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current
    val shape = RoundedCornerShape(CARD_CORNER)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (memory != null) it.focusKey(memory, key) else it }
            .let { it.paneEntry(entryTarget, key) }
            .focusable(interactionSource = interactionSource)
            .background(JellybeamTheme.Surface, shape)
            .border(1.dp, if (isFocused) JellybeamTheme.Pistacchio else Color.Transparent, shape)
            .padding(start = CARD_HPADDING, end = CARD_HPADDING, bottom = CARD_BOTTOM_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(CARD_HEADER_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(text = title.uppercaseUi(), style = kickerStyle(JellybeamTheme.Grigio))
            status()
        }
        content()
    }
}

/** A card header's right-hand state: a dot and an uppercase mono word, both in [color]. */
@Composable
private fun CardStatus(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(4.dp).background(color, CircleShape))
        BasicText(text = text.uppercaseUi(), style = kickerStyle(color))
    }
}

/** One hairline-topped label/value line; [emphasized] values are the card's headline facts. */
@Composable
private fun StatRow(label: String, value: String, emphasized: Boolean = false) {
    Hairline()
    Row(modifier = Modifier.fillMaxWidth().height(STAT_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text = label, maxLines = 1, modifier = Modifier.padding(end = 16.dp), style = statLabelStyle())
        BasicText(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = JellybeamTheme.MartianMono,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                color = if (emphasized) JellybeamTheme.Panna else JellybeamTheme.Grigio,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
            ),
        )
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Hairline))
}

private fun statLabelStyle() = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 10.sp)

/** The About voice's small uppercase mono: card kickers, header states, tile units, version line. */
private fun kickerStyle(color: Color) = TextStyle(
    fontFamily = JellybeamTheme.MartianMono,
    color = color,
    fontSize = 9.sp,
    letterSpacing = 0.5.sp,
)
