/*
 * Behavior Contract:
 * Capability: cloud-backup and device-transfer extraction must not copy credential prefs, the
 * install trust-root leftover, the trusted-launch command queue, or WorkManager's WorkDatabase;
 * owning layer: app; priority: P0.
 * Scenarios:
 * - Given data_extraction_rules.xml, when parsed, then both backup domains exclude
 *   git/webdav/s3 credentials, trusted_launch_intents, external_app_commands,
 *   androidx.work.workdb, and the file-domain DataStore directory.
 * Observable outcomes: exclude path presence in both <cloud-backup> and <device-transfer>.
 * TDD proof: RED because both domains are include-all sharedpref+database with no excludes.
 * Excludes: runtime Auto Backup device runs; deprecated adb backup.
 * // architectural-boundary-check: pins the shipped manifest XML against credential/workdb leaks.
 *
 * Test Change Justification:
 * - Reason category: exclusion surface extended to the settings DataStore directory.
 * - Old behavior/assertion being replaced: excludes covered credentials, launch intents,
 *   external commands and workdb only.
 * - Why old assertion is no longer correct: files/datastore holds the app-lock flag and backend
 *   selection; letting it leave the device via backup or transfer leaks security posture.
 * - Coverage preserved by: all previous exclude arms plus the new datastore file-domain arm.
 * - Why this is not fitting the test to the implementation: assertions check the shipped
 *   manifest contract, not how the exclusion is coded.
 */

package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class DataExtractionRulesTest : AppFunSpec() {
    init {
        test("backup domains exclude credentials trust root command queue and work database") {
            val xml = extractionRulesFile().readText()
            val requiredExcludes =
                listOf(
                    """<exclude domain="sharedpref" path="git_credentials.xml"/>""",
                    """<exclude domain="sharedpref" path="webdav_credentials.xml"/>""",
                    """<exclude domain="sharedpref" path="s3_credentials.xml"/>""",
                    """<exclude domain="sharedpref" path="trusted_launch_intents.xml"/>""",
                    """<exclude domain="sharedpref" path="external_app_commands.xml"/>""",
                    """<exclude domain="database" path="androidx.work.workdb"/>""",
                    // files/datastore holds the settings DataStore (app-lock + backend
                    // selection); it must not leave the device via backup or transfer.
                    """<exclude domain="file" path="datastore/"/>""",
                )
            listOf("cloud-backup", "device-transfer").forEach { domain ->
                val section = section(xml, domain)
                requiredExcludes.forEach { exclude ->
                    withClue("$domain must contain $exclude") {
                        section.contains(exclude) shouldBe true
                    }
                }
            }
        }
    }
}

private fun extractionRulesFile(): File {
    val currentDir = File(System.getProperty("user.dir") ?: ".")
    val candidates =
        listOf(
            currentDir.resolve("res/xml/data_extraction_rules.xml"),
            currentDir.resolve("app/res/xml/data_extraction_rules.xml"),
        )
    return checkNotNull(candidates.firstOrNull(File::isFile)) {
        "data_extraction_rules.xml not found from ${currentDir.absolutePath}"
    }
}

private fun section(
    xml: String,
    tag: String,
): String {
    val match = Regex("""<$tag>(.*?)</$tag>""", setOf(RegexOption.DOT_MATCHES_ALL)).find(xml)
    return checkNotNull(match?.groupValues?.get(1)) { "missing <$tag> in extraction rules" }
}
