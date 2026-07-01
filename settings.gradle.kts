pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Lomo"
include(":app", ":domain", ":data", ":ui-components", ":detekt-rules")
project(":detekt-rules").projectDir = file("quality/detekt-rules")
