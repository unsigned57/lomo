package com.lomo.ui.architecture

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * Capability: single canonical LomoModalBottomSheet wrapper and uncluttered ActionSheet layout in ui-components;
 * owner: ui architecture boundaries; priority: P1.
 *
 * Scenarios:
 * - Given ui-components production sources, when scanning for direct Material3 ModalBottomSheet( calls,
 *   then only component/common/LomoModalBottomSheet.kt invokes ModalBottomSheet(.
 * - Given ActionSheetUi.kt and MemoActionSheet.kt, when inspected for divider lines and muted list-icon
 *   tints, then no HorizontalDivider/ActionRowDivider remains and list icons use crisp onSurface tint.
 *
 * Observable outcomes: zero unauthorized ModalBottomSheet callers, single sheet owner, and zero dividers in action sheets.
 * TDD proof: RED while MemoMenuBottomSheet.kt and SelectionDialog.kt invoke bare ModalBottomSheet( directly
 * and ActionSheetUi.kt renders ActionRowDivider / HorizontalDivider with onSurfaceVariant icon tint.
 * Production scope: apps/android/ui-components/src/component/common/LomoModalBottomSheet.kt
 * Production scope: apps/android/ui-components/src/component/menu/MemoMenuBottomSheet.kt
 * Production scope: apps/android/ui-components/src/component/menu/MemoActionSheet.kt
 * Production scope: apps/android/ui-components/src/component/menu/ActionSheetUi.kt
 * Production scope: apps/android/ui-components/src/component/dialog/SelectionDialog.kt
 * Excludes: test sources and generated code.
 */
class LomoModalSheetPolicyTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        test("only LomoModalBottomSheet invokes Material3 ModalBottomSheet directly in ui-components") {
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under ui-components/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }

            val modalSheetWrapper = sourceRoot.resolve("component/common/LomoModalBottomSheet.kt").canonicalFile
            withClue("Canonical LomoModalBottomSheet wrapper must exist at ${modalSheetWrapper.path}") {
                modalSheetWrapper.exists() shouldBe true
            }

            val offenders =
                kotlinFiles
                    .filter { it.canonicalFile != modalSheetWrapper }
                    .filter { file -> file.callsBareModalBottomSheet() }

            withClue(
                "All ui-components modal sheets must delegate to LomoModalBottomSheet. " +
                    "Offenders: ${offenders.joinToString { it.relativeTo(sourceRoot).path }}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }

        test("memo action sheet renders zero dividers, omits legacy ActionListRow, and renders MemoInfoCard") {
            /*
             * Test Change Justification:
             * - Reason category: redesign restores v1.6.2-inspired MemoInfoCard (created time + character
             *   count) at the bottom of the memo menu and replaces flat ActionListRow items with
             *   aligned bottom capsules and a radial Share sub-action popup.
             * - Old behavior/assertion being replaced: asserted MemoMetaRow absence and inspected
             *   ActionListRow icon tinting.
             * - Why old assertion is no longer correct: flat ActionListRow rows are deleted in favor of
             *   4 compact tiles + aligned capsules, and MemoInfoCard intentionally restores metadata.
             * - Coverage preserved by: zero HorizontalDivider/ActionRowDivider enforcement remains, plus
             *   checks that legacy ActionListRow is removed and MemoInfoCard renders metadata.
             * - Why this is not fitting the test to the implementation: asserts architectural cleanup
             *   and restored metadata boundary directly.
             */
            val actionSheetUi = sourceRoot.resolve("component/menu/ActionSheetUi.kt").readText()
            val memoActionSheet = sourceRoot.resolve("component/menu/MemoActionSheet.kt").readText()

            withClue("ActionSheetUi and MemoActionSheet must not render HorizontalDivider or ActionRowDivider") {
                actionSheetUi.contains("HorizontalDivider") shouldBe false
                actionSheetUi.contains("ActionRowDivider") shouldBe false
                memoActionSheet.contains("ActionRowDivider") shouldBe false
            }

            withClue("Legacy borderless ActionListRow must be removed once Row placement is replaced by capsules") {
                actionSheetUi.contains("ActionListRow") shouldBe false
                memoActionSheet.contains("ActionRowList") shouldBe false
            }

            withClue("MemoActionSheet and ActionSheetUi must render MemoInfoCard when state is supplied") {
                actionSheetUi.contains("internal fun MemoInfoCard") shouldBe true
                memoActionSheet.contains("MemoInfoCard(") shouldBe true
            }
        }

        test("LomoModalBottomSheet disables Material3 dragHandle tooltip ripple and registers Haze source") {
            val modalSheet = sourceRoot.resolve("component/common/LomoModalBottomSheet.kt").readText()
            withClue("Material3 dragHandle must be passed as null to suppress horizontal bar tap ripple") {
                modalSheet.contains("dragHandle = null") shouldBe true
            }
            withClue(
                "LomoModalBottomSheet must render its drag handle inside sheet content column and register Haze source",
            ) {
                modalSheet.contains("dragHandle?.invoke()") shouldBe true
                modalSheet.contains("LocalLomoHazeState") shouldBe true
                modalSheet.contains(".hazeSource(") shouldBe true
            }
        }

        test("RadialSubActionOverlay uses Haze full-backdrop blur instead of partial MemoActionSheet column blur") {
            val memoActionSheet = sourceRoot.resolve("component/menu/MemoActionSheet.kt").readText()
            val radialOverlay = sourceRoot.resolve("component/menu/RadialSubActionOverlay.kt").readText()

            withClue("MemoActionSheet must not apply partial Modifier.blur only to its action column") {
                memoActionSheet.contains(".blur(") shouldBe false
            }
            withClue("RadialSubActionOverlay scrim must blur full shared Haze backdrop") {
                radialOverlay.contains("LocalLomoHazeState") shouldBe true
                radialOverlay.contains(".hazeBlur(") shouldBe true
            }
        }

        test("VoiceRecordingPanel renders LomoSheetDragHandle above its recording status header") {
            val voiceRecordingPanel = sourceRoot.resolve("component/input/VoiceRecordingPanel.kt").readText()
            val handleCallIndex = voiceRecordingPanel.indexOf("LomoSheetDragHandle(")
            val statusTextIndex = voiceRecordingPanel.indexOf("Res.string.recording_in_progress")

            withClue("VoiceRecordingPanel must render LomoSheetDragHandle at the top of its modal sheet column") {
                (handleCallIndex >= 0) shouldBe true
                (statusTextIndex > handleCallIndex) shouldBe true
            }
        }
    }

    private fun File.callsBareModalBottomSheet(): Boolean = readLines()
        .filterNot { line -> line.trimStart().startsWith("import") }
        .filterNot { line -> line.trimStart().startsWith("//") }
        .filterNot { line -> line.trimStart().startsWith("*") }
        .any { line -> BARE_MODAL_SHEET_CALL.containsMatchIn(line) }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
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
        private val BARE_MODAL_SHEET_CALL = Regex("""\bModalBottomSheet\s*\(""")
    }
}
