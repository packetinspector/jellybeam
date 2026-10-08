import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// docs/27 §2: the languages that ship. A translation in res/values-xx/ stays out of the APK
// (and out of the system's per-app language list) until its code is added here.
val shippedLanguages = listOf("en")

// One flag drives the BuildConfig field, signing and the cargo feature (docs/26 §7).
val updateFixtureBuild = providers.gradleProperty("jellybeam.updateFixture").orNull == "true"

android {
    namespace = "tv.jellybeam"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // The build image's NDK (Dockerfile `ANDROID_NDK_HOME`). Without the pin AGP looks for its
    // own default version, finds none, and packages libjellybeam_core.so unstripped.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "tv.jellybeam"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("jellybeam.updateVersionCode").orNull?.toInt() ?: 9
        versionName = providers.gradleProperty("jellybeam.updateVersionName").orNull ?: "0.1.8"
        buildConfigField("boolean", "UPDATE_FIXTURE", if (updateFixtureBuild) "true" else "false")
        buildConfigField("String", "SHIPPED_LANGUAGES", "\"${shippedLanguages.joinToString(",")}\"")
        // Only the ABIs the Rust core is built for. JNA's AAR also ships x86, mips and armeabi
        // slices; packaging them lets such a device install an APK with no core to load.
        // `-Pjellybeam.abi=armeabi-v7a` narrows a one-off build to a single slice.
        val abis = providers.gradleProperty("jellybeam.abi").orNull?.let(::listOf)
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        ndk { abiFilters += abis }
    }

    // docs/06 "Signing releases": keystore.properties (gitignored) with storeFile, storePassword,
    // keyAlias, keyPassword. storeFile resolves relative to the repository root.
    val keystoreProperties = rootProject.file("keystore.properties")
    if (keystoreProperties.exists()) {
        val props = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // docs/06 "Signing releases": a gitignored keystore.properties at the repo root
            // signs releases with a real key; without it the debug keystore signs, which keeps
            // one install identity across debug and release builds on a development device.
            signingConfig = if (updateFixtureBuild) signingConfigs.getByName("debug")
                else signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // The R8 map id in internal/mappings identifies a build; a git revision stamped into
            // META-INF would name commits that are not in the public history.
            vcsInfo { include = false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (used for the virtual-episode "Airs {date}" formatting,
        // see CardFormatting.kt) needs desugaring on our minSdk 23 floor --
        // it's only really available unconditionally from API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += shippedLanguages
        generateLocaleConfig = true
    }

    lint {
        // uniffi's own Kotlin template (uniffi/jellybeam_core/jellybeam_core.kt,
        // generated -- not ours to hand-edit) references
        // `java.lang.ref.Cleaner`, which needs API 33, behind a
        // `Class.forName("java.lang.ref.Cleaner")` runtime guard lint's
        // static NewApi check can't see through; it only ever runs that
        // branch on API 33+. Baselined rather than disabling NewApi
        // project-wide, so a real API-level mistake in our own code still
        // fails this check.
        baseline = file("lint-baseline.xml")
        // Test sources never ship; skipping them drops the unit-test and androidTest analyze
        // passes from the gate (docs/06-build-container.md).
        ignoreTestSources = true
    }

    testOptions {
        // JVM unit tests touch android.* stubs that would otherwise throw
        // "not mocked" -- concretely, media3's PlaybackException constructor
        // calls android.os.SystemClock.elapsedRealtime() for its timestamp.
        // Returning defaults (0) is harmless there and lets ViewModel tests
        // construct real player errors without Robolectric.
        unitTests.isReturnDefaultValues = true
    }

    // The uniffi-bindgen-generated Kotlin bindings (task `uniffiBindgen`
    // below) land here, outside src/main so the generated file is never
    // hand-edited or committed.
    sourceSets {
        getByName("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi/main/kotlin"))
        }
    }
}

composeCompiler {
    // app/compose_stability.conf: uniffi-generated records are declared with `var` fields, which
    // the compiler otherwise infers as unstable -- see that file's own comment.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose_stability.conf"))
}

