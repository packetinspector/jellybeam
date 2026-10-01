package tv.jellybeam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.diag.DiagStatus
import tv.jellybeam.player.pipController
import tv.jellybeam.player.subtitleColorArgb
import tv.jellybeam.ui.focus.focusKey
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.SeekPreviewSize
import uniffi.jellybeam_core.PlaybackQuality
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingMode
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitlePositionPreset

/**
 * The settings sheet's rail sections (docs/09), trimmed to what this app can
 * build: Server & Account is omitted -- it needs a signed-in-sessions read
 * [tv.jellybeam.data.CoreGateway] doesn't expose; Shortcuts is a keyboard-shortcuts reference and
 * Android TV's D-pad has no keyboard to document. Home, Library, Playback, Subtitles, About all
 * map onto fields this app's `Settings` record (or, for Subtitles/About, local content) already
 * has.
 */
internal enum class SettingsSection(val labelRes: Int) {
    HOME(R.string.settings_section_home),
    // "Show missing episodes" only affects series/season detail pages, so it's its own section,
    // not filed under Home.
    LIBRARY(R.string.settings_section_library),
    PLAYBACK(R.string.settings_section_playback),
    // Player OSD density preference (docs/jellybeam-osd-handoff §7/§11); its own section since it
    // configures the player screen itself, not a playback behavior.
    OSD(R.string.settings_section_osd),
    SUBTITLES(R.string.settings_section_subtitles),
    // docs/14-seerr-discover.md: Connect/Disconnect a Jellyseerr/Overseerr server. Its config lives
    // outside the `Settings` record entirely (see [DiscoverSettingsViewModel]).
    DISCOVER(R.string.settings_section_discover),
    // docs/21-user-reporting.md §6: diagnostic logging, crash reports, and the LAN report page.
    TROUBLESHOOTING(R.string.settings_section_troubleshooting),
    ABOUT(R.string.settings_section_about),
}

/** Rail order, top to bottom -- the one place this list is spelled out, so rail and tests can't
 * drift apart. */
internal fun settingsSectionOrder(): List<SettingsSection> = SettingsSection.entries

@Composable
private fun nextUpCutoffChipLabel(days: UInt?): String = when (days) {
    null -> stringResource(R.string.settings_next_up_cutoff_off)
    7u -> stringResource(R.string.settings_next_up_cutoff_1_week)
    14u -> stringResource(R.string.settings_next_up_cutoff_2_weeks)
    30u -> stringResource(R.string.settings_next_up_cutoff_1_month)
    90u -> stringResource(R.string.settings_next_up_cutoff_3_months)
    365u -> stringResource(R.string.settings_next_up_cutoff_1_year)
    // Defensive only -- a raw `settings.json` edit is the one way a seventh value reaches this
    // `when`.
    else -> stringResource(R.string.settings_skip_seconds, days.toInt())
}

/**
 * Home section: Next Up cutoff + rewatching, per-library Home visibility, shelf size, "hide
 * watched from Latest", and the startup screen picker, grouped into "Next Up" and "Libraries on Home" subgroups.
 * `showVirtualEpisodes` lives in [LibrarySectionContent] instead since it affects detail pages,
 * not Home.
 */
