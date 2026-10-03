package tv.jellybeam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.diag.DiagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.LanguageSettings
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackQuality
import uniffi.jellybeam_core.SeekPreviewSize
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.StillWatchingMode
import uniffi.jellybeam_core.StillWatchingSettings
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitleModeSetting
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.ViewSnapshot

/** Next Up cutoff presets (docs/09-settings-plan.md), `null` = "Off". Chip options, in display
 * order. */
val NEXT_UP_CUTOFF_DAY_PRESETS: List<UInt?> = listOf(null, 7u, 14u, 30u, 90u, 365u)

/** Home shelf-size presets (docs/09). Chip options, in display order. */
val HOME_SHELF_SIZE_PRESETS: List<UInt> = listOf(10u, 20u, 30u)

/** Skip back/forward presets, shared by both rows (docs/09). Chip options, in display order. */
val SKIP_SECONDS_PRESETS: List<UInt> = listOf(5u, 10u, 15u, 30u, 60u)

/** Autoplay-delay presets (docs/09). Chip options, in display order. */
val AUTOPLAY_DELAY_SECONDS_PRESETS: List<UInt> = listOf(5u, 10u, 15u, 30u)

/**
 * Playback › "Quality" row's five chips, in display order (docs/18-playback-quality.md §1/§3):
 * Direct Play (default) / Auto / three fixed bitrate caps. Auto only ever transcodes once this
 * TV's player has locally proven it can't play the file. Selected-chip matching is plain
 * structural equality against [uniffi.jellybeam_core.Settings.playbackQuality] -- a stored `Cap`
 * whose `maxBps` isn't one of these three presets simply selects no chip.
 */
val PLAYBACK_QUALITY_PRESETS: List<PlaybackQuality> = listOf(
    PlaybackQuality.DirectPlay,
    PlaybackQuality.Auto,
    PlaybackQuality.Cap(maxBps = 20_000_000u),
    PlaybackQuality.Cap(maxBps = 8_000_000u),
    PlaybackQuality.Cap(maxBps = 3_000_000u),
)

/** Subtitle style presets, discrete chip options rather than sliders. */
val SUBTITLE_SCALE_PRESETS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f)

/** Vertical-position ladder, matched by `SubtitleStyle.kt`'s bottom-padding fractions. */
val SUBTITLE_POSITION_PRESETS: List<SubtitlePositionPreset> = listOf(
    SubtitlePositionPreset.DEFAULT,
    SubtitlePositionPreset.RAISED,
    SubtitlePositionPreset.HIGHER,
    SubtitlePositionPreset.HIGHEST,
)

/** Background opacity ladder -- `0.0f` is "Off" (no background, edge-outlined text instead). */
val SUBTITLE_BACKGROUND_OPACITY_PRESETS: List<Float> = listOf(0.0f, 0.25f, 0.5f, 0.75f)

/** docs/09: the curated colour list, White first as the default. */
val SUBTITLE_COLOR_PRESETS: List<SubtitleColorPreset> = listOf(
    SubtitleColorPreset.WHITE,
    SubtitleColorPreset.SOFT_WHITE,
    SubtitleColorPreset.YELLOW,
    SubtitleColorPreset.LIGHT_GREEN,
)

/** The 3-way ladder every "Skip segments" row (docs/09-settings-plan.md) offers, in display
 * order. */
val SKIP_SEGMENT_ACTION_PRESETS: List<SegmentAction> = listOf(
    SegmentAction.ASK,
    SegmentAction.AUTO_SKIP,
    SegmentAction.OFF,
)

/** OSD density ladder (docs/jellybeam-osd-handoff §7/§11): Minimal first, Full second, though this
 * build's actual default is [OsdDetailSetting.FULL].
 */
val OSD_DETAIL_PRESETS: List<OsdDetailSetting> = listOf(
    OsdDetailSetting.MINIMAL,
    OsdDetailSetting.FULL,
)

/** Seek-preview size ladder (docs/12 §11): Small / Medium / Large, default Medium. */
val SEEK_PREVIEW_SIZE_PRESETS: List<SeekPreviewSize> = listOf(
    SeekPreviewSize.SMALL,
    SeekPreviewSize.MEDIUM,
    SeekPreviewSize.LARGE,
)

