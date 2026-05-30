// architectural-boundary-check
package com.lomo.ui.component.input

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.collections.immutable.persistentListOf

/*
 * Behavior Contract:
 * - Unit under test: InputSheet surface model.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: expose one typed editor surface and command sink instead of flattened memo/tool callbacks.
 *
 * Scenarios:
 * - Given core text, preview, hints, tags, recording, and toolbar action metadata, when an
 *   InputSheetState is created, then the state is grouped under a typed editor surface with generic actions.
 * - Given editor commands are dispatched, when InputSheetCallbacks receives typed commands, then one
 *   command sink owns tool actions and no default tool no-op callback is required.
 *
 * Observable outcomes:
 * - InputSheetState has `surface` and no flattened toolbar order field.
 * - InputSheetCallbacks has `commands` and no flattened media/recording/location/reminder callbacks.
 * - Typed command dispatch records observable command values.
 *
 * TDD proof:
 * - RED: `./gradlew :ui-components:testDebugUnitTest --tests 'com.lomo.ui.component.input.InputSheetSurfaceContractTest'`
 *   fails before M2 because InputSheet still exposes flattened callbacks/default no-op and state fields.
 *
 * Excludes:
 * - Compose rendering, focus timing, lifecycle effects, Android permission result behavior.
 */
class InputSheetSurfaceContractTest : UiComponentsFunSpec() {
    private val uiSourceRoot = resolveModuleRoot("ui-components").resolve("src/main/java")

    init {
        test("given input sheet state when constructed then editor state lives behind typed surface") {
            val state =
                InputSheetState(
                    surface =
                        InputEditorSurfaceState(
                            inputValue = TextFieldValue("hello"),
                            previewContent = "preview",
                            availableTags = persistentListOf("work"),
                            hints = persistentListOf("hint"),
                            toolbarOrder = persistentListOf(InputToolbarActionId("primary")),
                            capabilities = InputEditorCapabilities(toolbarTools = persistentListOf(contractTool("primary"))),
                        ),
                )

            state.surface.inputValue.text shouldBe "hello"
            state.surface.previewContent shouldBe "preview"
            state.surface.availableTags shouldContainExactly listOf("work")
            state.surface.hints shouldContainExactly listOf("hint")
            state.surface.toolbarOrder shouldContainExactly listOf(InputToolbarActionId("primary"))
        }

        test("given callbacks when commands dispatch then typed command sink records tool commands") {
            val commands = mutableListOf<InputEditorCommand>()
            val callbacks =
                InputSheetCallbacks(
                    onInputValueChange = {},
                    onDismiss = {},
                    onToggleExpanded = {},
                    onCollapse = {},
                    onDisplayModeChange = {},
                    onConsumeBackPress = { false },
                    commands = InputEditorCommandHandler { command -> commands += command },
                    onSubmit = {},
                    onToolbarOrderChanged = {},
                )

            callbacks.commands.dispatch(InputEditorCommand.Action(InputToolbarActionId("primary")))
            callbacks.commands.dispatch(InputEditorCommand.CancelRecording)

            commands shouldContainExactly
                listOf(
                    InputEditorCommand.Action(InputToolbarActionId("primary")),
                    InputEditorCommand.CancelRecording,
                )
        }

        test("given input component sources when inspected then flattened callbacks and string toolbar ids are deleted") {
            val sheetSource = uiSource("com/lomo/ui/component/input/InputSheet.kt").readText()
            val toolbarSource = uiSource("com/lomo/ui/component/input/InputEditorToolbarComponents.kt").readText()

            withClue("InputSheetState must expose typed editor surface") {
                sheetSource.contains("val surface: InputEditorSurfaceState") shouldBe true
            }
            withClue("InputSheetCallbacks must expose typed command sink") {
                sheetSource.contains("val commands: InputEditorCommandHandler") shouldBe true
            }
            listOf(
                "onUndo:",
                "onRedo:",
                "onImageClick:",
                "onCameraClick:",
                "onStartRecording:",
                "onStopRecording:",
                "onCancelRecording:",
                "onLocationClick:",
                "onBackfillClick:",
                "onInsertReminder:",
                "onInputToolbarToolOrderChanged:",
                "inputToolbarToolOrder:",
            ).forEach { forbidden ->
                withClue("InputSheet surface must not expose flattened callback $forbidden") {
                    sheetSource.contains(forbidden) shouldBe false
                }
                withClue("InputEditorToolbar surface must not expose flattened callback $forbidden") {
                    toolbarSource.contains(forbidden) shouldBe false
                }
            }
            withClue("InputEditorToolbar must consume registry tools rather than string ids") {
                toolbarSource.contains("tools: ImmutableList<InputToolbarTool>") shouldBe true
            }
            withClue("InputEditorToolbar must not keep string-id ordering helpers") {
                toolbarSource.contains("resolveInputToolbarToolIds") shouldBe false
            }
        }
    }

    private fun contractTool(id: String): InputToolbarTool =
        InputToolbarTool(
            id = InputToolbarActionId(id),
            icon = Icons.Rounded.Image,
            contentDescriptionRes = com.lomo.ui.R.string.cd_send,
            command = InputEditorCommand.Action(InputToolbarActionId(id)),
            enabled = true,
        )

    private fun uiSource(relativePath: String): File =
        uiSourceRoot.resolve(relativePath).also { file ->
            withClue("Expected ui source file exists: ${file.path}") {
                file.isFile shouldBe true
            }
        }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