@Composable
internal fun HomeSectionContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = state.settings
    val homeLabel = stringResource(R.string.settings_startup_screen_home)
    // Insertion-ordered: Home first, then the server's view order.
    val startupNames = remember(state.views, homeLabel) {
        mapOf<String?, String>(null to homeLabel) + state.views.associate { it.id to it.name }
    }
    val startupIds = remember(startupNames) { startupNames.keys.toList() }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        SectionHeader(stringResource(R.string.settings_next_up_group))
        PresetChipRow(
            label = stringResource(R.string.settings_cutoff),
            description = stringResource(R.string.settings_desc_home_cutoff),
            key = "home/cutoff",
            options = NEXT_UP_CUTOFF_DAY_PRESETS,
            selected = settings.nextUpCutoffDays,
            onSelect = viewModel::selectNextUpCutoff,
            chipLabel = { nextUpCutoffChipLabel(it) },
            chipKey = { days -> days?.toString() ?: "off" },
        )
        ToggleRow(
            label = stringResource(R.string.settings_next_up_rewatching),
            description = stringResource(R.string.settings_desc_home_next_up_rewatching),
            value = settings.nextUpRewatching,
            onToggle = viewModel::toggleNextUpRewatching,
            key = "home/next_up_rewatching",
        )

        SectionHeader(stringResource(R.string.settings_libraries_on_home))
        val libraryVisibilityDescription = stringResource(R.string.settings_desc_home_library_visibility)
        state.views.forEach { view ->
            ToggleRow(
                label = view.name,
                description = libraryVisibilityDescription,
                value = view.id !in settings.hiddenLibraryIds,
                onToggle = { viewModel.toggleLibraryVisibility(view.id) },
                key = "home/library_visibility/${view.id}",
            )
        }

        ToggleRow(
            label = stringResource(R.string.settings_home_show_favorites),
            description = stringResource(R.string.settings_desc_home_show_favorites),
            value = settings.homeShowFavorites,
            onToggle = viewModel::toggleHomeShowFavorites,
            key = "home/show_favorites",
        )
        PresetChipRow(
            label = stringResource(R.string.settings_home_shelf_size),
            description = stringResource(R.string.settings_desc_home_shelf_size),
            key = "home/shelf_size",
            options = HOME_SHELF_SIZE_PRESETS,
            selected = settings.homeShelfSize,
            onSelect = viewModel::selectHomeShelfSize,
            chipLabel = { size -> size.toString() },
        )
        ToggleRow(
            label = stringResource(R.string.settings_hide_watched_in_latest),
            description = stringResource(R.string.settings_desc_home_hide_watched_in_latest),
            value = settings.hideWatchedInLatest,
            onToggle = viewModel::toggleHideWatchedInLatest,
            key = "home/hide_watched_in_latest",
        )
        PresetChipRow(
            label = stringResource(R.string.settings_startup_screen),
            description = stringResource(R.string.settings_desc_home_startup_screen),
            key = "home/startup_screen",
            options = startupIds,
            selected = settings.startupScreenViewId,
            onSelect = viewModel::selectStartupScreen,
            chipLabel = { startupNames.getValue(it) },
            chipKey = { viewId -> viewId ?: "home" },
        )
        // Home top bar's clock, default on.
        ToggleRow(
            label = stringResource(R.string.settings_show_clock),
            description = stringResource(R.string.settings_desc_home_show_clock),
            value = settings.showClock,
            onToggle = viewModel::toggleShowClock,
            key = "home/show_clock",
        )
    }
}

/** Library section: settings affecting library/series/season detail pages rather than Home --
 * `showVirtualEpisodes` shows missing/unaired-episode placeholders in episode listings.
 */
@Composable
internal fun LibrarySectionContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ToggleRow(
            label = stringResource(R.string.settings_show_virtual_episodes),
            description = stringResource(R.string.settings_desc_library_show_virtual_episodes),
            value = state.settings.showVirtualEpisodes,
            onToggle = viewModel::toggleShowVirtualEpisodes,
            key = "library/show_virtual_episodes",
        )
    }
}

/** [SegmentAction]'s chip label: Ask/Auto/Off. */
@Composable
private fun skipSegmentActionChipLabel(action: SegmentAction): String = when (action) {
    SegmentAction.ASK -> stringResource(R.string.settings_skip_segment_ask)
    SegmentAction.AUTO_SKIP -> stringResource(R.string.settings_skip_segment_auto)
    SegmentAction.OFF -> stringResource(R.string.settings_skip_segment_off)
}

/** [PlaybackQuality]'s chip label (docs/18-playback-quality.md §1/§3): `DirectPlay`/`Auto` are
 * plain lookups; a `Cap` formats `maxBps` as whole Mbps -- every [PLAYBACK_QUALITY_PRESETS] entry
 * is an exact multiple of 1_000_000, so integer division never loses precision.
 */
@Composable
private fun playbackQualityChipLabel(quality: PlaybackQuality): String = when (quality) {
    is PlaybackQuality.DirectPlay -> stringResource(R.string.settings_quality_direct_play)
    is PlaybackQuality.Auto -> stringResource(R.string.settings_quality_auto)
    is PlaybackQuality.Cap -> stringResource(R.string.settings_quality_mbps, (quality.maxBps / 1_000_000u).toInt())
}

/** [SettingsChip.key]/focus-memory key for one [PLAYBACK_QUALITY_PRESETS] entry --
 * `direct_play`/`auto`/`cap_20`/`cap_8`/`cap_3`.
 */
private fun playbackQualityChipKey(quality: PlaybackQuality): String = when (quality) {
    is PlaybackQuality.DirectPlay -> "direct_play"
    is PlaybackQuality.Auto -> "auto"
    is PlaybackQuality.Cap -> "cap_${quality.maxBps / 1_000_000u}"
}