/** "Still watching?" mode ladder -- `Off` first, matching "Ask after: Off / Episodes / Hours".
 */
val STILL_WATCHING_MODE_PRESETS: List<StillWatchingMode> = listOf(
    StillWatchingMode.OFF,
    StillWatchingMode.AFTER_EPISODES,
    StillWatchingMode.AFTER_HOURS,
)

/** Mirrors `playback_policy::still_watching::STILL_WATCHING_EPISODE_PRESETS` (Rust) exactly. */
val STILL_WATCHING_EPISODE_PRESETS: List<UInt> = listOf(2u, 3u, 4u, 5u, 8u)

/** Mirrors `playback_policy::still_watching::STILL_WATCHING_HOUR_PRESETS` (Rust) exactly. */
val STILL_WATCHING_HOUR_PRESETS: List<Float> = listOf(1f, 2f, 3f, 4f)

/** Mirrors `playback_policy::still_watching::STILL_WATCHING_TIMEOUT_PRESETS` (Rust) exactly. */
val STILL_WATCHING_TIMEOUT_PRESETS: List<UInt> = listOf(30u, 60u, 120u, 300u)

/**
 * The value [Settings] would have before a real `settings.json` is read -- mirrors
 * `Settings::default()` on the Rust side. Used only as [SettingsUiState]'s placeholder while
 * [SettingsViewModel.refresh] is in flight (never actually rendered); the real value always comes
 * from [CoreGateway.getSettings].
 */
fun defaultSettings(): Settings = Settings(
    nextUpCutoffDays = null,
    nextUpRewatching = false,
    hiddenLibraryIds = emptyList(),
    hideWatchedInLatest = false,
    startupScreenViewId = null,
    homeShelfSize = 20u,
    homeShowFavorites = true,
    skipBackSecs = 10u,
    skipForwardSecs = 10u,
    language = LanguageSettings(audio = null, subtitle = null, subtitleMode = SubtitleModeSetting.DEFAULT),
    autoplayEnabled = true,
    autoplayDelaySecs = 10u,
    subtitleScale = 1.0f,
    subtitlePosition = SubtitlePositionPreset.DEFAULT,
    subtitleBold = false,
    subtitleBackgroundOpacity = 0.0f,
    subtitleColor = SubtitleColorPreset.WHITE,
    subtitleUseSystemStyle = false,
    skipIntro = SegmentAction.ASK,
    skipOutro = SegmentAction.ASK,
    skipRecap = SegmentAction.ASK,
    skipPreview = SegmentAction.ASK,
    skipCommercial = SegmentAction.AUTO_SKIP,
    showClock = true,
    tolerateMislabeledLevels = true,
    preferFfmpegTrueHd = false,
    preferFfmpegDts = false,
    preferFfmpegDtsHd = false,
    showVirtualEpisodes = false,
    preloadOnFocus = true,
    miniPlayerEnabled = false,
    osdDetail = OsdDetailSetting.FULL,
    seekPreviewSize = SeekPreviewSize.MEDIUM,
    stillWatching = StillWatchingSettings(
        mode = StillWatchingMode.AFTER_EPISODES,
        episodes = 3u,
        hours = 3f,
        timeoutSecs = 120u,
        resetOnInput = true,
    ),
    // docs/18-playback-quality.md §1: Direct Play is the default -- never transcodes.
    playbackQuality = PlaybackQuality.DirectPlay,
    // docs/21 §6: mirrors Settings::default() -- logging off, crash capture on.
    diagnosticLoggingEnabled = false,
    crashReportsEnabled = true,
    homeLayout = HomeLayout.CLASSIC,
)

/** Which entry of [options] is [current], or `-1` if none is (e.g. a stale `startupScreenViewId`
 * for a removed library) -- the one rule every chip row's "which chip is filled" question uses.
 */
fun <T> selectedChipIndex(options: List<T>, current: T): Int = options.indexOf(current)

