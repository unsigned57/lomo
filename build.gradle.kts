import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    alias(libs.plugins.dependencyAnalysis)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidxBaselineProfile) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.owaspDependencyCheck)
    alias(libs.plugins.versionCatalogUpdate)
}

versionCatalogUpdate {
    keep {
        versions.add("androidNdk")
        versions.add("byteBuddy")
    }
}

val kotlinVersion = libs.versions.kotlin.get()
val detektVersion = libs.versions.detekt.get()
val byteBuddyVersion = libs.versions.byteBuddy.get()
val koverQualityVariant = "quality"
val coverageMinBound = 70
val dependencyAnalysisProjects = setOf("app", "domain", "data", "ui-components")
val detektProjects = setOf("app", "domain", "data", "ui-components")
val lintTasksByProject =
    linkedMapOf(
        "app" to "lintRelease",
        "data" to "lintDebug",
        "ui-components" to "lintDebug",
    )
val composeCompilerReportTasksByProject =
    linkedMapOf(
        "app" to listOf("compileDebugKotlin"),
        "ui-components" to listOf("compileDebugKotlin"),
    )
val compileGateTasksByProject =
    linkedMapOf(
        "app" to listOf("compileDebugKotlin", "compileDebugJavaWithJavac"),
        "data" to listOf("compileDebugKotlin", "compileDebugJavaWithJavac"),
        "ui-components" to listOf("compileDebugKotlin", "compileDebugJavaWithJavac"),
        "domain" to listOf("compileKotlin", "compileJava"),
    )
val unitTestTasksByProject =
    linkedMapOf(
        "app" to "testDebugUnitTest",
        "data" to "testDebugUnitTest",
        "domain" to "test",
        "ui-components" to "testDebugUnitTest",
    )
val koverProjects = setOf("app", "domain", "data", "ui-components")
val coverageExcludedPackages =
    listOf(
        "com.lomo.ui",
        "com.lomo.app.util",
        "com.lomo.app.presentation",
        "com.lomo.app.feature.settings",
        "com.lomo.data.source",
        "com.lomo.data.media",
        "com.lomo.data.security",
    )
val coverageExcludedClasses =
    listOf(
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
        "*Dao_Impl*",
        "*Database_Impl*",
        "com.lomo.ui.util.AppHapticFeedback*",
        "com.lomo.app.MainActivity*",
        "com.lomo.app.LomoApplication*",
        "com.lomo.app.LomoAppRootKt*",
        "com.lomo.app.navigation*",
        "com.lomo.app.repository.AppWidgetRepository*",
        "com.lomo.app.widget*",
        "com.lomo.app.di*",
        "com.lomo.app.theme*",
        "com.lomo.app.benchmark*",
        "com.lomo.app.media.AudioPlayerManager*",
        "com.lomo.app.util.ShareCardBitmapRenderer",
        "com.lomo.app.util.ShareUtils*",
        "com.lomo.app.util.HapticManager*",
        "com.lomo.app.util.CameraCaptureUtils*",
        "com.lomo.app.feature.main.MemoUiImageContentResolver*",
        "com.lomo.app.feature.settings.SettingsCoordinatorFactory*",
        "com.lomo.app.provider.ImageMapProvider*",
        "com.lomo.app.feature.*.*Presenter*",
        "com.lomo.app.feature.*.*ScreenKt*",
        "com.lomo.app.feature.*.*SectionKt*",
        "com.lomo.app.feature.*.*SectionsKt*",
        "com.lomo.app.feature.*.*BannerKt*",
        "com.lomo.app.feature.*.*DialogsKt*",
        "com.lomo.app.feature.*.*DialogHostKt*",
        "com.lomo.app.feature.*.*LayoutKt*",
        "com.lomo.app.feature.*.*SheetKt*",
        "com.lomo.app.feature.*.*PanelKt*",
        "com.lomo.app.feature.*.*ScaffoldKt*",
        "com.lomo.app.feature.*.*ContentKt*",
        "com.lomo.app.feature.*.*TopBarKt*",
        "com.lomo.app.feature.*.*FabKt*",
        "com.lomo.app.feature.*.*StateHostsKt*",
        "com.lomo.app.feature.*.*NavigationActionsKt*",
        "com.lomo.app.feature.*.*EventEffectsKt*",
        "com.lomo.app.feature.*.*EmptyStateKt*",
        "com.lomo.app.feature.*.*DirectoryGuideKt*",
        "com.lomo.app.feature.*.*SupportKt*",
        "com.lomo.app.feature.*.*SyncContainersKt*",
        "com.lomo.app.feature.*.*DialogOptionsKt*",
        "com.lomo.app.feature.*.*InteractionHostKt*",
        "com.lomo.app.feature.*.*BinderKt*",
        "com.lomo.app.feature.*.*EntryKt*",
        "com.lomo.app.feature.*.*CardListAnimationKt*",
        "com.lomo.app.feature.*.*ControllerKt*",
        "com.lomo.app.feature.*.*UiState*",
        "com.lomo.app.feature.*.*UiSnapshot*",
        "com.lomo.app.feature.*.*LocalState*",
        "com.lomo.app.feature.*.*HostState*",
        "com.lomo.app.feature.*.*Features*",
        "com.lomo.app.feature.*.*Actions*",
        "com.lomo.app.feature.*.*DialogState*",
        "com.lomo.data.di*",
        "com.lomo.data.local.datastore.LomoDataStoreKeys*",
        "com.lomo.data.local.datastore.LomoDataStoreKt*",
        "com.lomo.data.git.SafGitMirrorBridge*",
        "com.lomo.data.webdav.Dav4jvmWebDavClient*",
        "com.lomo.data.git.GitSyncQueryTestCoordinator*",
        "com.lomo.data.source.FileMediaStorageDataSourceDelegate*",
        "com.lomo.data.media.AudioRecorder*",
        "com.lomo.data.media.AudioPlaybackUriResolverImpl*",
        "com.lomo.data.share.NsdDiscoveryService*",
        "com.lomo.data.share.ShareServiceLifecycleController*",
        "com.lomo.data.share.ShareServiceManager*",
        "com.lomo.data.git.SafGitMirrorBridge*",
        "com.lomo.ui.util.DateTimeUtils*",
        "com.lomo.ui.util.SharedTransitionLocalsKt*",
        "com.lomo.ui.media.AudioPlayerManagerKt*",
    )
