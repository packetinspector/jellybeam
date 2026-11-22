rootProject.name = "jellybeam-tv"

include(":app")
// docs/30: the music visualizer engine and its debug-only lab app.
include(":visualizer")
include(":visualizer-lab")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // docs/06 "Patched Media3": the one artifact tools/media3-patch builds, and nothing else from here.
        exclusiveContent {
            forRepository { maven { url = uri("third_party/maven") } }
            filter { includeVersion("androidx.media3", "media3-exoplayer", "1.11.1-jb1") }
        }
        google()
        mavenCentral()
    }
}