/** Flips [viewId]'s membership in [hiddenLibraryIds]: shown means absent from this list, so
 * toggling a shown library adds it and a hidden one removes it. */
fun toggleHiddenLibrary(hiddenLibraryIds: List<String>, viewId: String): List<String> =
    if (viewId in hiddenLibraryIds) hiddenLibraryIds - viewId else hiddenLibraryIds + viewId

/** The Startup screen row's chip ladder: `null` (Home) first, then one entry per [views] id, in
 * server order. */
fun startupScreenOptionIds(views: List<ViewSnapshot>): List<String?> = listOf(null) + views.map { it.id }

data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: Settings = defaultSettings(),
    /** Full server-configured library list (unfiltered by `hiddenLibraryIds`) -- the "Libraries
     * on Home" row needs every library, not just the visible ones. */
    val views: List<ViewSnapshot> = emptyList(),
    /** docs/21 §6: the Diagnostic logging row's live status subtitle, collected from
     * [AppGraph.diag]'s own [DiagStatus] flow. */
    val diagStatus: DiagStatus = DiagStatus(enabled = false, onSinceMs = null, lines = 0),
    /** docs/21 §6: whether a crash capture exists -- gates the "Clear log" row alongside
     * [diagStatus]'s line count. */
    val crashPending: Boolean = false,
)

/**
 * docs/21 §6: the Troubleshooting row actions that reach [tv.jellybeam.AppGraph.diag]/
 * [tv.jellybeam.AppGraph.crash], factored out of [SettingsViewModel] so
 * `SettingsViewModelTest` can pass a fake instead of touching the real [AppGraph].
 */
interface DiagnosticsController {
    fun setDiagnosticLoggingEnabled(enabled: Boolean)
    fun setCrashReportsEnabled(enabled: Boolean)
    fun discardCrash()
    fun clearDiagnostics()
    fun crashPending(): Boolean
}

/** [DiagnosticsController]'s real implementation, reached through [tv.jellybeam.AppGraph]. */
object AppGraphDiagnosticsController : DiagnosticsController {
    override fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        AppGraph.diag.setEnabled(enabled)
    }

    override fun setCrashReportsEnabled(enabled: Boolean) {
        AppGraph.crash.enabled = enabled
    }

    override fun discardCrash() {
        AppGraph.crash.discard()
    }

    override fun clearDiagnostics() {
        AppGraph.diag.clear()
        AppGraph.crash.discard()
    }

    override fun crashPending(): Boolean = AppGraph.crash.pending() != null
}

/**
 * Backs the Settings screen (docs/09-settings-plan.md slice 2): loads the whole [Settings]
 * record plus [CoreGateway.views] once on entry, then write-through on every row change -- each
 * `toggle*`/`select*` call computes the whole updated record, applies it to [state] immediately,
 * and pushes it to [CoreGateway.setSettings] via the shared [updateSettings] primitive.
 */