/** One "Skip segments" row (docs/09-settings-plan.md): a 3-way Ask/Auto/Off [ChipFieldRow] for a
 * single segment type, writing through [onSelect].
 */
@Composable
private fun SkipSegmentRow(label: String, description: String, key: String, current: SegmentAction, onSelect: (SegmentAction) -> Unit) {
    PresetChipRow(
        label = label,
        description = description,
        key = key,
        options = SKIP_SEGMENT_ACTION_PRESETS,
        selected = current,
        onSelect = onSelect,
        chipLabel = { skipSegmentActionChipLabel(it) },
    )
}

/**
 * docs/09's Playback fields: docs/18-playback-quality.md's Quality row + skip back/forward +
 * autoplay (enabled + delay) + per-type skip-segment behavior. Quality defaults to Direct Play
 * (CLAUDE.md's strictly-opt-in transcoding rule): Auto/bitrate caps only transcode after this
 * TV's own player has locally proven it can't play a file, never from a server prediction. The
 * mini player row also gates on [pipController]'s device PiP support ([ToggleRow]'s `enabled`
 * contract): unsupported devices show it dimmed, Off and inert.
 */
@Composable
internal fun PlaybackSectionContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = state.settings
    val context = LocalContext.current
    val miniPlayerSupported = remember { pipController.isSupported(context) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        // docs/18-playback-quality.md §1/§3: Direct Play (default) / Auto / three fixed bitrate
        // caps.
        PresetChipRow(
            label = stringResource(R.string.settings_playback_quality),
            description = stringResource(R.string.settings_desc_playback_quality),
            key = "playback/quality",
            options = PLAYBACK_QUALITY_PRESETS,
            selected = settings.playbackQuality,
            onSelect = viewModel::selectPlaybackQuality,
            chipLabel = { playbackQualityChipLabel(it) },
            chipKey = ::playbackQualityChipKey,
        )
        PresetChipRow(
            label = stringResource(R.string.settings_skip_back),
            description = stringResource(R.string.settings_desc_playback_skip_back),
            key = "playback/skip_back",
            options = SKIP_SECONDS_PRESETS,
            selected = settings.skipBackSecs,
            onSelect = viewModel::selectSkipBack,
            chipLabel = { secondsChipLabel(it) },
        )
        PresetChipRow(
            label = stringResource(R.string.settings_skip_forward),
            description = stringResource(R.string.settings_desc_playback_skip_forward),
            key = "playback/skip_forward",
            options = SKIP_SECONDS_PRESETS,
            selected = settings.skipForwardSecs,
            onSelect = viewModel::selectSkipForward,
            chipLabel = { secondsChipLabel(it) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_autoplay_enabled),
            description = stringResource(R.string.settings_desc_playback_autoplay_enabled),
            value = settings.autoplayEnabled,
            onToggle = viewModel::toggleAutoplayEnabled,
            key = "playback/autoplay_enabled",
        )
        PresetChipRow(
            label = stringResource(R.string.settings_autoplay_delay),
            description = stringResource(R.string.settings_desc_playback_autoplay_delay),
            key = "playback/autoplay_delay",
            options = AUTOPLAY_DELAY_SECONDS_PRESETS,
            selected = settings.autoplayDelaySecs,
            onSelect = viewModel::selectAutoplayDelay,
            chipLabel = { secondsChipLabel(it) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_mini_player),
            description = if (miniPlayerSupported) {
                stringResource(R.string.settings_desc_playback_mini_player)
            } else {
                stringResource(R.string.settings_desc_playback_mini_player_unsupported)
            },
            // Unsupported devices always read Off -- the player gates on isSupported too.
            value = settings.miniPlayerEnabled && miniPlayerSupported,
            onToggle = viewModel::toggleMiniPlayer,
            key = "playback/mini_player",
            enabled = miniPlayerSupported,
        )

        SectionHeader(stringResource(R.string.settings_still_watching_group))
        StillWatchingGroup(state = state, viewModel = viewModel)

        SectionHeader(stringResource(R.string.settings_skip_segments_group))
        SkipSegmentRow(
            label = stringResource(R.string.settings_skip_segment_intro),
            description = stringResource(R.string.settings_desc_playback_skip_segment_intro),
            key = "playback/skip_segment_intro",
            current = settings.skipIntro,
            onSelect = viewModel::selectSkipIntro,
        )
        SkipSegmentRow(
            label = stringResource(R.string.settings_skip_segment_outro),
            description = stringResource(R.string.settings_desc_playback_skip_segment_outro),
            key = "playback/skip_segment_outro",
            current = settings.skipOutro,
            onSelect = viewModel::selectSkipOutro,
        )
        SkipSegmentRow(
            label = stringResource(R.string.settings_skip_segment_recap),
            description = stringResource(R.string.settings_desc_playback_skip_segment_recap),
            key = "playback/skip_segment_recap",
            current = settings.skipRecap,
            onSelect = viewModel::selectSkipRecap,
        )
        SkipSegmentRow(
            label = stringResource(R.string.settings_skip_segment_preview),
            description = stringResource(R.string.settings_desc_playback_skip_segment_preview),
            key = "playback/skip_segment_preview",
            current = settings.skipPreview,
            onSelect = viewModel::selectSkipPreview,
        )
        SkipSegmentRow(
            label = stringResource(R.string.settings_skip_segment_commercial),
            description = stringResource(R.string.settings_desc_playback_skip_segment_commercial),
            key = "playback/skip_segment_commercial",
            current = settings.skipCommercial,
            onSelect = viewModel::selectSkipCommercial,
        )

        // "Advanced" subgroup: level tolerance affects device-profile/decoder selection; the
        // audio switches are local renderer overrides, never a server Direct Play change.
        SectionHeader(stringResource(R.string.settings_advanced_group))
        ToggleRow(
            label = stringResource(R.string.settings_tolerate_levels),
            description = stringResource(R.string.settings_desc_playback_tolerate_levels),
            value = settings.tolerateMislabeledLevels,
            onToggle = viewModel::toggleTolerateMislabeledLevels,
            key = "playback/tolerate_levels",
        )
        ToggleRow(
            label = stringResource(R.string.settings_preload_on_focus),
            description = stringResource(R.string.settings_desc_playback_preload_on_focus),
            value = settings.preloadOnFocus,
            onToggle = viewModel::togglePreloadOnFocus,
            key = "playback/preload_on_focus",
        )
        ToggleRow(
            label = stringResource(R.string.settings_ffmpeg_true_hd),
            description = stringResource(R.string.settings_desc_playback_ffmpeg_true_hd),
            value = settings.preferFfmpegTrueHd,
            onToggle = viewModel::togglePreferFfmpegTrueHd,
            key = "playback/ffmpeg_true_hd",
        )
        ToggleRow(
            label = stringResource(R.string.settings_ffmpeg_dts),
            description = stringResource(R.string.settings_desc_playback_ffmpeg_dts),
            value = settings.preferFfmpegDts,
            onToggle = viewModel::togglePreferFfmpegDts,
            key = "playback/ffmpeg_dts",
        )
        ToggleRow(
            label = stringResource(R.string.settings_ffmpeg_dts_hd),
            description = stringResource(R.string.settings_desc_playback_ffmpeg_dts_hd),
            value = settings.preferFfmpegDtsHd,
            onToggle = viewModel::togglePreferFfmpegDtsHd,
            key = "playback/ffmpeg_dts_hd",
        )
        SettingsNoteRow(stringResource(R.string.settings_ffmpeg_dts_note))
    }
}

