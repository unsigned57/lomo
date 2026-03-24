import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidxBaselineProfile) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.versionCatalogUpdate)
}

val kotlinVersion = libs.versions.kotlin.get()
val detektVersion = libs.versions.detekt.get()
val koverQualityVariant = "quality"
val defaultCoverageGateStage = "m1"
val coverageGateStages =
    linkedMapOf(
        "baseline" to 21,
        "m1" to 35,
        "m2" to 50,
        "m3" to 65,
        "m4" to 80,
    )
val coverageGateStage =
    (
        (findProperty("coverageGateStage") as String?)
            ?: System.getenv("COVERAGE_GATE_STAGE")
            ?: defaultCoverageGateStage
    ).trim().ifEmpty { defaultCoverageGateStage }
val configuredCoverageMinBound = (findProperty("coverageMinBound") as String?)?.toIntOrNull()
val coverageMinBound =
    configuredCoverageMinBound
        ?: coverageGateStages[coverageGateStage]
        ?: throw GradleException(
            "Unknown coverage gate stage '$coverageGateStage'. Valid stages: ${coverageGateStages.keys.joinToString(", ")}.",
        )
val detektProjects = setOf("app", "domain", "data", "ui-components")
val lintProjects = setOf("app", "data", "ui-components")
val koverProjects = setOf("app", "domain", "data", "ui-components")
val formattingConfig = rootProject.file("quality/detekt/config/formatting.yml")
val meaningfulTestCheckScript = rootProject.file("quality/scripts/check_meaningful_tests.sh")
val detektConfigByProject =
    mapOf(
        "app" to "quality/detekt/config/app.yml",
        "domain" to "quality/detekt/config/domain.yml",
        "data" to "quality/detekt/config/data.yml",
        "ui-components" to "quality/detekt/config/ui-components.yml",
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
    koverProjects.forEach { projectName ->
        add("kover", project(":$projectName"))
    }
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

            val moduleConfig = rootProject.file(detektConfigByProject.getValue(name))

            extensions.configure(dev.detekt.gradle.extensions.DetektExtension::class.java) {
                toolVersion = detektVersion
                config.setFrom(moduleConfig)
                buildUponDefaultConfig = true
                allRules = false
                autoCorrect = false
                ignoreFailures = false
                basePath.set(rootProject.layout.projectDirectory)
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
                buildUponDefaultConfig.set(true)
                ignoreFailures.set(false)
                config.setFrom(moduleConfig)
                basePath.set(rootProject.projectDir.absolutePath)
                reports {
                    checkstyle.required.set(false)
                    html.required.set(true)
                    markdown.required.set(false)
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

tasks.register("androidLintCheck") {
    group = "verification"
    description = "Runs Android Lint for the app and Android library modules."
    dependsOn(lintProjects.map { projectName -> ":$projectName:lintDebug" })
}

tasks.register("meaningfulTestCheck", Exec::class.java) {
    group = "verification"
    description = "Checks that changed test files document their tested contract and exclusions."
    workingDir = rootProject.projectDir
    commandLine("bash", meaningfulTestCheckScript.absolutePath)
}

tasks.register("coverageGatePlan") {
    group = "help"
    description = "Prints the staged merged coverage thresholds used by the quality gate."
    val stageLines =
        coverageGateStages.entries.joinToString(separator = System.lineSeparator()) { (stage, bound) ->
            val suffix = if (stage == coverageGateStage) " (current)" else ""
            " - $stage -> $bound%$suffix"
        }
    val overrideLine =
        configuredCoverageMinBound?.let {
            " - override -> $coverageMinBound% (from -PcoverageMinBound)"
        }
    doLast {
        println("Coverage gate stages:")
        println(stageLines)
        overrideLine?.let(::println)
    }
}

kover {
    currentProject {
        createVariant(koverQualityVariant) {}
    }
    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.Manifest*",
                    "*.R",
                    "*.R$*",
                    "*.ComposableSingletons*",
                    "dagger.hilt.internal.aggregatedroot.codegen.*",
                    "hilt_aggregated_deps.*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_Provide*Factory",
                    "*_MembersInjector",
                    "*_GeneratedInjector",
                    "*_HiltModules*",
                    "*Hilt_*",
                    "*_Impl",
                    "*_Impl$*",
                    "*Database_Impl*",
                    "com.lomo.data.di.*",
                    "com.lomo.ui.component.*",
                    "com.lomo.ui.theme.*",
                    "com.lomo.ui.text.*",
                    "com.lomo.ui.util.*",
                    "com.lomo.app.MainActivity",
                    "com.lomo.app.MainActivityKt",
                    "com.lomo.app.LomoApplication",
                    "com.lomo.app.LomoAppRootKt",
                    "com.lomo.app.navigation.*",
                    "com.lomo.app.repository.AppWidgetRepository",
                    "com.lomo.app.widget.*",
                    "com.lomo.app.feature.common.AppConfigUiCoordinator",
                    "com.lomo.app.feature.common.MemoUiCoordinator",
                    "com.lomo.app.util.ShareCardBitmapRenderer*",
                    "com.lomo.app.util.ShareUtils",
                    "com.lomo.app.feature.share.LanShareUiCoordinator",
                    "com.lomo.app.feature.main.MemoUiImageContentResolver*",
                    "com.lomo.app.feature.settings.SettingsCoordinatorFactory",
                    "com.lomo.app.feature.*.*ScreenKt",
                    "com.lomo.app.feature.*.*SectionsKt",
                    "com.lomo.app.feature.*.*DialogsKt",
                    "com.lomo.app.feature.*.*LayoutKt",
                    "com.lomo.app.feature.*.*SheetKt",
                    "com.lomo.app.feature.*.*PanelKt",
                    "com.lomo.app.feature.*.*ScaffoldKt",
                    "com.lomo.app.feature.*.*ContentKt",
                    "com.lomo.app.feature.*.*TopBarKt",
                    "com.lomo.app.feature.*.*FabKt",
                    "com.lomo.app.feature.*.*StateHostsKt",
                    "com.lomo.app.feature.*.*NavigationActionsKt",
                    "com.lomo.app.feature.*.*EventEffectsKt",
                    "com.lomo.app.feature.*.*EmptyStateKt",
                    "com.lomo.app.feature.*.*DirectoryGuideKt",
                    "com.lomo.app.feature.*.*SupportKt",
                    "com.lomo.app.feature.*.*SyncContainersKt",
                    "com.lomo.app.feature.*.*DialogOptionsKt",
                    "com.lomo.app.feature.*.*InteractionHostKt",
                    "com.lomo.app.feature.*.*BinderKt",
                    "com.lomo.app.feature.*.*EntryKt",
                    "com.lomo.app.feature.*.*CardListAnimationKt",
                    "com.lomo.app.feature.*.*StateProvider",
                    "com.lomo.app.feature.*.*Presenter",
                    "com.lomo.app.feature.*.*DialogState",
                    "com.lomo.app.feature.*.*Coordinator",
                    "com.lomo.data.repository.GitSyncRepositorySupport",
                    "com.lomo.data.repository.WebDavSyncRepositorySupport",
                    "com.lomo.data.repository.WebDavSyncFileBridge",
                    "com.lomo.data.repository.MemoSavePlanFactory",
                    "com.lomo.data.repository.MemoOutboxDrainCoordinator",
                    "com.lomo.data.repository.MemoStorageFormatProvider",
                    "com.lomo.data.repository.MemoRefreshPlanner",
                    "com.lomo.data.git.GitSyncQueryTestCoordinator",
                )
            }
        }
        variant(koverQualityVariant) {
            log {
                header = "Merged quality coverage [$coverageGateStage >= $coverageMinBound%]"
                format = "<entity>: <value>%"
            }
            verify {
                rule {
                    minBound(coverageMinBound)
                }
            }
        }
    }
}

tasks.register("coverageCheck") {
    group = "verification"
    description = "Runs merged JVM unit-test coverage checks for stage '$coverageGateStage' ($coverageMinBound%)."
    dependsOn("koverVerifyQuality")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs the repository quality gate."
    dependsOn("architectureCheck", "androidLintCheck", "meaningfulTestCheck", "coverageCheck")
}
