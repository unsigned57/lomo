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
 * - Capability: app manifests cannot instantiate data-layer components.
 * Scenarios:
 * - Given a data component, when parsed under any Android namespace prefix, then it is rejected.
 * - Given comments or app-owned components, when parsed, then they create no forbidden edge.
 * - Given malformed XML or a document type, when checked, then invalid input is surfaced.
 * Observable outcomes: Detekt findings identify component names or invalid manifest input.
 * TDD proof: the previous regex failed both comment and namespace-prefix scenarios (7 run, 2 failed).
 * Excludes: manifest merging, product implementation, and dependency direction (owned by the
 * Rust architecture tests using parsed Amper YAML).
 * Test Change Justification:
 * - Reason category: replace a text heuristic with structural parsing and one dependency owner.
 * - Old assertion: fake module.yaml strings were scanned by an app-only regex in Detekt.
 * - Why no longer correct: those fixtures were not valid YAML and ignored alternate YAML syntax.
 * - Coverage preserved by: policy_contracts::kotlin_runtime_composition_cannot_become_a_compile_dependency
 *   and binding_capability_cannot_escape_through_an_exported_dependency cover real module input.
 * - Not implementation fitting: the original forbidden component assertion remains; two observed
 *   false-positive/false-negative cases and malformed-input cases add stronger behavior locks.
 */
class AppBoundaryArchitectureRuleTest : FunSpec({
    test("reports an app manifest data component") {
        manifestFindings("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application><receiver android:name="com.lomo.data.reminder.ReminderAlarmReceiver" /></application>
            </manifest>
        """).single().message shouldContain "com.lomo.data.reminder.ReminderAlarmReceiver"
    }

    test("app-owned components stay legal") {
        manifestFindings("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application><activity android:name="com.lomo.app.MainActivity" /></application>
            </manifest>
        """).shouldHaveSize(0)
    }

    test("manifest comments do not instantiate data components") {
        manifestFindings("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <!-- <receiver android:name="com.lomo.data.OldReceiver" /> -->
              <application />
            </manifest>
        """).shouldHaveSize(0)
    }

    test("changing the Android XML namespace prefix cannot hide a component") {
        manifestFindings("""
            <manifest xmlns:host="http://schemas.android.com/apk/res/android">
              <application><receiver host:name="com.lomo.data.HiddenReceiver" /></application>
            </manifest>
        """).single().message shouldContain "com.lomo.data.HiddenReceiver"
    }

    test("invalid XML fails closed") {
        manifestFindings("<manifest><application>").single().message shouldContain "Invalid app AndroidManifest.xml"
    }

    test("a manifest cannot obtain its policy facts from external entities") {
        manifestFindings("""
            <!DOCTYPE manifest SYSTEM "file:///not-a-policy-input">
            <manifest><application /></manifest>
        """).single().message shouldContain "Invalid app AndroidManifest.xml"
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