/** [StillWatchingMode]'s chip label -- Off/Episodes/Hours. */
@Composable
private fun stillWatchingModeChipLabel(mode: StillWatchingMode): String = when (mode) {
    StillWatchingMode.OFF -> stringResource(R.string.settings_still_watching_mode_off)
    StillWatchingMode.AFTER_EPISODES -> stringResource(R.string.settings_still_watching_mode_episodes)
    StillWatchingMode.AFTER_HOURS -> stringResource(R.string.settings_still_watching_mode_hours)
}

/** "Answer timeout" chip label: the option list is "30s 60s 2m 5m", not a uniform seconds/minutes
 * rule, so this maps [STILL_WATCHING_TIMEOUT_PRESETS]'s exact values. */
@Composable
private fun stillWatchingTimeoutChipLabel(secs: UInt): String = when (secs) {
    120u -> stringResource(R.string.settings_still_watching_timeout_minutes, 2)
    300u -> stringResource(R.string.settings_still_watching_timeout_minutes, 5)
    else -> secondsChipLabel(secs)
}

@Composable
private fun secondsChipLabel(secs: UInt): String = stringResource(R.string.settings_skip_seconds, secs.toInt())

@Composable
private fun percentChipLabel(fraction: Float): String =
    stringResource(R.string.settings_subtitle_percent, (fraction * 100).toInt())

/**
 * "Still watching?" group, under Autoplay: mode row always shown; "Episodes"/"Hours" only for the
 * matching mode; "Answer timeout" shown whenever mode isn't Off. Meaningless when autoplay is
 * off, but still renders at reduced alpha with an explanatory note, and stays focusable/editable.
 */
