pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.owasp.dependencycheck") {
                useModule("org.owasp:dependency-check-gradle:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Lomo"
include(":app", ":domain", ":data", ":ui-components", ":detekt-rules")
project(":detekt-rules").projectDir = file("quality/detekt-rules")