dependencies {
    implementation(libs.apksig)
    implementation(libs.androidx.core)
    // System splash screen (docs/brand.md §6.3): installSplashScreen() in MainActivity.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // App shell: ViewModel + StateFlow-driven screens (sign-in, Home),
    // plain Compose state-based navigation -- no navigation library.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Poster/thumb loading from JellybeamCore.imageUrl(...).
    implementation(libs.coil.compose)

    // Playback: Media3 ExoPlayer + Jellyfin's ffmpeg audio decoder extension,
    // using proven renderer wiring (playback/media3/exoplayer/ExoPlayerBackend.kt).
    // media3-ui is only for PlayerView's SurfaceView/AspectRatioFrameLayout
    // plumbing -- its built-in controller is disabled (useController = false)
    // since the OSD is our own Compose overlay (player/PlaybackScreen.kt).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    // docs/18-playback-quality.md: HLS transcode fallback -- the Auto/Cap
    // quality modes load the server's transcoded stream as an HLS MediaItem
    // (PlayerHolder.load sets MimeTypes.APPLICATION_M3U8 for a Transcode plan).
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
    // MediaSessionHolder.kt: publishes the shared player to the TV system
    // UI / Assistant. No MediaSessionService dependency -- Activity-scoped
    // only, see that class's own doc comment.
    implementation(libs.androidx.media3.session)

    // Verifies/installs the embedded Baseline Profile (app/src/main/baseline-prof.txt)
    // at app startup -- profile-guided AOT on a sideloaded install without the
    // manual `cmd package compile -m speed -f` ritual. Status is logged once
    // per process from MainActivity.onResume; see docs/10-perf-logging.md
    // "Profile status".
    implementation(libs.androidx.profileinstaller)
    // docs/21 §4: QR encoding for the report screen.
    implementation(libs.zxing.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Required by the uniffi-bindgen-generated Kotlin bindings
    // (uniffi/jellybeam_core/jellybeam_core.kt): JNA for the native-library
    // loading/calling glue (the "@aar" classifier is required on Android --
    // it ships JNA's per-ABI native libs instead of a desktop .jar).
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------
// Rust core wiring: cross-compile libjellybeam_core.so via cargo-ndk, then
// generate the Kotlin FFI bindings from it via uniffi-bindgen. Both tasks
// are meant to run inside the project's Docker build container (see
// build.sh / Dockerfile), where `cargo`/`cargo-ndk` are on PATH and the
// Android NDK is at $ANDROID_NDK_HOME.
// ---------------------------------------------------------------------

val coreDir = rootProject.layout.projectDirectory.dir("core")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val uniffiOutputDir = layout.buildDirectory.dir("generated/uniffi/main/kotlin")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    description = "Cross-compiles the jellybeam-ffi cdylib for Android via cargo-ndk."
    group = "jellybeam"

    workingDir = coreDir.asFile
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        // armeabi-v7a: the supported low-memory target class runs a 32-bit
        // userspace, so this ABI remains a release requirement.
        "-t", "armeabi-v7a",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release",
        "-p", "jellybeam-ffi",
    )

    if (updateFixtureBuild) {
        args("--features", "update-test-fixture")
        environment("JELLYBEAM_UPDATE_TEST_CA", rootProject.file("internal/updates/ca.pem").absolutePath)
    }
    inputs.property("updateFixture", updateFixtureBuild)
    inputs.dir(coreDir.dir("app-updates/src"))
    inputs.file(coreDir.file("app-updates/Cargo.toml"))
    inputs.dir(coreDir.dir("ffi/src"))
    inputs.dir(coreDir.dir("media-cache/src"))
    inputs.dir(coreDir.dir("jellyfin-core/src"))
    inputs.dir(coreDir.dir("jellyfin-api/src"))
    inputs.dir(coreDir.dir("seerr-api/src"))
    inputs.dir(coreDir.dir("playback-policy/src"))
    inputs.file(coreDir.file("Cargo.toml"))
    inputs.file(coreDir.file("Cargo.lock"))
    inputs.file(coreDir.file("ffi/Cargo.toml"))
    // The fixture CA is embedded at compile time, so rotating it must rebuild the core.
    if (updateFixtureBuild) inputs.file(rootProject.file("internal/updates/ca.pem")).optional()
    outputs.dir(jniLibsDir)
    // Captured as a plain File local: referencing the script-level
    // `jniLibsDir` val from inside this predicate would capture the build
    // script object itself, which the configuration cache refuses to
    // serialize.
    val jniLibsRoot = jniLibsDir.asFile
    outputs.upToDateWhen {
        listOf("arm64-v8a", "x86_64", "armeabi-v7a").all { abi ->
            jniLibsRoot.resolve(abi).resolve("libjellybeam_core.so").exists()
        }
    }
}

val uniffiBindgen = tasks.register<Exec>("uniffiBindgen") {
    description = "Generates the Kotlin UniFFI bindings from the built arm64 .so."
    group = "jellybeam"
    dependsOn(cargoNdkBuild)

    workingDir = coreDir.asFile
    val outDir = uniffiOutputDir.get().asFile
    // The output dir is the sole source of the bindings package; a stale copy under an old
    // library name would be compiled and linted alongside the fresh one.
    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }
    commandLine(
        "cargo", "run",
        "-p", "jellybeam-ffi",
        "--features", "cli",
        "--bin", "uniffi-bindgen",
        "--",
        "generate",
        "--library", jniLibsDir.dir("arm64-v8a").asFile.resolve("libjellybeam_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )

    inputs.dir(jniLibsDir.dir("arm64-v8a"))
    outputs.dir(uniffiOutputDir)
}

tasks.named("preBuild") {
    dependsOn(uniffiBindgen)
}

// Belt-and-suspenders: make sure Kotlin compilation never races the
// generator, regardless of how AGP orders things around preBuild.
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(uniffiBindgen)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// docs/27 §3: string tests read res/ files directly, so a translation edit must rerun them.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
}