@Composable
private fun StillWatchingGroup(state: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = state.settings
    val stillWatching = settings.stillWatching
    val dimmed = Modifier.alpha(if (settings.autoplayEnabled) 1f else SETTINGS_DISABLED_ALPHA)

    PresetChipRow(
        label = stringResource(R.string.settings_still_watching_mode),
        description = stringResource(R.string.settings_desc_playback_still_watching_mode),
        key = "playback/still_watching_mode",
        modifier = dimmed,
        options = STILL_WATCHING_MODE_PRESETS,
        selected = stillWatching.mode,
        onSelect = viewModel::selectStillWatchingMode,
        chipLabel = { stillWatchingModeChipLabel(it) },
    )
    if (stillWatching.mode == StillWatchingMode.AFTER_EPISODES) {
        PresetChipRow(
            label = stringResource(R.string.settings_still_watching_episodes),
            description = stringResource(R.string.settings_desc_playback_still_watching_episodes),
            key = "playback/still_watching_episodes",
            modifier = dimmed,
            options = STILL_WATCHING_EPISODE_PRESETS,
            selected = stillWatching.episodes,
            onSelect = viewModel::selectStillWatchingEpisodes,
            chipLabel = { episodes -> episodes.toString() },
        )
    }
    if (stillWatching.mode == StillWatchingMode.AFTER_HOURS) {
        PresetChipRow(
            label = stringResource(R.string.settings_still_watching_hours),
            description = stringResource(R.string.settings_desc_playback_still_watching_hours),
            key = "playback/still_watching_hours",
            modifier = dimmed,
            options = STILL_WATCHING_HOUR_PRESETS,
            selected = stillWatching.hours,
            onSelect = viewModel::selectStillWatchingHours,
            chipLabel = { hours -> stringResource(R.string.settings_still_watching_hours_value, hours.toInt()) },
        )
    }
    if (stillWatching.mode != StillWatchingMode.OFF) {
        PresetChipRow(
            label = stringResource(R.string.settings_still_watching_timeout),
            description = stringResource(R.string.settings_desc_playback_still_watching_timeout),
            key = "playback/still_watching_timeout",
            modifier = dimmed,
            options = STILL_WATCHING_TIMEOUT_PRESETS,
            selected = stillWatching.timeoutSecs,
            onSelect = viewModel::selectStillWatchingTimeout,
            chipLabel = { stillWatchingTimeoutChipLabel(it) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_still_watching_reset_on_input),
            description = stringResource(R.string.settings_desc_playback_still_watching_reset_on_input),
            value = stillWatching.resetOnInput,
            onToggle = viewModel::toggleStillWatchingResetOnInput,
            key = "playback/still_watching_reset_on_input",
            modifier = dimmed,
        )
    }
    if (!settings.autoplayEnabled) {
        SettingsNoteRow(stringResource(R.string.settings_still_watching_note))
    }
}

/** [OsdDetailSetting]'s chip label -- Minimal/Full (docs/jellybeam-osd-handoff §7). */
@Composable
private fun osdDetailChipLabel(detail: OsdDetailSetting): String = when (detail) {
    OsdDetailSetting.MINIMAL -> stringResource(R.string.settings_osd_detail_minimal)
    OsdDetailSetting.FULL -> stringResource(R.string.settings_osd_detail_full)
}

/** OSD section (docs/jellybeam-osd-handoff §7/§11): player screen density, a two-way [ChipFieldRow]
 * plus a note on what the denser mode adds. */
@Composable
internal fun OsdSectionContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        PresetChipRow(
            label = stringResource(R.string.settings_osd_detail),
            description = stringResource(R.string.settings_desc_osd_osd_detail),
            key = "osd/osd_detail",
            options = OSD_DETAIL_PRESETS,
            selected = state.settings.osdDetail,
            onSelect = viewModel::selectOsdDetail,
            chipLabel = { osdDetailChipLabel(it) },
        )
        SettingsNoteRow(stringResource(R.string.settings_osd_detail_note))
        PresetChipRow(
            label = stringResource(R.string.settings_seek_preview_size),
            description = stringResource(R.string.settings_desc_osd_seek_preview_size),
            key = "osd/seek_preview_size",
            options = SEEK_PREVIEW_SIZE_PRESETS,
            selected = state.settings.seekPreviewSize,
            onSelect = viewModel::selectSeekPreviewSize,
            chipLabel = { seekPreviewSizeChipLabel(it) },
        )
    }
}

