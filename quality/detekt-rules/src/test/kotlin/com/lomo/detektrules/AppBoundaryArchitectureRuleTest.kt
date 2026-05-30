package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: AppBuildDependencyBoundary Detekt rule.
 * - Owning layer: quality.
 * - Priority tier: P1.
 * - Capability: detect app-layer build and manifest edges that point directly at the data layer.
 *
 * Scenarios:
 * - Given app build.gradle.kts adds project(":data") as an implementation dependency,
 *   when architecture Detekt runs, then the app->data project edge is reported.
 * - Given app build.gradle.kts uses the generated projects.data accessor,
 *   when architecture Detekt runs, then the app->data project edge is reported.
 * - Given app build.gradle.kts adds project(":data") through add("implementation", ...),
 *   when architecture Detekt runs, then the app->data project edge is reported.
 * - Given app build.gradle.kts adds projects.data through add("implementation", ...),
 *   when architecture Detekt runs, then the app->data project edge is reported.
 * - Given app build.gradle.kts declares Android variant or custom implementation configurations
 *   that point at project(":data") or projects.data,
 *   when architecture Detekt runs, then every app->data project edge is reported.
 * - Given app AndroidManifest.xml names a com.lomo.data component,
 *   when architecture Detekt runs from the app build file, then the manifest boundary violation is reported.
 * - Given a currently known migration exception is listed in config,
 *   when architecture Detekt runs, then only that exact known edge is allowed.
 *
 * Observable outcomes:
 * - Detekt finding counts and messages name the violating build dependency or manifest component.
 *
 * TDD proof:
 * - RED, 2026-05-22:
 *   `./gradlew --no-daemon --no-configuration-cache --console=plain :detekt-rules:test
 *   --tests 'com.lomo.detektrules.AppBoundaryArchitectureRuleTest'`
 *   failed `reports app Android variant and custom implementation data dependencies` at
 *   `AppBoundaryArchitectureRuleTest.kt:126`; `8 tests completed, 1 failed`.
 * - GREEN, 2026-05-22:
 *   the same command completed with `BUILD SUCCESSFUL`; `:detekt-rules:test` passed after
 *   AppBuildDependencyBoundary matched variant/custom implementation configuration names.
 *
 * Excludes:
 * - production dependency migration, Android manifest merger behavior, and semantic Gradle model resolution.
 */
class AppBoundaryArchitectureRuleTest : FunSpec({
    test("reports app implementation project data dependency") {
        val findings =
            rule().findingsForAppBuild(
                """
                plugins {
                    id("com.android.application")
                }

                dependencies {
                    implementation(project(":data"))
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain """implementation(project(":data"))"""
    }

    test("reports app version catalog data project accessor dependency") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                    implementation(projects.data)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "implementation(projects.data)"
    }

    test("reports app add implementation project data dependency") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                    add("implementation", project(":data"))
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain """add("implementation", project(":data"))"""
    }

    test("reports app add implementation data project accessor dependency") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                    add("implementation", projects.data)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain """add("implementation", projects.data)"""
    }

    test("reports app Android variant and custom implementation data dependencies") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                    debugImplementation(project(":data"))
                    releaseImplementation(project(":data"))
                    benchmarkImplementation(project(":data"))
                    add("debugImplementation", projects.data)
                }
                """,
            )

        findings.shouldHaveSize(4)
        findings.map(Finding::message).toString() shouldContain """debugImplementation(project(":data"))"""
        findings.map(Finding::message).toString() shouldContain """releaseImplementation(project(":data"))"""
        findings.map(Finding::message).toString() shouldContain """benchmarkImplementation(project(":data"))"""
        findings.map(Finding::message).toString() shouldContain """add("debugImplementation", projects.data)"""
    }

    test("allows app build dependencies that stay out of data") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                    implementation(project(":domain"))
                    implementation(project(":ui-components"))
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("reports app manifest data component names") {
        val findings =
            rule().findingsForAppBuild(
                buildGradle =
                    """
                    dependencies {
                        implementation(project(":domain"))
                    }
                    """,
                manifest =
                    """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application>
                            <receiver android:name="com.lomo.data.reminder.ReminderAlarmReceiver" />
                        </application>
                    </manifest>
                    """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "com.lomo.data.reminder.ReminderAlarmReceiver"
    }

    test("allows only configured current app data boundary migration exceptions") {
        val findings =
            rule(
                TestConfig(
                    "allowedDataProjectDependencies" to listOf("""implementation(project(":data"))"""),
                    "allowedManifestDataComponents" to listOf("com.lomo.data.reminder.ReminderAlarmReceiver"),
                ),
            ).findingsForAppBuild(
                buildGradle =
                    """
                    dependencies {
                        implementation(project(":data"))
                    }
                    """,
                manifest =
                    """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application>
                            <receiver android:name="com.lomo.data.reminder.ReminderAlarmReceiver" />
                        </application>
                    </manifest>
                    """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(config: Config = Config.empty): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName("AppBuildDependencyBoundary")]) {
        "Expected AppBuildDependencyBoundary to be registered."
    }.invoke(config)

private fun Rule.findingsForAppBuild(
    buildGradle: String,
    manifest: String? = null,
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val buildFile = tempDir.resolve("app/build.gradle.kts")
    buildFile.parent.createDirectories()
    buildFile.writeText(buildGradle.trimIndent())
    if (manifest != null) {
        val manifestFile = tempDir.resolve("app/src/main/AndroidManifest.xml")
        manifestFile.parent.createDirectories()
        manifestFile.writeText(manifest.trimIndent())
    }
    return lint(compileForTest(buildFile))
}
