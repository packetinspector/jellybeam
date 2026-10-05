package tv.jellybeam.ui.settings

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.defaultTestSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackQuality
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.StillWatchingMode
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** [DiagnosticsController] test double (docs/21 §6) -- never touches [tv.jellybeam.AppGraph]. */
private class FakeDiagnosticsController : DiagnosticsController {
    val setDiagnosticLoggingCalls = mutableListOf<Boolean>()
    val setCrashReportsCalls = mutableListOf<Boolean>()
    var discardCrashCallCount = 0
        private set
    var clearDiagnosticsCallCount = 0
        private set
    var crashPendingValue = false

    override fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        setDiagnosticLoggingCalls.add(enabled)
    }

    override fun setCrashReportsEnabled(enabled: Boolean) {
        setCrashReportsCalls.add(enabled)
    }

    override fun discardCrash() {
        discardCrashCallCount++
        crashPendingValue = false
    }

    override fun clearDiagnostics() {
        clearDiagnosticsCallCount++
        crashPendingValue = false
    }

    override fun crashPending(): Boolean = crashPendingValue
}

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val movies = ViewSnapshot(id = "view-movies", name = "Movies", kind = ViewKind.LIBRARY)
    private val shows = ViewSnapshot(id = "view-shows", name = "TV Shows", kind = ViewKind.LIBRARY)

    @Test
    fun `rapid edits persist in input order behind a suspended write`() = runTest {
        val seeded = defaultTestSettings()
        val fake = FakeCoreGateway(settings = seeded)
        val releaseFirst = CompletableDeferred<Unit>()
        val started = mutableListOf<Settings>()
        val persisted = mutableListOf<Settings>()
        val gateway = object : CoreGateway by fake {
            override suspend fun setSettings(settings: Settings) {
                started += settings
                if (started.size == 1) releaseFirst.await()
                persisted += settings
            }
        }
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipBack(30u)
        viewModel.selectSkipForward(60u)

        val first = seeded.copy(skipBackSecs = 30u)
        val latest = first.copy(skipForwardSecs = 60u)
        assertEquals(latest, viewModel.state.value.settings)
        assertEquals(listOf(first), started)
        assertTrue(persisted.isEmpty())

        releaseFirst.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf(first, latest), persisted)
    }

    // ---- defaultSettings() placeholder --------------------------------

    @Test
    fun `defaultSettings defaults osdDetail to FULL`() {
        assertEquals(OsdDetailSetting.FULL, defaultSettings().osdDetail)
    }

    // ---- Loading (rows reflect getSettings/views on entry) ----------------

    @Test
    fun `state loads the whole settings record and the full view list on entry`() = runTest {
        val seeded = defaultTestSettings().copy(nextUpRewatching = true, skipBackSecs = 30u)
        val gateway = FakeCoreGateway(viewsList = listOf(movies, shows), settings = seeded)

        val viewModel = SettingsViewModel(gateway)

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(seeded, viewModel.state.value.settings)
        assertEquals(listOf(movies, shows), viewModel.state.value.views)
    }

    // ---- Toggle rows write the whole record, one field changed -----------

    @Test
    fun `toggling next-up rewatching flips only that field and writes the whole record`() = runTest {
        val seeded = defaultTestSettings().copy(nextUpRewatching = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleNextUpRewatching()

        val expected = seeded.copy(nextUpRewatching = true)
        assertEquals(expected, viewModel.state.value.settings)
        assertEquals(listOf(expected), gateway.setSettingsCalls)
    }

    @Test
    fun `toggling hide-watched-in-latest flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(hideWatchedInLatest = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleHideWatchedInLatest()

        assertEquals(seeded.copy(hideWatchedInLatest = true), viewModel.state.value.settings)
        assertEquals(seeded.copy(hideWatchedInLatest = true), gateway.setSettingsCalls.single())
    }

    @Test
    fun `toggling show-virtual-episodes flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(showVirtualEpisodes = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleShowVirtualEpisodes()

        assertEquals(seeded.copy(showVirtualEpisodes = true), viewModel.state.value.settings)
        assertEquals(seeded.copy(showVirtualEpisodes = true), gateway.setSettingsCalls.single())
    }

    @Test
    fun `toggling autoplay enabled flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(autoplayEnabled = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleAutoplayEnabled()

        assertFalse(viewModel.state.value.settings.autoplayEnabled)
        // Every other field must be untouched by the write-through.
        assertEquals(seeded.copy(autoplayEnabled = false), viewModel.state.value.settings)
    }

    // ---- Library toggle: add/remove from hiddenLibraryIds -----------------

    @Test
    fun `toggling a shown library hides it (adds to hiddenLibraryIds)`() = runTest {
        val seeded = defaultTestSettings().copy(hiddenLibraryIds = emptyList())
        val gateway = FakeCoreGateway(viewsList = listOf(movies, shows), settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleLibraryVisibility(movies.id)

        assertEquals(listOf(movies.id), viewModel.state.value.settings.hiddenLibraryIds)
    }

    @Test
    fun `toggling an already-hidden library shows it again (removes from hiddenLibraryIds)`() = runTest {
        val seeded = defaultTestSettings().copy(hiddenLibraryIds = listOf(movies.id, shows.id))
        val gateway = FakeCoreGateway(viewsList = listOf(movies, shows), settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleLibraryVisibility(movies.id)

        assertEquals(listOf(shows.id), viewModel.state.value.settings.hiddenLibraryIds)
    }

    // ---- Chip rows select a value directly, write the whole record --------

    @Test
    fun `selecting a next-up cutoff chip sets that value exactly`() = runTest {
        val seeded = defaultTestSettings().copy(nextUpCutoffDays = null)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectNextUpCutoff(90u)

        assertEquals(90u, viewModel.state.value.settings.nextUpCutoffDays)
    }

    @Test
    fun `selecting a shelf size chip sets that value exactly`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings().copy(homeShelfSize = 20u))
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectHomeShelfSize(30u)

        assertEquals(30u, viewModel.state.value.settings.homeShelfSize)
    }

    @Test
    fun `the resume posters toggle flips the home setting`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings().copy(homeResumePosters = false))
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleHomeResumePosters()

        assertEquals(true, viewModel.state.value.settings.homeResumePosters)
        assertEquals(true, gateway.setSettingsCalls.single().homeResumePosters)
    }

    @Test
    fun `the favorites row toggle flips the home setting`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings().copy(homeShowFavorites = true))
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleHomeShowFavorites()

        assertEquals(false, viewModel.state.value.settings.homeShowFavorites)
    }

    @Test
    fun `selecting Off for next-up cutoff sets it back to null`() = runTest {
        val seeded = defaultTestSettings().copy(nextUpCutoffDays = 365u)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectNextUpCutoff(null)

        assertNull(viewModel.state.value.settings.nextUpCutoffDays)
    }

    @Test
    fun `selecting a skip-back chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipBackSecs = 10u)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipBack(30u)

        assertEquals(seeded.copy(skipBackSecs = 30u), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a skip-forward chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipForwardSecs = 10u)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipForward(60u)

        assertEquals(seeded.copy(skipForwardSecs = 60u), viewModel.state.value.settings)
    }

    @Test
    fun `selecting an autoplay-delay chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(autoplayDelaySecs = 5u)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectAutoplayDelay(30u)

        assertEquals(seeded.copy(autoplayDelaySecs = 30u), viewModel.state.value.settings)
    }

    // ---- Playback › Quality (docs/18-playback-quality.md §1/§3) -----------

    @Test
    fun `selectPlaybackQuality persists the chosen mode`() = runTest {
        val seeded = defaultTestSettings().copy(playbackQuality = PlaybackQuality.DirectPlay)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectPlaybackQuality(PlaybackQuality.Auto)
        assertEquals(seeded.copy(playbackQuality = PlaybackQuality.Auto), viewModel.state.value.settings)

        viewModel.selectPlaybackQuality(PlaybackQuality.Cap(maxBps = 8_000_000u))
        assertEquals(
            seeded.copy(playbackQuality = PlaybackQuality.Cap(maxBps = 8_000_000u)),
            viewModel.state.value.settings,
        )
    }

    // ---- "Still watching?" group (docs/feature-dev/spec-still-watching-and-lan-discovery.md
    // Feature A) ----

    @Test
    fun `selecting a still-watching mode chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(
            stillWatching = defaultTestSettings().stillWatching.copy(mode = StillWatchingMode.AFTER_EPISODES),
        )
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStillWatchingMode(StillWatchingMode.AFTER_HOURS)

        assertEquals(
            seeded.copy(stillWatching = seeded.stillWatching.copy(mode = StillWatchingMode.AFTER_HOURS)),
            viewModel.state.value.settings,
        )
    }

    @Test
    fun `selecting a still-watching episodes chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(
            stillWatching = defaultTestSettings().stillWatching.copy(episodes = 3u),
        )
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStillWatchingEpisodes(5u)

        assertEquals(
            seeded.copy(stillWatching = seeded.stillWatching.copy(episodes = 5u)),
            viewModel.state.value.settings,
        )
    }

    @Test
    fun `selecting a still-watching hours chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(
            stillWatching = defaultTestSettings().stillWatching.copy(hours = 3f),
        )
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStillWatchingHours(2f)

        assertEquals(
            seeded.copy(stillWatching = seeded.stillWatching.copy(hours = 2f)),
            viewModel.state.value.settings,
        )
    }

    @Test
    fun `selecting a still-watching timeout chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(
            stillWatching = defaultTestSettings().stillWatching.copy(timeoutSecs = 120u),
        )
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStillWatchingTimeout(60u)

        assertEquals(
            seeded.copy(stillWatching = seeded.stillWatching.copy(timeoutSecs = 60u)),
            viewModel.state.value.settings,
        )
    }

    @Test
    fun `toggling still-watching reset-on-input writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(
            stillWatching = defaultTestSettings().stillWatching.copy(resetOnInput = true),
        )
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleStillWatchingResetOnInput()

        assertEquals(
            seeded.copy(stillWatching = seeded.stillWatching.copy(resetOnInput = false)),
            viewModel.state.value.settings,
        )
    }

    @Test
    fun `selecting a startup-screen chip stores that view's id`() = runTest {
        val seeded = defaultTestSettings().copy(startupScreenViewId = null)
        val gateway = FakeCoreGateway(viewsList = listOf(movies, shows), settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStartupScreen(shows.id)

        assertEquals(shows.id, viewModel.state.value.settings.startupScreenViewId)
    }

    @Test
    fun `selecting Home for startup screen stores null`() = runTest {
        val seeded = defaultTestSettings().copy(startupScreenViewId = movies.id)
        val gateway = FakeCoreGateway(viewsList = listOf(movies, shows), settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectStartupScreen(null)

        assertNull(viewModel.state.value.settings.startupScreenViewId)
    }

    // ---- Subtitle style rows (docs/09-settings-plan.md subtitle appearance) --

    @Test
    fun `selecting a subtitle scale chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(subtitleScale = 1.0f)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSubtitleScale(1.5f)

        assertEquals(seeded.copy(subtitleScale = 1.5f), viewModel.state.value.settings)
        assertEquals(seeded.copy(subtitleScale = 1.5f), gateway.setSettingsCalls.single())
    }

    @Test
    fun `selecting a subtitle position chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(subtitlePosition = SubtitlePositionPreset.DEFAULT)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSubtitlePosition(SubtitlePositionPreset.HIGHEST)

        assertEquals(seeded.copy(subtitlePosition = SubtitlePositionPreset.HIGHEST), viewModel.state.value.settings)
    }

    @Test
    fun `toggling subtitle bold flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(subtitleBold = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleSubtitleBold()

        assertTrue(viewModel.state.value.settings.subtitleBold)
        assertEquals(seeded.copy(subtitleBold = true), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a subtitle background opacity chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(subtitleBackgroundOpacity = 0.0f)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSubtitleBackgroundOpacity(0.75f)

        assertEquals(seeded.copy(subtitleBackgroundOpacity = 0.75f), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a subtitle color chip writes only that field`() = runTest {
        val seeded = defaultTestSettings()
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSubtitleColor(SubtitleColorPreset.LIGHT_GREEN)

        assertEquals(seeded.copy(subtitleColor = SubtitleColorPreset.LIGHT_GREEN), viewModel.state.value.settings)
    }

    @Test
    fun `toggling system subtitle style flips only that field`() = runTest {
        val seeded = defaultTestSettings()
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleSubtitleUseSystemStyle()

        assertEquals(seeded.copy(subtitleUseSystemStyle = true), viewModel.state.value.settings)
    }

    // ---- Skip-segment rows (docs/09-settings-plan.md skip-segment settings) --

    @Test
    fun `selecting a skip-intro action writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipIntro = SegmentAction.ASK)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipIntro(SegmentAction.OFF)

        assertEquals(seeded.copy(skipIntro = SegmentAction.OFF), viewModel.state.value.settings)
        assertEquals(seeded.copy(skipIntro = SegmentAction.OFF), gateway.setSettingsCalls.single())
    }

    @Test
    fun `selecting a skip-outro action writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipOutro = SegmentAction.ASK)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipOutro(SegmentAction.AUTO_SKIP)

        assertEquals(seeded.copy(skipOutro = SegmentAction.AUTO_SKIP), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a skip-recap action writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipRecap = SegmentAction.ASK)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipRecap(SegmentAction.OFF)

        assertEquals(seeded.copy(skipRecap = SegmentAction.OFF), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a skip-preview action writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipPreview = SegmentAction.ASK)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipPreview(SegmentAction.AUTO_SKIP)

        assertEquals(seeded.copy(skipPreview = SegmentAction.AUTO_SKIP), viewModel.state.value.settings)
    }

    @Test
    fun `selecting a skip-commercial action writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(skipCommercial = SegmentAction.AUTO_SKIP)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectSkipCommercial(SegmentAction.ASK)

        assertEquals(seeded.copy(skipCommercial = SegmentAction.ASK), viewModel.state.value.settings)
    }

    // ---- Clock --------------------------------------------------------

    @Test
    fun `toggling show clock flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(showClock = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleShowClock()

        assertEquals(seeded.copy(showClock = false), viewModel.state.value.settings)
        assertEquals(seeded.copy(showClock = false), gateway.setSettingsCalls.single())
    }

    // ---- Advanced: tolerate mislabeled codec levels ------------------------

    @Test
    fun `toggling tolerate mislabeled levels flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(tolerateMislabeledLevels = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleTolerateMislabeledLevels()

        assertEquals(seeded.copy(tolerateMislabeledLevels = false), viewModel.state.value.settings)
        assertEquals(seeded.copy(tolerateMislabeledLevels = false), gateway.setSettingsCalls.single())
    }

    // ---- Advanced: preload on focus ----------------------------------------

    @Test
    fun `toggling preload on focus flips only that field`() = runTest {
        val seeded = defaultTestSettings().copy(preloadOnFocus = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.togglePreloadOnFocus()

        assertEquals(seeded.copy(preloadOnFocus = false), viewModel.state.value.settings)
        assertEquals(seeded.copy(preloadOnFocus = false), gateway.setSettingsCalls.single())
    }

    // ---- Mini player (docs/17-mini-player.md) ------------------------------

    @Test
    fun `toggleMiniPlayer flips the flag and persists`() = runTest {
        val seeded = defaultTestSettings().copy(miniPlayerEnabled = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.toggleMiniPlayer()

        assertEquals(seeded.copy(miniPlayerEnabled = true), viewModel.state.value.settings)
        assertEquals(seeded.copy(miniPlayerEnabled = true), gateway.setSettingsCalls.single())
    }

    // ---- OSD detail (docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md §§7/11) ----

    @Test
    fun `selecting an OSD detail chip writes only that field`() = runTest {
        val seeded = defaultTestSettings().copy(osdDetail = OsdDetailSetting.FULL)
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.selectOsdDetail(OsdDetailSetting.MINIMAL)

        assertEquals(seeded.copy(osdDetail = OsdDetailSetting.MINIMAL), viewModel.state.value.settings)
        assertEquals(seeded.copy(osdDetail = OsdDetailSetting.MINIMAL), gateway.setSettingsCalls.single())
    }

    @Test
    fun `FFmpeg audio toggles independently write only their codec preference`() = runTest {
        val seeded = defaultTestSettings()
        val gateway = FakeCoreGateway(settings = seeded)
        val viewModel = SettingsViewModel(gateway)

        viewModel.togglePreferFfmpegTrueHd()
        assertEquals(seeded.copy(preferFfmpegTrueHd = true), viewModel.state.value.settings)

        viewModel.togglePreferFfmpegDts()
        assertEquals(
            seeded.copy(preferFfmpegTrueHd = true, preferFfmpegDts = true),
            viewModel.state.value.settings,
        )

        viewModel.togglePreferFfmpegDtsHd()
        assertEquals(
            seeded.copy(preferFfmpegTrueHd = true, preferFfmpegDts = true, preferFfmpegDtsHd = true),
            viewModel.state.value.settings,
        )
    }

    // ---- Troubleshooting (docs/21-user-reporting.md §6) --------------------

    @Test
    fun `toggling diagnostic logging persists the field and calls the recorder`() = runTest {
        val seeded = defaultTestSettings().copy(diagnosticLoggingEnabled = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val diagnostics = FakeDiagnosticsController()
        val viewModel = SettingsViewModel(gateway, diagnostics = diagnostics)

        viewModel.toggleDiagnosticLogging()

        assertEquals(seeded.copy(diagnosticLoggingEnabled = true), viewModel.state.value.settings)
        assertEquals(seeded.copy(diagnosticLoggingEnabled = true), gateway.setSettingsCalls.single())
        assertEquals(listOf(true), diagnostics.setDiagnosticLoggingCalls)
    }

    @Test
    fun `turning crash reports off discards a pending capture`() = runTest {
        val seeded = defaultTestSettings().copy(crashReportsEnabled = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val diagnostics = FakeDiagnosticsController().apply { crashPendingValue = true }
        val viewModel = SettingsViewModel(gateway, diagnostics = diagnostics)

        viewModel.toggleCrashReports()

        assertEquals(seeded.copy(crashReportsEnabled = false), viewModel.state.value.settings)
        assertEquals(listOf(false), diagnostics.setCrashReportsCalls)
        assertEquals(1, diagnostics.discardCrashCallCount)
        assertFalse(viewModel.state.value.crashPending)
    }

    @Test
    fun `turning crash reports on does not discard anything`() = runTest {
        val seeded = defaultTestSettings().copy(crashReportsEnabled = false)
        val gateway = FakeCoreGateway(settings = seeded)
        val diagnostics = FakeDiagnosticsController()
        val viewModel = SettingsViewModel(gateway, diagnostics = diagnostics)

        viewModel.toggleCrashReports()

        assertEquals(seeded.copy(crashReportsEnabled = true), viewModel.state.value.settings)
        assertEquals(0, diagnostics.discardCrashCallCount)
    }

    @Test
    fun `clearDiagnostics clears both the log and any crash capture`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings())
        val diagnostics = FakeDiagnosticsController().apply { crashPendingValue = true }
        val viewModel = SettingsViewModel(gateway, diagnostics = diagnostics)

        viewModel.clearDiagnostics()

        assertEquals(1, diagnostics.clearDiagnosticsCallCount)
        assertFalse(viewModel.state.value.crashPending)
    }
}

/** [toggleHiddenLibrary]/[startupScreenOptionIds]/[selectedChipIndex] as pure functions,
 * independent of the ViewModel wiring above.
 */
class SettingsPresetFunctionsTest {

    @Test
    fun `toggleHiddenLibrary adds an id that was not hidden`() {
        assertEquals(listOf("v1"), toggleHiddenLibrary(emptyList(), "v1"))
    }

    @Test
    fun `toggleHiddenLibrary removes an id that was already hidden`() {
        assertEquals(listOf("v2"), toggleHiddenLibrary(listOf("v1", "v2"), "v1"))
    }

    @Test
    fun `startupScreenOptionIds puts Home (null) first, then views in order`() {
        val views = listOf(ViewSnapshot(id = "v1", name = "Movies", kind = ViewKind.LIBRARY), ViewSnapshot(id = "v2", name = "Shows", kind = ViewKind.LIBRARY))
        assertEquals(listOf(null, "v1", "v2"), startupScreenOptionIds(views))
    }

    @Test
    fun `startupScreenOptionIds is just Home with no views`() {
        assertEquals(listOf<String?>(null), startupScreenOptionIds(emptyList()))
        assertTrue(startupScreenOptionIds(emptyList()).size == 1)
    }

    // ---- selectedChipIndex: which chip (if any) reads as filled -----------

    @Test
    fun `selectedChipIndex finds the matching option`() {
        assertEquals(2, selectedChipIndex(listOf(5, 10, 15, 30, 60), 15))
    }

    @Test
    fun `selectedChipIndex returns -1 for a value not in the options, for example a stale id`() {
        assertEquals(-1, selectedChipIndex(listOf("a", "b", "c"), "stale"))
    }

    @Test
    fun `selectedChipIndex treats null as an ordinary option value`() {
        val options: List<UInt?> = listOf(null, 7u, 14u)
        assertEquals(0, selectedChipIndex(options, null))
        assertEquals(1, selectedChipIndex(options, 7u))
    }
}