/** [SeekPreviewSize]'s chip label (docs/12 §11). */
@Composable
private fun seekPreviewSizeChipLabel(size: SeekPreviewSize): String = when (size) {
    SeekPreviewSize.SMALL -> stringResource(R.string.settings_seek_preview_size_small)
    SeekPreviewSize.MEDIUM -> stringResource(R.string.settings_seek_preview_size_medium)
    SeekPreviewSize.LARGE -> stringResource(R.string.settings_seek_preview_size_large)
}

/** [SubtitlePositionPreset]'s row label. */
@Composable
private fun subtitlePositionChipLabel(position: SubtitlePositionPreset): String = when (position) {
    SubtitlePositionPreset.DEFAULT -> stringResource(R.string.settings_subtitle_position_default)
    SubtitlePositionPreset.RAISED -> stringResource(R.string.settings_subtitle_position_raised)
    SubtitlePositionPreset.HIGHER -> stringResource(R.string.settings_subtitle_position_higher)
    SubtitlePositionPreset.HIGHEST -> stringResource(R.string.settings_subtitle_position_highest)
}

/** [SubtitleColorPreset]'s row label. */
@Composable
private fun subtitleColorChipLabel(color: SubtitleColorPreset): String = when (color) {
    SubtitleColorPreset.WHITE -> stringResource(R.string.settings_subtitle_color_white)
    SubtitleColorPreset.SOFT_WHITE -> stringResource(R.string.settings_subtitle_color_soft_white)
    SubtitleColorPreset.YELLOW -> stringResource(R.string.settings_subtitle_color_yellow)
    SubtitleColorPreset.LIGHT_GREEN -> stringResource(R.string.settings_subtitle_color_light_green)
}

/**
 * docs/09 subtitle style presets, applied by `PlaybackScreen.kt`. Size and position always apply;
 * the system-style toggle dims (but keeps editable) the color, bold and background rows it
 * overrides, like the Still watching group.
 */
@Composable
internal fun SubtitlesSectionContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = state.settings
    val overridden = Modifier.alpha(if (settings.subtitleUseSystemStyle) SETTINGS_DISABLED_ALPHA else 1f)

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        PresetChipRow(
            label = stringResource(R.string.settings_subtitle_size),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_size),
            key = "subtitles/subtitle_size",
            options = SUBTITLE_SCALE_PRESETS,
            selected = settings.subtitleScale,
            onSelect = viewModel::selectSubtitleScale,
            chipLabel = { percentChipLabel(it) },
        )
        PresetChipRow(
            label = stringResource(R.string.settings_subtitle_position),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_position),
            key = "subtitles/subtitle_position",
            options = SUBTITLE_POSITION_PRESETS,
            selected = settings.subtitlePosition,
            onSelect = viewModel::selectSubtitlePosition,
            chipLabel = { subtitlePositionChipLabel(it) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_subtitle_system_style),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_system_style),
            value = settings.subtitleUseSystemStyle,
            onToggle = viewModel::toggleSubtitleUseSystemStyle,
            key = "subtitles/subtitle_system_style",
        )
        PresetChipRow(
            label = stringResource(R.string.settings_subtitle_color),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_color),
            key = "subtitles/subtitle_color",
            modifier = overridden,
            options = SUBTITLE_COLOR_PRESETS,
            selected = settings.subtitleColor,
            onSelect = viewModel::selectSubtitleColor,
            chipLabel = { subtitleColorChipLabel(it) },
            swatch = { Color(subtitleColorArgb(it)) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_subtitle_bold),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_bold),
            value = settings.subtitleBold,
            onToggle = viewModel::toggleSubtitleBold,
            key = "subtitles/subtitle_bold",
            modifier = overridden,
        )
        PresetChipRow(
            label = stringResource(R.string.settings_subtitle_background),
            description = stringResource(R.string.settings_desc_subtitles_subtitle_background),
            key = "subtitles/subtitle_background",
            modifier = overridden,
            options = SUBTITLE_BACKGROUND_OPACITY_PRESETS,
            selected = settings.subtitleBackgroundOpacity,
            onSelect = viewModel::selectSubtitleBackgroundOpacity,
            chipLabel = { percentChipLabel(it) },
        )
    }
}

/** Chip display order, house convention: explicit preset list (like
 * [SKIP_SEGMENT_ACTION_PRESETS]/[OSD_DETAIL_PRESETS]) rather than a raw enum `.entries` walk. */
private val SEERR_AUTH_METHOD_OPTIONS: List<SeerrAuthMethod> = listOf(
    SeerrAuthMethod.JELLYFIN,
    SeerrAuthMethod.LOCAL,
    SeerrAuthMethod.API_KEY,
)

