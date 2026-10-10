package tv.jellybeam.ui.home.common

import org.junit.Test
import org.junit.Assert.assertEquals

class HomeRevealTest {
    @Test
    fun loadedAtFirstCompositionRevealsAtOnce() {
        assertEquals(HomeRevealStart(contentRevealed = true, showLoadingSkeleton = false), homeRevealStart(isLoading = false))
    }

    @Test
    fun loadingAtFirstCompositionShowsTheSkeleton() {
        assertEquals(HomeRevealStart(contentRevealed = false, showLoadingSkeleton = true), homeRevealStart(isLoading = true))
    }
}
