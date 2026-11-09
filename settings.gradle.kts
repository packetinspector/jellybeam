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
        google()
        mavenCentral()
    }
}
