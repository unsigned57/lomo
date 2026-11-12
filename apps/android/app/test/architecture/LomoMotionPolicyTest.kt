package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Capability: LomoMotion semantic scheme usage across app production sources.
 * - Behavior focus: migrated motion call sites must resolve animation specs through LomoMotion
 *   (component/layout), and no app production source may hand-roll bare tween/keyframes/spring
 *   specs. The memo list wrapper must not key content on the changing modifier lambda identity.
 *
 * Scenarios:
 * - Given a migrated call site, when its source is inspected, then it references LomoMotion and
 *   does not read MaterialTheme.motionScheme for spatial specs.
 * - Given the memo card list animation wrapper, when its source is inspected, then MemoListContent
 *   is not wrapped in a key() group keyed on animation or modifier identity.
 * - Given all app production sources, when they are scanned for bare tween, keyframes or spring
 *   spec declarations, then zero offenders are found.
 *
 * Observable outcomes: every migrated file references LomoMotion and no longer reads
 *   MaterialTheme.motionScheme; the forbidden-spec scan reports zero offenders;
 *   MemoCardListAnimation.kt contains no key() group.
 *
 * TDD proof: Fails before the LomoMotion migration because MemoCardListAnimation.kt wraps
 *   MemoListContent in key(animation, modifier), resetting list state on modifier identity
 *   change, and no app call site references LomoMotion.
 *
 * Production scope: apps/android/app/src/feature/memo/MemoCardListAnimation.kt
 * Production scope: apps/android/app/src/feature/memo/MemoCardEntry.kt
 * Production scope: apps/android/app/src/feature/share/ShareTransferBanner.kt
 * Production scope: apps/android/app/src/feature/gallery/GalleryReelMemoOverlay.kt
 * Production scope: apps/android/app/src/feature/tag/TagFilterScreen.kt
 * Production scope: apps/android/app/src/feature/share/ShareSections.kt
 * Production scope: apps/android/app/src/feature/share/ShareDeviceDiscoverySection.kt
 *
 * Excludes: test sources, generated code and third-party libraries.
 *
 * Test Change Justification:
 * Reason category: user-requested task page removal.
 * Old behavior/assertion being replaced: the removed TasksScreen must exist and use LomoMotion.
 * Why old assertion is no longer correct: the user requested deletion of the Android task page.
 * Coverage preserved by: all remaining motion call sites and the whole-source forbidden-spec scan.
 * Why this is not fitting the test to the implementation: the product scope explicitly excludes tasks.
 *
 * Test Change Justification:
 * Reason category: consolidate memo body height animation under ui-components.
 * Old behavior/assertion being replaced: MemoCardEntry must itself resolve a motion scheme.
 * Why old assertion is no longer correct: the entry delegates size animation to MemoCardBody;
 * keeping the wrapper animation creates nested height animations for the same content change.
 * Coverage preserved by: ui-components LomoMotionPolicyTest covers MemoCard's semantic scheme,
 * the global forbidden-spec scan remains, and device expand before/after checks preserve behavior.
 * Why this is not fitting the test to the implementation: animation ownership moved to the
 * existing reusable component; the removed caller no longer defines any animation specification.
 * TDD proof: Regression preservation - expand-before.json, expand-after.json and app suites in
 * target/lomo/reports/manual/startup-scroll-round2/.
 */
class LomoMotionPolicyTest : AppFunSpec() {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        test("migrated call sites resolve specs from LomoMotion and not MaterialTheme.motionScheme") {
            val migratedFiles =
                listOf(
                    "feature/share/ShareTransferBanner.kt",
                    "feature/gallery/GalleryReelMemoOverlay.kt",
                    "feature/share/ShareSections.kt",
                    "feature/share/ShareDeviceDiscoverySection.kt",
                )
            migratedFiles.forEach { relativePath ->
                val file = sourceRoot.resolve(relativePath)
                withClue("migrated motion call site $relativePath must exist") { file.exists() shouldBe true }
                val content = file.readText()
                withClue("$relativePath must resolve specs through LomoMotion") {
                    content.contains("LomoMotion") shouldBe true
                }
                withClue("$relativePath must not read MaterialTheme.motionScheme for spatial specs") {
                    content.contains("MaterialTheme.motionScheme") shouldBe false
                }
            }
        }

        test("memo list wrapper does not recompose content on modifier or animation identity") {
            val content = sourceRoot.resolve("feature/memo/MemoCardListAnimation.kt").readText()
            withClue("MemoCardList must not wrap MemoListContent in a key() group keyed on unstable identity") {
                content.contains("key(animation") shouldBe false
                content.contains("key(") shouldBe false
            }
        }

        test("no app production source declares bare tween, keyframes or spring specs") {
            val kotlinFiles =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under app/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }
            val offenders = kotlinFiles.filter { file -> file.containsForbiddenSpec() }
            withClue(
                "bare tween/keyframes/spring specs must come from LomoMotion schemes. " +
                    "Offenders: ${offenders.joinToString { it.path }}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }
    }

    private fun File.containsForbiddenSpec(): Boolean = readLines()
        .filterNot { line -> line.trimStart().startsWith("import") }
        .filterNot { line -> line.trimStart().startsWith("//") }
        .filterNot { line -> line.trimStart().startsWith("*") }
        .any { line -> FORBIDDEN_SPEC_PATTERN.containsMatchIn(line) }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
                currentDir.parentFile?.parentFile?.resolve(moduleName),
            )
        return candidateRoots.filterNotNull().firstOrNull { it.resolve("src").exists() }
            ?: currentDir.resolve(moduleName)
    }

    private companion object {
        private val FORBIDDEN_SPEC_PATTERN =
            Regex("""\b(?:tween|spring|SpringSpec)\s*[<(]|\bkeyframes\b""")
    }
}
