package tv.jellybeam.ui.home

import androidx.compose.runtime.Composable
import uniffi.jellybeam_core.HomeLayout

/** docs/25 §5.3: Home as the host sees it -- the session's committed [layout], whatever it is. */
@Composable
fun HomeHost(layout: HomeLayout, args: HomeHostArgs) {
    layout.spec.Screen(args)
}
