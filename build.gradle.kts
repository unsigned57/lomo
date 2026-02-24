// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidxBaselineProfile) apply false
    alias(libs.plugins.versionCatalogUpdate)
    alias(libs.plugins.benManesVersions)
    alias(libs.plugins.ktlint) apply false
}

val kotlinVersion = libs.versions.kotlin.get()

subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Configure the dependency update check to only offer stable versions
fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

// tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
//     rejectVersionIf {
//         isNonStable(candidate.version) && !isNonStable(currentVersion)
//     }
// }
