// Root build script. Minimal: just declares the AGP/Kotlin plugins so
// versions are resolved once (per the version catalog) and `:app` can
// `apply` them without redeclaring versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