/** [SeerrAuthMethod]'s chip label -- docs/14's own three-method list. */
@Composable
private fun SeerrAuthMethod.chipLabel(): String = when (this) {
    SeerrAuthMethod.JELLYFIN -> stringResource(R.string.settings_discover_method_jellyfin)
    SeerrAuthMethod.LOCAL -> stringResource(R.string.settings_discover_method_local)
    SeerrAuthMethod.API_KEY -> stringResource(R.string.settings_discover_method_api_key)
}

/** [tv.jellybeam.diag.DiagStatus]'s row subtitle (docs/21-user-reporting.md §6): "Off", or "On since
 * <date> · <n> lines". */
@Composable
private fun diagnosticLoggingSubtitle(status: DiagStatus): String =
    if (!status.enabled) {
        stringResource(R.string.settings_troubleshooting_logging_off)
    } else {
        val date = status.onSinceMs?.let(::formatOnSinceDate) ?: "-"
        val lines = pluralStringResource(R.plurals.settings_troubleshooting_logging_lines, status.lines, status.lines)
        stringResource(R.string.settings_troubleshooting_logging_on, date, lines)
    }

/** Short local date for [diagnosticLoggingSubtitle] -- no year (a diagnostic session never spans
 * one), no time (the line count already conveys freshness). */
private fun formatOnSinceDate(epochMs: Long): String =
    java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

/**
 * Troubleshooting section (docs/21-user-reporting.md §6): diagnostic logging + crash reports
 * toggles, "Report a problem" (always enabled, [onReportProblem] opens
 * [tv.jellybeam.ui.report.ReportScreen]), and "Clear log" (disabled with nothing to clear).
 */
@Composable
internal fun TroubleshootingSectionContent(state: SettingsUiState, viewModel: SettingsViewModel, onReportProblem: () -> Unit) {
    val settings = state.settings
    val diagStatus = state.diagStatus
    val clearEnabled = diagStatus.lines > 0 || state.crashPending

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ToggleRow(
            label = stringResource(R.string.settings_troubleshooting_diagnostic_logging),
            description = diagnosticLoggingSubtitle(diagStatus),
            value = settings.diagnosticLoggingEnabled,
            onToggle = viewModel::toggleDiagnosticLogging,
            key = "troubleshooting/diagnostic_logging",
        )
        ToggleRow(
            label = stringResource(R.string.settings_troubleshooting_crash_reports),
            description = stringResource(R.string.settings_desc_troubleshooting_crash_reports),
            value = settings.crashReportsEnabled,
            onToggle = viewModel::toggleCrashReports,
            key = "troubleshooting/crash_reports",
        )
        ActionRow(
            label = stringResource(R.string.settings_troubleshooting_report_problem),
            description = stringResource(R.string.settings_desc_troubleshooting_report_problem),
            onClick = onReportProblem,
            key = "troubleshooting/report_problem",
        )
        ActionRow(
            label = stringResource(R.string.settings_troubleshooting_clear_log),
            description = stringResource(R.string.settings_desc_troubleshooting_clear_log),
            onClick = viewModel::clearDiagnostics,
            key = "troubleshooting/clear_log",
            enabled = clearEnabled,
        )
    }
}

/**
 * Discover section (docs/14-seerr-discover.md): status row, method chips, URL/identity/secret
 * fields (the `JellybeamTextField` recipe from [tv.jellybeam.ui.signin.SignInScreen]), a Connect button
 * (spinner while connecting, inline error), and a confirmation-gated Disconnect. Values write
 * through only on Connect (see [DiscoverSettingsViewModel]). [onSeerrConfigChanged] is
 * [tv.jellybeam.MainActivity]'s `seerrEpoch` bump, so the drawer's Discover entry appears/disappears
 * without an app restart.
 */