val coverageExcludedClassPatterns =
    (coverageExcludedClasses + coverageExcludedClasses.map { pattern -> pattern.replace('.', '/') }).distinct()
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

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "SARIF")
    suppressionFile = rootProject.file("quality/owasp/dependency-check-suppressions.xml").absolutePath
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
    tasks.withType(KotlinCompilationTask::class.java).configureEach {
        val isKotlinCompileTask =
            name.startsWith("compile") &&
                name.endsWith("Kotlin")
        if (isKotlinCompileTask) {
            compilerOptions.allWarningsAsErrors.set(true)
        }
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        if ("-Werror" !in options.compilerArgs) {
            options.compilerArgs.add("-Werror")
        }
    }

    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
            force("net.bytebuddy:byte-buddy:$byteBuddyVersion")
            force("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
        }
    }

    if (name in dependencyAnalysisProjects) {
        apply(plugin = "com.autonomousapps.dependency-analysis")
    }

    if (name in detektProjects) {
        afterEvaluate {
            apply(plugin = "dev.detekt")
            dependencies.add("detektPlugins", project(":detekt-rules"))
            dependencies.add("detektPlugins", "dev.detekt:detekt-rules-coroutines:$detektVersion")

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
    description = "Runs Android Lint for release app wiring and debug Android library modules."
    dependsOn(lintTasksByProject.map { (projectName, taskName) -> ":$projectName:$taskName" })
}

tasks.register("composeCompilerAnalysisCheck") {
    group = "verification"
    description = "Generates Compose compiler metrics and reports for AI-readable static hotspot analysis."
    dependsOn(
        composeCompilerReportTasksByProject.flatMap { (projectName, taskNames) ->
            taskNames.map { taskName -> ":$projectName:$taskName" }
        },
    )
}

tasks.register("composeStaticAnalysisCheck") {
    group = "verification"
    description = "Runs Compose-focused static analysis via Android Lint plus compiler metrics and reports."
    dependsOn("composeCompilerAnalysisCheck", "androidLintCheck")
}

tasks.register("compileGateCheck") {
    group = "verification"
    description = "Runs source compile gates first so Kotlin/Java warning-as-error failures surface before slower checks."
    dependsOn(
        compileGateTasksByProject.flatMap { (projectName, taskNames) ->
            taskNames.map { taskName -> ":$projectName:$taskName" }
        },
    )
}

tasks.register("unitTestCheck") {
    group = "verification"
    description = "Runs JVM unit tests across all modules after compile gates have passed."
    dependsOn(unitTestTasksByProject.map { (projectName, taskName) -> ":$projectName:$taskName" })
    mustRunAfter("compileGateCheck")
}

tasks.register("meaningfulTestCheck", Exec::class.java) {
    group = "verification"
    description = "Checks that changed test files document their tested contract, red phase, and exclusions."
    workingDir = rootProject.projectDir
    commandLine("bash", meaningfulTestCheckScript.absolutePath)
}

tasks.register("dependencyAnalysisCheck") {
    group = "verification"
    description = "Runs dependency-analysis reports for undeclared, unused, and mis-scoped dependencies. Experimental under AGP 9.x."
    dependsOn("buildHealth")
}

tasks.register("dependencyVulnerabilityCheck") {
    group = "verification"
    description = "Runs OWASP dependency scanning and fails on known vulnerabilities at CVSS 7.0 or higher."
    dependsOn("dependencyCheckAnalyze")
}

kover {
    currentProject {
        createVariant(koverQualityVariant) {}
    }
    reports {
        variant(koverQualityVariant) {
            filters {
                excludes {
                    androidGeneratedClasses()
                    packages(coverageExcludedPackages)
                    classes(coverageExcludedClassPatterns)
                }
            }
            log {
                header = "Merged quality coverage [min $coverageMinBound%]"
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
    description = "Runs merged JVM unit-test coverage checks with a $coverageMinBound% minimum."
    dependsOn("koverVerifyQuality")
}

tasks.register("staticQualityCheck") {
    group = "verification"
    description = "Runs compile gates, architecture checks, Android Lint, and meaningful-test metadata without coverage."
    dependsOn("compileGateCheck", "architectureCheck", "androidLintCheck", "meaningfulTestCheck")
    mustRunAfter("unitTestCheck")
}

tasks.register("fastQualityCheck") {
    group = "verification"
    description = "Runs the iterative quality gate: compile gates, meaningful-test metadata, and JVM unit tests."
    dependsOn("compileGateCheck", "meaningfulTestCheck", "unitTestCheck")
}

tasks.register("fullQualityCheck") {
    group = "verification"
    description = "Runs the full staged quality gate: unit tests first, then static checks, then merged coverage."
    dependsOn("staticQualityCheck", "unitTestCheck", "coverageCheck")
}

tasks.named("architectureCheck") {
    mustRunAfter("unitTestCheck")
}

tasks.named("androidLintCheck") {
    mustRunAfter("unitTestCheck")
}

tasks.named("coverageCheck") {
    mustRunAfter("staticQualityCheck")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs the final repository quality gate via the staged full-quality pipeline."
    dependsOn("fullQualityCheck")
}
