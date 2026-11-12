package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: AppManifestBoundary through the registered Detekt provider.
 * - Owning layer: quality; priority P0.
 * - Capability: app packaging may register data background services/receivers, while UI/application
 *   components remain outside the data boundary.
 * Scenarios:
 * - Given a data activity/application component, when parsed under any Android namespace prefix, then it is rejected.
 * - Given a data receiver/service, when registered by app packaging, then it is accepted.
 * - Given comments or app-owned components, when parsed, then they create no forbidden edge.
 * - Given malformed XML or a document type, when checked, then invalid input is surfaced.
 * Observable outcomes: Detekt findings identify component names or invalid manifest input.
 * TDD proof: the previous regex failed both comment and namespace-prefix scenarios (7 run, 2 failed).
 * Excludes: manifest merging, product implementation, and dependency direction (owned by the
 * Rust architecture tests using parsed Amper YAML).
 * Test Change Justification:
 * Reason category: packaging boundary correction required by observed APK omissions.
 * Old behavior/assertion being replaced: all data component registrations were forbidden.
 * Why old assertion is no longer correct: library AARs discard source manifests, so app packaging
 *   must register background services/receivers for the OS to reach data implementations.
 * Coverage preserved by: activity/application rejections, namespace-prefix controls, malformed
 *   XML rejection, and positive receiver/service fixtures; Kotlin compile scope stays runtime-only.
 * Why this is not fitting the test to the implementation: the installed pre-fix APK lacks actual
 *   permissions/components; target/lomo/reports/manual/reminder-alarm/package-before.txt records RED.

 */
class AppBoundaryArchitectureRuleTest :
    FunSpec({
        test("reports a data activity in the app manifest") {
            manifestFindings(
                """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application><activity android:name="com.lomo.data.DataActivity" /></application>
            </manifest>
        """,
            ).single().message shouldContain "com.lomo.data.DataActivity"
        }

        test("data background components can be registered by the packaging owner") {
            manifestFindings(
                """
            <manifest xmlns:host="http://schemas.android.com/apk/res/android">
              <application>
                <receiver host:name="com.lomo.data.reminder.ReminderAlarmReceiver" host:exported="false" />
                <service host:name="com.lomo.data.recording.RecordingForegroundService" host:exported="false" />
              </application>
            </manifest>
        """,
            ).shouldHaveSize(0)
        }

        test("app-owned components stay legal") {
            manifestFindings(
                """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application><activity android:name="com.lomo.app.MainActivity" /></application>
            </manifest>
        """,
            ).shouldHaveSize(0)
        }

        test("manifest comments do not instantiate data components") {
            manifestFindings(
                """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <!-- <receiver android:name="com.lomo.data.OldReceiver" /> -->
              <application />
            </manifest>
        """,
            ).shouldHaveSize(0)
        }

        test("changing the Android XML namespace prefix cannot hide a component") {
            manifestFindings(
                """
            <manifest xmlns:host="http://schemas.android.com/apk/res/android">
              <application host:name="com.lomo.data.HiddenApplication" />
            </manifest>
        """,
            ).single().message shouldContain "com.lomo.data.HiddenApplication"
        }

        test("invalid XML fails closed") {
            manifestFindings("<manifest><application>").single().message shouldContain "Invalid app AndroidManifest.xml"
        }

        test("a manifest cannot obtain its policy facts from external entities") {
            manifestFindings(
                """
            <!DOCTYPE manifest SYSTEM "file:///not-a-policy-input">
            <manifest><application /></manifest>
        """,
            ).single().message shouldContain "Invalid app AndroidManifest.xml"
        }
    })

private fun manifestFindings(manifest: String): List<Finding> {
    val rule = checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName("AppManifestBoundary")]) {
        "AppManifestBoundary must be registered"
    }.invoke(Config.empty)
    val root = Files.createTempDirectory("lomo-manifest-rule")
    try {
        val source = root.resolve("app/src/Fixture.kt")
        source.parent.createDirectories()
        source.writeText("package com.lomo.app\nclass Fixture")
        source.parent.resolve("AndroidManifest.xml").writeText(manifest.trimIndent())
        return rule.lint(compileForTest(source))
    } finally {
        check(root.toFile().deleteRecursively()) { "Cannot clean fixture $root" }
    }
}
