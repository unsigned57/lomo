package com.lomo.ui.icon

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Capability: provide Material Symbols Rounded variable font icon specs with FILL/wght/autoMirror
 *   metadata backed by the bundled variable font resource; owner: ui-components iconography; priority: P0.
 *
 * Scenarios:
 * - Given the icon catalogue, when getters are resolved, then specs have unique names, valid
 *   snake_case glyph names, and filled variants retain their base glyph.
 * - Given the bundled font and directional icons, when inspected, then the font is non-empty
 *   and only the designated directional icons enable autoMirror for RTL layouts.
 *
 * Observable outcomes: LomoIconSpec metadata count >= 120, unique names, valid snake_case glyph names,
 * exact autoMirror set, filled variant axis contract, and bundled variable font resource presence.
 * TDD proof: Fails before LomoIconSpec/LomoIcon migration while LomoIcons exposes ImageVector getters
 * backed by static LomoIconsPart1..6 paths.
 * Production scope: apps/android/ui-components/src/icon/LomoIcons.kt
 * Production scope: apps/android/ui-components/src/icon/LomoIcon.kt
 * Resource input: apps/android/ui-components/composeResources/font/material_symbols_rounded.ttf
 * Excludes: GPU glyph rasterization by Android Skia/Canvas; verified by runtime UI smoke/screenshots.
 *
 * Test Change Justification:
 * - Reason category: user-requested migration to Material Symbols Rounded variable font.
 * - Old behavior/assertion being replaced: inspecting ImageVector viewport dimensions and VectorNode paths.
 * - Why old assertion is no longer correct: LomoIcons now resolves to variable font LomoIconSpec descriptors
 *   rendered via dev.tclement.fonticons rather than hardcoded SVG ImageVector builders.
 * - Coverage preserved by: full getter reflection count (>= 120), unique descriptor names, valid snake_case
 *   glyph ligature names, filled axis contract, bundled TTF verification, and exact autoMirror set parity.
 * - Why this is not fitting the test to the implementation: asserts the real invariants of the new variable-font
 *   icon architecture (ligature format, uniqueness, RTL mirror parity, and bundled font asset).
 */
class LomoIconsTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")

    init {
        test("every icon resolves to a valid variable-font spec and the variable font resource exists") {
            val fontFile = moduleRoot.resolve("composeResources/font/material_symbols_rounded.ttf")
            withClue("Bundled variable font resource must exist at ${fontFile.path}") {
                fontFile.exists() shouldBe true
                (fontFile.length() > 1_000_000L) shouldBe true
            }

            val iconGetters =
                LomoIcons::class.java.methods
                    .filter { method ->
                        method.parameterCount == 0 &&
                            method.returnType == LomoIconSpec::class.java &&
                            method.name.startsWith("get")
                    }.sortedBy { it.name }

            iconGetters.size shouldBeGreaterThanOrEqual 124

            val seenNames = mutableSetOf<String>()
            iconGetters.forEach { getter ->
                val spec = getter.invoke(LomoIcons) as LomoIconSpec
                withClue("Spec ${getter.name} has blank name") {
                    spec.name.isNotBlank() shouldBe true
                }
                withClue("Spec ${getter.name} has invalid glyphName '${spec.glyphName}'") {
                    GLYPH_NAME_PATTERN.matches(spec.glyphName) shouldBe true
                }
                withClue("Duplicate icon spec name ${spec.name}") {
                    seenNames.add(spec.name) shouldBe true
                }
            }
        }

        test("filled icon variants enable the FILL axis while keeping their base glyph ligature") {
            val expectedFilled =
                mapOf(
                    LomoIcons.CheckCircleFilled to "check_circle",
                    LomoIcons.DateRangeFilled to "date_range",
                    LomoIcons.DeleteFilled to "delete",
                    LomoIcons.ErrorFilled to "error",
                    LomoIcons.KeepFilled to "keep",
                    LomoIcons.PushPinFilled to "push_pin",
                )

            expectedFilled.forEach { (spec, baseGlyph) ->
                withClue("${spec.name} should set filled = true") {
                    spec.filled shouldBe true
                }
                withClue("${spec.name} should reuse base glyph '$baseGlyph'") {
                    spec.glyphName shouldBe baseGlyph
                }
            }
        }

        test("directional icons enable autoMirror while symmetric action icons keep it disabled") {
            val autoMirroredIcons =
                LomoIcons::class.java.methods
                    .filter { method ->
                        method.parameterCount == 0 &&
                            method.returnType == LomoIconSpec::class.java &&
                            method.name.startsWith("get")
                    }.map { getter ->
                        getter.name.removePrefix("get") to (getter.invoke(LomoIcons) as LomoIconSpec).autoMirror
                    }.filter { (_, autoMirrored) -> autoMirrored }
                    .map { (name, _) -> name }

            autoMirroredIcons.shouldContainExactlyInAnyOrder(EXPECTED_AUTO_MIRRORED_ICONS)
        }
    }

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
        private val GLYPH_NAME_PATTERN = Regex("^[a-z0-9_]+$")

        private val EXPECTED_AUTO_MIRRORED_ICONS =
            listOf(
                "ArrowBack",
                "ArrowOutward",
                "Article",
                "CompareArrows",
                "ContentCopy",
                "EditSquare",
                "InsertDriveFile",
                "KeyboardArrowLeft",
                "KeyboardArrowRight",
                "Label",
                "ManageSearch",
                "MenuOpen",
                "NoteAdd",
                "Notes",
                "Redo",
                "RestoreFromTrash",
                "Send",
                "SettingsBackupRestore",
                "ShortText",
                "TextSnippet",
                "Undo",
            )
    }
}
