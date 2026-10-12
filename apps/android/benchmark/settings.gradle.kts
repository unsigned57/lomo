// Behavior Contract
//
// Capability: standalone Gradle build for the AndroidX Macrobenchmark instrumented-test
// module. The pinned Kotlin toolchain (Amper 0.12.0-dev-4119) builds the app APK through its
// own embedded Gradle bridge but exposes no instrumented-test phase, so this module owns the
// androidTest APK that `just android benchmark` installs against the Amper-built app.
//
// Scenarios:
// - Given `just android benchmark`, when the harness resolves, then only this module configures
//   and all intermediates land under target/lomo/android-benchmark — never beside the
//   canonical Kotlin toolchain build directory.
// - Given no connected device, when `connectedDebugAndroidTest` is requested, then AGP fails
//   before any measurement claim can be produced.
//
// Observable outcomes: com.lomo.benchmark androidTest APK built from Java sources only;
// `*-benchmarkData.json` results under the redirected build output.
// Excludes: production app/module compilation, version pins for shipping code, and any
// baseline-profile file generation (static profile generation stays with
// quality/scripts/generate_static_baseline_profile.py).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "lomo-benchmark"
