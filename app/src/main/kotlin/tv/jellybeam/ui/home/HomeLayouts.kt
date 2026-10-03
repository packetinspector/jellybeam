package tv.jellybeam.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import tv.jellybeam.ui.home.classic.ClassicLayout
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.HomeLayout

/** docs/25 §5.3: what the host hands every layout; the same obligations Classic meets. A data
 * class, so an equal set of args lets Compose skip the layout when the root recomposes.
 */
data class HomeHostArgs(
    val onOpenDetail: (Card) -> Unit,
    val isTop: Boolean,
    /** JellybeamRoot's focus gate; see [tv.jellybeam.MainActivity]'s `RetainedScreenLayer`. */
    val focusGate: MutableState<Boolean>,
)

/** docs/25 §5.2: a layout's single entry point on the UI side. */
interface HomeLayoutSpec {
    @Composable
    fun Screen(args: HomeHostArgs)
}

/** docs/25 §5.2: the only place that names every layout. An arm that doesn't run never loads
 * its layout's classes.
 */
val HomeLayout.spec: HomeLayoutSpec
    get() = when (this) {
        HomeLayout.CLASSIC -> ClassicLayout
    }