class SettingsViewModel(
    private val gateway: CoreGateway,
    /** docs/21 §6: injected so tests observe a fake status instead of the real [AppGraph.diag]. */
    private val diagStatus: StateFlow<DiagStatus> = AppGraph.diag.status,
    private val diagnostics: DiagnosticsController = AppGraphDiagnosticsController,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val settingsWrites = Mutex()

    init {
        viewModelScope.launch { refresh() }
        refreshCrashPending()
        viewModelScope.launch { diagStatus.collect { status -> _state.update { it.copy(diagStatus = status) } } }
    }

    private fun refreshCrashPending() {
        _state.update { it.copy(crashPending = diagnostics.crashPending()) }
    }

    private suspend fun refresh() {
        val settings = gateway.getSettings()
        val views = gateway.views()
        _state.update { it.copy(isLoading = false, settings = settings, views = views) }
    }

    private fun updateSettings(transform: (Settings) -> Settings) {
        val updated = transform(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        // Whole-record writes must finish in input order, even when an earlier IO call stalls.
        viewModelScope.launch { settingsWrites.withLock { gateway.setSettings(updated) } }
        // docs/21 §2.1: one line per settings write, no field name/value (never server addresses
        // or API keys, per the table's own field note).
        AppGraph.diag.event("settings.change")
    }

    fun selectNextUpCutoff(days: UInt?) = updateSettings { it.copy(nextUpCutoffDays = days) }

    fun selectHomeShelfSize(size: UInt) = updateSettings { it.copy(homeShelfSize = size) }

    fun toggleNextUpRewatching() = updateSettings { it.copy(nextUpRewatching = !it.nextUpRewatching) }

    fun toggleHideWatchedInLatest() = updateSettings { it.copy(hideWatchedInLatest = !it.hideWatchedInLatest) }

    fun toggleHomeShowFavorites() = updateSettings { it.copy(homeShowFavorites = !it.homeShowFavorites) }

    fun toggleLibraryVisibility(viewId: String) = updateSettings { settings ->
        settings.copy(hiddenLibraryIds = toggleHiddenLibrary(settings.hiddenLibraryIds, viewId))
    }

    fun selectStartupScreen(viewId: String?) = updateSettings { it.copy(startupScreenViewId = viewId) }

    /** Home section, "Library" grouping: whether virtual (missing/unaired) episode placeholders
     * show in series/season episode listings. Off by default. */
    fun toggleShowVirtualEpisodes() =
        updateSettings { it.copy(showVirtualEpisodes = !it.showVirtualEpisodes) }

    /** Playback › "Quality" row (docs/18-playback-quality.md §1/§3): Direct Play / Auto / a
     * fixed bitrate cap; Kotlin never branches on the mode beyond storing it. */
    fun selectPlaybackQuality(quality: PlaybackQuality) = updateSettings { it.copy(playbackQuality = quality) }

    fun selectSkipBack(secs: UInt) = updateSettings { it.copy(skipBackSecs = secs) }

    fun selectSkipForward(secs: UInt) = updateSettings { it.copy(skipForwardSecs = secs) }

    fun toggleAutoplayEnabled() = updateSettings { it.copy(autoplayEnabled = !it.autoplayEnabled) }

    fun selectAutoplayDelay(secs: UInt) = updateSettings { it.copy(autoplayDelaySecs = secs) }

    fun selectSubtitleScale(scale: Float) = updateSettings { it.copy(subtitleScale = scale) }

    fun selectSubtitlePosition(position: SubtitlePositionPreset) =
        updateSettings { it.copy(subtitlePosition = position) }

    fun toggleSubtitleBold() = updateSettings { it.copy(subtitleBold = !it.subtitleBold) }

    fun selectSubtitleBackgroundOpacity(opacity: Float) =
        updateSettings { it.copy(subtitleBackgroundOpacity = opacity) }

    fun selectSubtitleColor(color: SubtitleColorPreset) = updateSettings { it.copy(subtitleColor = color) }

    fun toggleSubtitleUseSystemStyle() =
        updateSettings { it.copy(subtitleUseSystemStyle = !it.subtitleUseSystemStyle) }

    fun selectSkipIntro(action: SegmentAction) = updateSettings { it.copy(skipIntro = action) }

    fun selectSkipOutro(action: SegmentAction) = updateSettings { it.copy(skipOutro = action) }

    fun selectSkipRecap(action: SegmentAction) = updateSettings { it.copy(skipRecap = action) }

    fun selectSkipPreview(action: SegmentAction) = updateSettings { it.copy(skipPreview = action) }

    fun selectSkipCommercial(action: SegmentAction) = updateSettings { it.copy(skipCommercial = action) }

    /** Home top bar's clock, default on. */
    fun toggleShowClock() = updateSettings { it.copy(showClock = !it.showClock) }

    /** Advanced-settings toggle (Playback section, "Advanced" group, default on) gating
     * device-profile/player level tolerance. */
    fun toggleTolerateMislabeledLevels() =
        updateSettings { it.copy(tolerateMislabeledLevels = !it.tolerateMislabeledLevels) }

    /** Playback section, "Mini player" row (docs/17-mini-player.md §4), default off. */
    fun toggleMiniPlayer() = updateSettings { it.copy(miniPlayerEnabled = !it.miniPlayerEnabled) }

    fun togglePreferFfmpegTrueHd() =
        updateSettings { it.copy(preferFfmpegTrueHd = !it.preferFfmpegTrueHd) }

    fun togglePreferFfmpegDts() =
        updateSettings { it.copy(preferFfmpegDts = !it.preferFfmpegDts) }

    fun togglePreferFfmpegDtsHd() =
        updateSettings { it.copy(preferFfmpegDtsHd = !it.preferFfmpegDtsHd) }

    /** Advanced-settings toggle (Playback section, "Advanced" group, default on) gating
     * `JellybeamCore::preload_playback`. */
    fun togglePreloadOnFocus() =
        updateSettings { it.copy(preloadOnFocus = !it.preloadOnFocus) }

    /** OSD section, "OSD detail" row (docs/jellybeam-osd-handoff §7/§11): picks
     * [OsdDetailSetting.MINIMAL] or [OsdDetailSetting.FULL] directly. */
    fun selectOsdDetail(value: OsdDetailSetting) = updateSettings { it.copy(osdDetail = value) }

    /** OSD section, "Seek preview" row (docs/12 §11). */
    fun selectSeekPreviewSize(value: SeekPreviewSize) = updateSettings { it.copy(seekPreviewSize = value) }

    /**
     * "Still watching?" group, under Autoplay -- "Ask after" row: Off / Episodes / Hours. Only
     * meaningful when [Settings.autoplayEnabled] is true, but this mutator is not gated by it.
     */
    fun selectStillWatchingMode(mode: StillWatchingMode) =
        updateSettings { it.copy(stillWatching = it.stillWatching.copy(mode = mode)) }

    /** "Still watching?" group, "Episodes" row (shown only when mode == AfterEpisodes). */
    fun selectStillWatchingEpisodes(episodes: UInt) =
        updateSettings { it.copy(stillWatching = it.stillWatching.copy(episodes = episodes)) }

    /** "Still watching?" group, "Hours" row (shown only when mode == AfterHours). */
    fun selectStillWatchingHours(hours: Float) =
        updateSettings { it.copy(stillWatching = it.stillWatching.copy(hours = hours)) }

    /** "Still watching?" group, "Answer timeout" row (shown whenever mode != Off). */
    fun selectStillWatchingTimeout(timeoutSecs: UInt) =
        updateSettings { it.copy(stillWatching = it.stillWatching.copy(timeoutSecs = timeoutSecs)) }

    /**
     * "Still watching?" group, "Button presses reset the count" row (shown whenever mode != Off):
     * `true` resets both counters on any qualifying input; `false` ignores remote input, counting
     * autoplay transitions/elapsed time through seeks and pauses (the two explicit resets still
     * fire).
     */
    fun toggleStillWatchingResetOnInput() =
        updateSettings {
            it.copy(stillWatching = it.stillWatching.copy(resetOnInput = !it.stillWatching.resetOnInput))
        }

    // ---- Troubleshooting (docs/21-user-reporting.md §6) --------------------

    /** Diagnostic logging toggle: persists the field and starts/stops the recorder together, so
     * a process restart before the write lands still reflects the row the viewer saw flip. */
    fun toggleDiagnosticLogging() {
        val enabling = !_state.value.settings.diagnosticLoggingEnabled
        updateSettings { it.copy(diagnosticLoggingEnabled = enabling) }
        diagnostics.setDiagnosticLoggingEnabled(enabling)
    }

    /** Crash reports toggle: turning it off also discards any pending capture (docs/21 §6 --
     * "no capture silently" mode), so a re-enable later never resurfaces a stale crash. */
    fun toggleCrashReports() {
        val enabling = !_state.value.settings.crashReportsEnabled
        updateSettings { it.copy(crashReportsEnabled = enabling) }
        diagnostics.setCrashReportsEnabled(enabling)
        if (!enabling) {
            diagnostics.discardCrash()
            refreshCrashPending()
        }
    }

    /** "Clear log" action row: ring, flushed file, and any crash capture, all at once. */
    fun clearDiagnostics() {
        diagnostics.clearDiagnostics()
        refreshCrashPending()
    }
}

class SettingsViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
        return SettingsViewModel(gateway) as T
    }
}
