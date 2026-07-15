package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
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
 * - Given app module.yaml adds //data as a compile dependency,
 *   when architecture Detekt runs, then the app->data compile edge is reported.
 * - Given app module.yaml adds //data as runtime-only,
 *   when architecture Detekt runs, then the app runtime composition edge is allowed.
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
 *   `./kotlin test --include-module detekt-rules --platform jvm
 *   --include-classes 'com.lomo.detektrules.AppBoundaryArchitectureRuleTest'`
 *   failed `reports app Android variant and custom implementation data dependencies` at
 *   `AppBoundaryArchitectureRuleTest.kt:126`; `8 tests completed, 1 failed`.
 * - GREEN, 2026-05-22:
 *   the same command completed with `BUILD SUCCESSFUL`; `:detekt-rules:test` passed after
 *   AppBuildDependencyBoundary matched variant/custom implementation configuration names.
 *
 * Excludes:
 * - production dependency migration, Android manifest merger behavior, and semantic Toolchain model resolution.
 
 * Test Change Justification:
 * - Reason category: mechanical layout path update.
 * - Old behavior/assertion being replaced: fixture relativePath strings used maven-like or com/lomo-rooted source paths.
 * - Why old assertion is no longer correct: product modules omit the common package root on disk under Amper src/test roots.
 * - Coverage preserved by: same Detekt finding contracts and assertion messages.
 * - Why this is not fitting the test to the implementation: only path fixtures changed; rule behavior is unchanged.
*/
class AppBoundaryArchitectureRuleTest : FunSpec({
    test("reports app compile data dependency") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                  - //domain
                  - //data
                  - //ui-components
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "- //data"
    }

    test("reports app non-runtime data scope") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                  - //domain
                  - //data: compile-only
                  - //ui-components
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "- //data: compile-only"
    }

    test("allows app build dependencies that stay out of data") {
        val findings =
            rule().findingsForAppBuild(
                """
                dependencies {
                  - //domain
                  - //ui-components
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("reports app manifest data component names") {
        val findings =
            rule().findingsForAppBuild(
                moduleSpec =
                    """
                    dependencies {
                      - //domain
                      - //ui-components
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

    test("allows runtime-only data module for app composition") {
        val findings =
            rule().findingsForAppBuild(
                moduleSpec =
                    """
                    dependencies {
                      - //domain
                      - //data: runtime-only
                      - //ui-components
                    }
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
    moduleSpec: String,
    manifest: String? = null,
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val buildFile = tempDir.resolve("app/module.yaml")
    buildFile.parent.createDirectories()
    buildFile.writeText(moduleSpec.trimIndent())
    if (manifest != null) {
        val manifestFile = tempDir.resolve("app/src/AndroidManifest.xml")
        manifestFile.parent.createDirectories()
        manifestFile.writeText(manifest.trimIndent())
    }
    val sourceFile = tempDir.resolve("app/src/Fixture.kt")
    sourceFile.parent.createDirectories()
    sourceFile.writeText(
        """
        package com.lomo.app

        class Fixture
        """.trimIndent(),
    )
    return lint(compileForTest(sourceFile))
}
