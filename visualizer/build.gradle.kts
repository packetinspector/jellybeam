import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// docs/30 §1: the visualizer engine. No dependency beyond the Kotlin stdlib, so it builds and
// JVM-tests in seconds without the Rust core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.jellybeam.visualizer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        ignoreTestSources = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// docs/30 §9 T14: `-Pviz.stressSeconds=30` runs the ring stress test for the plan's full 30 s.
val stressSeconds = providers.gradleProperty("viz.stressSeconds")
tasks.withType<Test>().configureEach {
    stressSeconds.orNull?.let { systemProperty("viz.stressSeconds", it) }
}

dependencies {
    testImplementation(libs.junit)
}
