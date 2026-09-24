package tv.jellybeam.nav

import uniffi.jellybeam_core.ViewSnapshot

/**
 * Startup-screen resolution (docs/07 §5 / docs/09-settings-plan.md): the persisted
 * `Settings.startupScreenViewId` is validated against the session's current views, since a
 * saved id can outlive the library it named. `null` or a stale id both resolve to `null`
 * ("land on Home only"); [tv.jellybeam.MainActivity] pushes [Screen.Library] only when non-null.
 */
fun resolveStartupView(startupScreenViewId: String?, views: List<ViewSnapshot>): ViewSnapshot? {
    if (startupScreenViewId == null) return null
    return views.firstOrNull { it.id == startupScreenViewId }
}
