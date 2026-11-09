import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// docs/30 §9: debug-only lab app that drives :visualizer from synthetic signals for golden,
// pacing, lifecycle, perf and sync tests on the emulator. It never ships: release is disabled.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.jellybeam.visualizer.lab"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tv.jellybeam.visualizer.lab"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        ignoreTestSources = true
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":visualizer"))
    implementation(libs.androidx.media3.exoplayer)
    // androidx.annotation.OptIn for Media3's unstable audio-output API.
    implementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
