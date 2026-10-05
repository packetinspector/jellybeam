package tv.jellybeam.ui.home.classic

import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.ui.cards.testCard
import tv.jellybeam.ui.home.FakeHomeGateway
import tv.jellybeam.ui.home.classicSnapshot
import tv.jellybeam.ui.home.latestShelf
import tv.jellybeam.ui.home.resume
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeShelf
import uniffi.jellybeam_core.ShelfSource

/** Classic's own half: titles, card treatment and keys for the core's shelves, and reuse of
 * unchanged sections across refreshes. The refresh machinery itself is [tv.jellybeam.ui.home.common.HomeFeedTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassicHomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Must match [tv.jellybeam.ui.common.ChangeRefreshScheduler]'s default `visiblePeriodMs`. */
    private val testVisiblePeriodMs = 500L

    /** The feed's poll loop runs on `viewModelScope`; clear it before `runTest` ends. */
    private inline fun withViewModel(gateway: CoreGateway, block: (ClassicHomeViewModel) -> Unit) {
        val viewModel = ClassicHomeViewModel(gateway)
        try {
            block(viewModel)
        } finally {
            ViewModelStore().apply { put("home", viewModel) }.clear()
        }
    }

    @Test
    fun `shelves keep the core's order and get their titles and card treatment`() {
        val shelves = buildShelves(
            listOf(
                HomeShelf(ShelfSource.ContinueWatching, listOf(testCard(id = "r1"))),
                HomeShelf(ShelfSource.NextUp, listOf(testCard(id = "n1"))),
                HomeShelf(ShelfSource.Favorites, listOf(testCard(id = "f1"))),
                latestShelf("v1", "Movies", listOf(testCard(id = "m1"))),
                latestShelf("v2", "TV Shows", listOf(testCard(id = "t1"))),
            ),
            "Continue Watching",
            "Next Up",
            "Favorites",
            resumeAsPosters = false,
        ) { "Latest in $it" }

        assertEquals(
            listOf("Continue Watching", "Next Up", "Favorites", "Latest in Movies", "Latest in TV Shows"),
            shelves.map { it.title },
        )
        assertEquals(
            listOf(ShelfKind.RESUME, ShelfKind.RESUME, ShelfKind.POSTER, ShelfKind.POSTER, ShelfKind.POSTER),
            shelves.map { it.kind },
        )
    }

    @Test
    fun `resume posters turns only Continue Watching and Next Up into poster shelves`() {
        val shelves = buildShelves(
            listOf(
                HomeShelf(ShelfSource.ContinueWatching, listOf(testCard(id = "r1"))),
                HomeShelf(ShelfSource.NextUp, listOf(testCard(id = "n1"))),
                latestShelf("v1", "Movies", listOf(testCard(id = "m1"))),
            ),
            "Continue Watching",
            "Next Up",
            "Favorites",
            resumeAsPosters = true,
        ) { "Latest in $it" }

        assertEquals(listOf(ShelfKind.POSTER, ShelfKind.POSTER, ShelfKind.POSTER), shelves.map { it.kind })
        assertEquals(listOf("resume", "next-up", "latest:v1"), shelves.map { it.id })
    }

    @Test
    fun `shelf keys stay the strings focus memory already stores`() {
        assertEquals("resume", ShelfSource.ContinueWatching.key())
        assertEquals("next-up", ShelfSource.NextUp.key())
        assertEquals("favorites", ShelfSource.Favorites.key())
        assertEquals("latest:v1", ShelfSource.Latest(viewId = "v1", viewName = "Movies").key())
    }

    @Test
    fun `without a hero the content clears the masthead`() {
        assertEquals(MASTHEAD_HEIGHT, contentTopInset(hero = null))
        assertEquals(0.dp, contentTopInset(hero = testCard(id = "r1")))
    }

    @Test
    fun `an unchanged section keeps the same list instance after a debounced refresh`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r1"))),
        )
        withViewModel(gateway) { viewModel ->
            val resumeBefore = viewModel.state.value.content.resume

            // Structurally equal but a new instance, matching what a real FFI marshal produces.
            gateway.homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r1")))
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            assertSame(
                "an unchanged section must reuse the existing list instance, not the fresh FFI one",
                resumeBefore,
                viewModel.state.value.content.resume,
            )
        }
    }

    @Test
    fun `a changed section is replaced with the new content`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r1"))),
        )
        withViewModel(gateway) { viewModel ->
            val resumeBefore = viewModel.state.value.content.resume

            gateway.homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r2")))
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            assertNotSame(resumeBefore, viewModel.state.value.content.resume)
            assertEquals(listOf("r2"), viewModel.state.value.content.resume.map { it.id })
        }
    }

    @Test
    fun `one changed latest shelf does not reallocate its unchanged sibling`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = classicSnapshot(
                latest = listOf(
                    latestShelf("a", "Movies", listOf(testCard(id = "m1"))),
                    latestShelf("b", "TV Shows", listOf(testCard(id = "t1"))),
                ),
            ),
        )
        withViewModel(gateway) { viewModel ->
            val shelfABefore = viewModel.state.value.content.shelves.first { it.source.key() == "latest:a" }

            // Fresh instances with shelf a's same content, as a real refresh would marshal.
            gateway.homeSnapshotResult = classicSnapshot(
                latest = listOf(
                    latestShelf("a", "Movies", listOf(testCard(id = "m1"))),
                    latestShelf("b", "TV Shows", listOf(testCard(id = "t1"), testCard(id = "t2"))),
                ),
            )
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            val after = viewModel.state.value.content.shelves
            assertSame(
                "shelf a's content didn't change, so its HomeShelf instance must be reused",
                shelfABefore,
                after.first { it.source.key() == "latest:a" },
            )
            assertEquals(listOf("t1", "t2"), after.first { it.source.key() == "latest:b" }.cards.map { it.id })
        }
    }

    @Test
    fun `home asks the core for the classic layout`() = runTest {
        val gateway = FakeHomeGateway()
        withViewModel(gateway) {
            assertEquals(listOf(HomeLayout.CLASSIC), gateway.requestedLayouts)
        }
    }
}
