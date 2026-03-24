pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
include(":app", ":domain", ":data", ":ui-components", ":benchmark", ":detekt-rules")
project(":detekt-rules").projectDir = file("quality/detekt-rules")
