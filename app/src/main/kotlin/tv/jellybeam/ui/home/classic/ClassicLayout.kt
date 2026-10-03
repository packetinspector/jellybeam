package tv.jellybeam.ui.home.classic

import androidx.compose.runtime.Composable
import tv.jellybeam.ui.home.HomeHostArgs
import tv.jellybeam.ui.home.HomeLayoutSpec

/** docs/07 §1's Classic Home, as the host sees it. */
object ClassicLayout : HomeLayoutSpec {
    @Composable
    override fun Screen(args: HomeHostArgs) {
        ClassicHomeScreen(onOpenDetail = args.onOpenDetail, isTop = args.isTop, focusGate = args.focusGate)
    }
}
