// Root build script. Minimal: just declares the AGP/Kotlin plugins so
// versions are resolved once (per the version catalog) and `:app` can
// `apply` them without redeclaring versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// docs/06 "Patched Media3": every request for media3-exoplayer (ours, media3-session's, the ffmpeg
// AAR's 1.9.0) resolves to the patched build, so one ExoPlayer is on every classpath.
val patchedExoplayer = libs.androidx.media3.exoplayer.asProvider().get().toString()
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.media3:media3-exoplayer"))
                .using(module(patchedExoplayer))
                .because("docs/06: Media3 1.11.1 with upstream 4c95cd9 backported")
        }
    }
}