@Composable
internal fun DiscoverSectionContent(
    onSeerrConfigChanged: () -> Unit,
    viewModel: DiscoverSettingsViewModel = viewModel(factory = DiscoverSettingsViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    if (state.isLoading) return

    // Connect, Disconnect and its confirmation each replace the control that had focus, which
    // would leave the D-pad with nothing to move; hand focus to the new layout's first chip on
    // every layout change after the first (pane entry owns the first). A chip, never a text
    // field: landing in a field opens editing and the D-pad stops moving.
    val focusGate = LocalSettingsFocusGate.current
    val connectedRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }
    val formRequester = remember { FocusRequester() }
    val layout = state.status.configured to state.disconnectConfirmVisible
    var lastLayout by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(layout) {
        val previous = lastLayout
        lastLayout = layout
        if (previous == null || previous == layout) return@LaunchedEffect
        val target = when {
            !layout.first -> formRequester
            layout.second -> confirmRequester
            else -> connectedRequester
        }
        requestFocusUntilSuccess(focusGate) { target.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        RowCard(isFocused = false) {
            val statusText = if (state.status.configured) {
                stringResource(
                    R.string.settings_discover_status_connected,
                    state.status.appTitle ?: state.status.seerrUrl.orEmpty(),
                    state.status.identity.orEmpty(),
                )
            } else {
                stringResource(R.string.settings_discover_status_not_configured)
            }
            BasicText(text = statusText, style = rowLabelStyle())
        }

        if (state.status.configured) {
            if (state.disconnectConfirmVisible) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        text = stringResource(R.string.settings_discover_disconnect_body),
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChip(
                            label = stringResource(R.string.settings_discover_disconnect_cancel),
                            selected = false,
                            onSelect = viewModel::dismissDisconnectConfirm,
                            key = "discover/disconnect_cancel",
                        )
                        SettingsChip(
                            label = stringResource(R.string.settings_discover_disconnect_confirm),
                            selected = true,
                            onSelect = { viewModel.disconnect(onSeerrConfigChanged) },
                            key = "discover/disconnect_confirm",
                            focusRequester = confirmRequester,
                        )
                    }
                }
            } else {
                SettingsChip(
                    label = stringResource(R.string.settings_discover_disconnect),
                    selected = false,
                    onSelect = viewModel::openDisconnectConfirm,
                    key = "discover/disconnect",
                    focusRequester = connectedRequester,
                )
            }
        } else {
            PresetChipRow(
                label = stringResource(R.string.settings_discover_method_label),
                description = stringResource(R.string.settings_desc_discover_method),
                key = "discover/method",
                options = SEERR_AUTH_METHOD_OPTIONS,
                selected = state.method,
                onSelect = viewModel::onMethodChange,
                chipLabel = { method -> method.chipLabel() },
                selectedFocusRequester = formRequester,
            )
            DiscoverTextField(
                label = stringResource(R.string.settings_discover_url_label),
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                key = "discover/url",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            DiscoverTextField(
                label = stringResource(
                    when (state.method) {
                        SeerrAuthMethod.API_KEY -> R.string.settings_discover_identity_label_api_key
                        // docs/14: a Seerr local account signs in with its email, never a username.
                        SeerrAuthMethod.LOCAL -> R.string.settings_discover_identity_label_local
                        SeerrAuthMethod.JELLYFIN -> R.string.settings_discover_identity_label
                    },
                ),
                value = state.identity,
                onValueChange = viewModel::onIdentityChange,
                key = "discover/identity",
                // Autocomplete would suggest full words over a username here too -- off.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (state.method == SeerrAuthMethod.LOCAL) KeyboardType.Email else KeyboardType.Text,
                    autoCorrect = false,
                ),
            )
            DiscoverTextField(
                label = stringResource(
                    if (state.method == SeerrAuthMethod.API_KEY) R.string.settings_discover_secret_label_api_key
                    else R.string.settings_discover_secret_label,
                ),
                value = state.secret,
                onValueChange = viewModel::onSecretChange,
                key = "discover/secret",
                visualTransformation = PasswordVisualTransformation(),
                // Without an explicit Password keyboard type, the TV keyboard suggested
                // autocomplete for the masked text.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            )
            state.error?.let { error ->
                BasicText(text = error, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp))
            }
            SettingsChip(
                label = stringResource(if (state.isConnecting) R.string.settings_discover_connecting else R.string.settings_discover_connect),
                selected = true,
                onSelect = { if (!state.isConnecting) viewModel.connect(onSeerrConfigChanged) },
                key = "discover/connect",
            )
        }
    }
}

/** [tv.jellybeam.ui.signin.SignInScreen]'s `JellybeamTextField`, copied into this section. [key] is
 * this field's docs/15-focus-and-selection.md §5 stable key ("discover/<id>"), registered the
 * same way [ToggleRow]/[SettingsChip] register theirs.
 */
@Composable
private fun DiscoverTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    key: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (memory != null) it.focusKey(memory, key) else it }
                    .let { it.paneEntry(entryTarget, key) },
                textStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 16.sp),
                singleLine = true,
                cursorBrush = SolidColor(JellybeamTheme.Pistacchio),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                interactionSource = interactionSource,
            )
        }
    }
}
