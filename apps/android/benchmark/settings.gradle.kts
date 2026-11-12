// Behavior Contract
//
// Capability: standalone Gradle build for the AndroidX Macrobenchmark instrumented-test
// module. The pinned Kotlin toolchain (Amper 0.14.0-dev-4626) builds the app APK through its
// own embedded Gradle bridge but exposes no instrumented-test phase, so this module owns the
// androidTest APK that `just build android benchmark` installs against the Amper-built app.
//
// Scenarios:
// - Given `just build android benchmark`, when the harness resolves, then only this module configures
//   and all intermediates land under target/lomo/build/android-benchmark — never beside the
//   canonical Kotlin toolchain build directory.
// - Given no connected device, when `connectedDebugAndroidTest` is requested, then AGP fails
//   before any measurement claim can be produced.
//
// Observable outcomes: com.lomo.benchmark androidTest APK built from Java sources only;
// `*-benchmarkData.json` results under the redirected build output.
// Excludes: production app/module compilation and version pins for shipping code.
// Baseline-profile packaging inputs still come from the repo side — this module's
// `profileGen` collects measured rules. The canonical perf device profile --write flow
// validates them with SDK profgen and publishes the packaging inputs plus collection receipt.

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
