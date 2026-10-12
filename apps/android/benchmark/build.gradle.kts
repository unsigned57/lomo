// Behavior Contract
//
// Capability: single-module AndroidX Macrobenchmark harness. The androidTest sourceset compiles
// Java-only measurement code (startup/frame/scroll scenarios) so no Kotlin Gradle plugin or
// version pairing with the pinned toolchain is required; the test APK self-instruments
// `com.lomo.benchmark` and drives the already-installed `com.lomo.app` debug APK by package
// name through MacrobenchmarkRule + UIAutomator resource-id anchors.
//
// Scenarios:
// - Given the debug variant, when `connectedDebugAndroidTest` runs, then
//   `LomoMacrobenchmark.{startup,frame,scroll}` execute against the installed target package
//   and emit androidx benchmark JSON results.
// - Given the layout contract, when the project configures, then every intermediate lands under
//   target/lomo/android-benchmark/gradle — no second repository build tree.
//
// Observable outcomes: assembleDebugAndroidTest produces com.lomo.benchmark test APK;
// connected run writes `*-benchmarkData.json` under
// build/outputs/connected_android_test_additional_output (redirected).
// Excludes: Kotlin compilation, release/signing config, baseline-profile file emission.

plugins {
    // Same AGP the pinned Kotlin toolchain resolves for its embedded Android bridge,
    // so the benchmark harness shares one already-cached plugin artifact.
    id("com.android.library") version "9.2.1"
}

android {
    namespace = "com.lomo.benchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Declared accuracy suppression: the anchor-driven suite must run against the
        // debuggable build (MainActivity only enables testTagsAsResourceId when
        // debuggable), and CI devices are emulators. androidx.benchmark records the
        // suppression inside every emitted `*-benchmarkData.json`, so the data can never
        // be mistaken for physical-device release evidence.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,DEBUGGABLE"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Benchmarks only ever run the debug variant; the release block stays stock.
    buildTypes {
        named("debug") {
            isMinifyEnabled = false
        }
    }
}

// Lomo-owned intermediates live in the cargo-managed artifact namespace, not a second
// repository build tree (quality artifact-layout contract).
layout.buildDirectory.set(file("../../../target/lomo/android-benchmark/gradle"))

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
