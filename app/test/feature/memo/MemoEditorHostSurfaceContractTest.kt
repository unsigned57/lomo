// architectural-boundary-check
package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputEditorCommand
import com.lomo.ui.component.input.InputToolbarToolTintRole
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: Memo editor host adapter surface.
 * - Owning layer: app feature/memo.
 * - Priority tier: P1.
 * - Capability: memo editor hosts receive a small typed session/capability/command surface instead of
 *   flattened recording, location, toolbar, media, save, and submit parameters.
 *
 * Scenarios:
 * - Given memo editor state and capabilities, when the app builds a MemoEditorSurface, then memo-specific
 *   state is grouped in session and capabilities objects before crossing into ui-components.
 * - Given memo tool commands, when commands are dispatched through the app command handler, then app-owned
 *   memo orchestration observes typed commands instead of flattened callback parameters.
 *
 * Observable outcomes:
 * - MemoEditorSurface exposes session, capabilities, and commands objects.
 * - Command dispatch records typed InputEditorCommand values.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.app.feature.memo.MemoEditorHostSurfaceContractTest'`
 *   fails before M2 because MemoEditorSurface/session/capabilities/command-handler objects do not exist.
 *
 * Excludes:
 * - Compose rendering, ActivityResult launchers, repository persistence, domain/data lifecycle behavior.
 */
class MemoEditorHostSurfaceContractTest : AppFunSpec() {
    private val appSourceRoot = resolveModuleRoot("app").resolve("src")

    init {
        test("given memo editor state when surface is built then app owns grouped session and capabilities") {
            val surface =
                MemoEditorSurface(
                    session = MemoEditorSessionState(imageDirectory = "/images"),
                    capabilities = MemoEditorCapabilities(quickSaveOnBackEnabled = true),
                    commands = MemoEditorCommandHandler { },
                    operations = MemoEditorOperations.fakeForContract(),
                )

            surface.session.imageDirectory shouldBe "/images"
            surface.capabilities.quickSaveOnBackEnabled shouldBe true
        }

        test("given memo editor commands when dispatched then command handler owns typed editor orchestration") {
            val commands = mutableListOf<InputEditorCommand>()
            val surface =
                MemoEditorSurface(
                    session = MemoEditorSessionState(imageDirectory = "/images"),
                    capabilities = MemoEditorCapabilities(quickSaveOnBackEnabled = false),
                    commands = MemoEditorCommandHandler { command -> commands += command },
                    operations = MemoEditorOperations.fakeForContract(),
                )

            surface.commands.dispatch(InputEditorCommand.Action(MemoEditorToolbarActionIds.image))
            surface.commands.dispatch(InputEditorCommand.Action(MemoEditorToolbarActionIds.location))

            commands shouldContainExactly
                listOf(
                    InputEditorCommand.Action(MemoEditorToolbarActionIds.image),
                    InputEditorCommand.Action(MemoEditorToolbarActionIds.location),
                )
        }

        test("given memo toolbar metadata when built then app owns memo action labels and command ids") {
            val tools =
                memoEditorToolbarToolMetadata(
                    availableActions = memoEditorToolbarTools(recording = true, location = true),
                    canUndo = true,
                    canRedo = false,
                    canBackfill = false,
                    hasAttachedLocation = true,
                ).associateBy { tool -> tool.id }

            (tools.getValue(MemoEditorToolbarActionIds.backfill).contentDescriptionRes > 0) shouldBe true
            tools.getValue(MemoEditorToolbarActionIds.backfill).enabled shouldBe false
            tools.getValue(MemoEditorToolbarActionIds.reminder).command shouldBe
                InputEditorCommand.Action(MemoEditorToolbarActionIds.reminder)
            tools.getValue(MemoEditorToolbarActionIds.location).tintRole shouldBe InputToolbarToolTintRole.Highlight
        }

        test("given memo editor host source when inspected then flattened editor parameters are deleted") {
            val hostSource = appSource("feature/memo/MemoInteractionHost.kt").readText()
            val sheetSource = appSource("feature/memo/MemoEditorController.kt").readText()

            assertMemoEditorSurfaceOnly(
                source = hostSource,
                functionName = "MemoInteractionHost",
                requiredParameter = "editorSurface: MemoEditorSurface",
            )
            assertMemoEditorSurfaceOnly(
                source = sheetSource,
                functionName = "MemoEditorSheetHost",
                requiredParameter = "surface: MemoEditorSurface",
            )
        }
    }

    private fun assertMemoEditorSurfaceOnly(
        source: String,
        functionName: String,
        requiredParameter: String,
    ) {
        withClue("$functionName must expose one app editor surface entrypoint") {
            Regex("""fun\s+$functionName\s*\(""").findAll(source).count() shouldBe 1
        }
        withClue("$functionName must expose the typed memo editor surface") {
            source.contains(requiredParameter) shouldBe true
        }
        val forbiddenParameters =
            listOf(
                "onSaveImage:",
                "onStartRecording:",
                "onStopRecording:",
                "onCancelRecording:",
                "onLocationClick:",
                "onBackfillClick:",
                "onInputToolbarToolOrderChanged:",
                "inputToolbarToolOrder:",
                "isRecordingFlow:",
                "recordingDurationFlow:",
                "recordingAmplitudeFlow:",
            )
        forbiddenParameters.forEach { forbidden ->
            withClue("$functionName must not expose flattened editor parameter $forbidden") {
                source.substringAfter("fun $functionName(").substringBefore(") {").contains(forbidden) shouldBe false
            }
        }
    }

    private fun appSource(relativePath: String): File =
        appSourceRoot.resolve(relativePath).also { file ->
            withClue("Expected app source file exists: ${file.path}") {
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
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}

private fun MemoEditorOperations.Companion.fakeForContract(): MemoEditorOperations =
    MemoEditorOperations(
        onSaveImage = { _, _, _ -> },
        onSubmit = { _, _, _ -> },
        onDismiss = {},
        onToolbarOrderChanged = {},
    )
