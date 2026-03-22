// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidxBaselineProfile) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.versionCatalogUpdate)
}

val kotlinVersion = libs.versions.kotlin.get()
val detektVersion = libs.versions.detekt.get()
val detektProjects = setOf("app", "domain", "data", "ui-components")
val formattingConfig = rootProject.file("config/detekt/formatting.yml")
val detektConfigByProject =
    mapOf(
        "app" to "config/detekt/app.yml",
        "domain" to "config/detekt/domain.yml",
        "data" to "config/detekt/data.yml",
        "ui-components" to "config/detekt/ui-components.yml",
    )
val stagedDetektFiles =
    (findProperty("internalDetektGitFilter") as String?)
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map { relativePath -> java.io.File(rootProject.projectDir, relativePath) }
        ?.filter { it.exists() }
        ?.toList()
        .orEmpty()

dependencies {
    add("detektPlugins", "dev.detekt:detekt-rules-ktlint-wrapper:$detektVersion")
}

extensions.configure(dev.detekt.gradle.extensions.DetektExtension::class.java) {
    toolVersion = detektVersion
    config.setFrom(formattingConfig)
    buildUponDefaultConfig = false
    allRules = false
    disableDefaultRuleSets = true
    autoCorrect = true
    basePath.set(rootProject.layout.projectDirectory)
}

tasks.register("detektFormat", dev.detekt.gradle.Detekt::class.java) {
    group = "formatting"
    description = "Formats Kotlin source files and Gradle Kotlin DSL scripts using detekt ktlint wrappers."
    setSource(files(rootProject.projectDir))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**")
    config.setFrom(formattingConfig)
    buildUponDefaultConfig.set(false)
    allRules.set(false)
    disableDefaultRuleSets.set(true)
    autoCorrect.set(true)
    basePath.set(rootProject.projectDir.absolutePath)
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(false)
    }
}

tasks.register("detektFormatStaged", dev.detekt.gradle.Detekt::class.java) {
    group = "formatting"
    description = "Formats staged Kotlin source files and Gradle Kotlin DSL scripts using detekt ktlint wrappers."
    notCompatibleWithConfigurationCache("Uses a dynamic staged file list supplied through a Gradle property.")
    setSource(files(stagedDetektFiles))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**")
    config.setFrom(formattingConfig)
    buildUponDefaultConfig.set(false)
    allRules.set(false)
    disableDefaultRuleSets.set(true)
    autoCorrect.set(true)
    basePath.set(rootProject.projectDir.absolutePath)
    onlyIf { stagedDetektFiles.isNotEmpty() }
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(false)
    }
}

subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
        }
    }

    if (name in detektProjects) {
        afterEvaluate {
            apply(plugin = "dev.detekt")
            dependencies.add("detektPlugins", project(":detekt-rules"))

            extensions.configure(dev.detekt.gradle.extensions.DetektExtension::class.java) {
                toolVersion = detektVersion
                config.setFrom(rootProject.file(detektConfigByProject.getValue(name)))
                buildUponDefaultConfig = false
                allRules = false
                autoCorrect = false
            }

            tasks.withType(dev.detekt.gradle.Detekt::class.java).configureEach {
                dependsOn(":detekt-rules:assemble")
                setSource(
                    files(
                        "src/main/java",
                        "src/main/kotlin",
                        if (name == "app") "build.gradle.kts" else null,
                    ),
                )
                include("**/*.kt", "**/*.kts")
                exclude("**/build/**")
                reports {
                    checkstyle.required.set(false)
                    html.required.set(true)
                    sarif.required.set(false)
                }
            }
        }
    }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Runs architecture guardrail checks for layer boundaries, forbidden imports, and naming rules."
    dependsOn(
        ":app:detekt",
        ":domain:detekt",
        ":data:detekt",
        ":ui-components:detekt",
    )
}
