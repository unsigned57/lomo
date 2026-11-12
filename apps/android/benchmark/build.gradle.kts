// Behavior Contract
//
// Capability: single-module AndroidX Macrobenchmark harness. The androidTest sourceset compiles
// Java-only measurement code (startup/frame/scroll scenarios) so no Kotlin Gradle plugin or
// version pairing with the pinned toolchain is required; the test APK self-instruments
// `com.lomo.benchmark` and drives the already-installed `com.lomo.app` APK by package
// name through MacrobenchmarkRule + UIAutomator resource-id anchors.
//
// Scenarios:
// - Given the debug variant, when `connectedDebugAndroidTest` runs, then
//   `LomoMacrobenchmark.{startup,frame,scroll}` execute against the installed target package
//   and emit androidx benchmark JSON results.
// - Given the layout contract, when the project configures, then every intermediate lands under
//   target/lomo/build/android-benchmark/gradle — no second repository build tree.
//
// Observable outcomes: assembleDebugAndroidTest produces com.lomo.benchmark test APK;
// connected run writes `*-benchmarkData.json` (plus `*-baseline-prof.txt` /
// `-startup-prof.txt` from the `profileGen` collection) under
// build/outputs/connected_android_test_additional_output (redirected).
// Excludes: Kotlin compilation, release/signing config, baseline-profile packaging
// (the collected files are evidence `perf device profile` consumes).

plugins {
    // Standalone Java harness uses the latest stable AGP independently of the Kotlin
    // toolchain embedded bridge; both resolve through the shared dependency cache.
    id("com.android.library") version "9.5.1"
}

android {
    namespace = "com.lomo.benchmark"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Default planes use Debug on CI emulators. The release plane overrides this with
        // EMULATOR alone and uses profileable semantic anchors. androidx.benchmark records
        // the suppression inside every emitted `*-benchmarkData.json`.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,DEBUGGABLE"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The self-instrumenting harness uses its debug variant for either target APK.
    buildTypes {
        named("debug") {
            isMinifyEnabled = false
        }
    }
}

// Lomo-owned intermediates live in the cargo-managed artifact namespace, not a second
// repository build tree (quality artifact-layout contract).
layout.buildDirectory.set(file("../../../target/lomo/build/android-benchmark/gradle"))

val uiAutomatorVersion = "2.4.1"

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:$uiAutomatorVersion")
    androidTestImplementation("androidx.test.uiautomator:uiautomator-shell:$uiAutomatorVersion")
}
