package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.DraftId
import com.lomo.ui.component.input.InputEditorCommand
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow

class MemoEditorHostSurfaceContractTest : AppFunSpec() {
    init {
        test("given memo editor state when surface is built then app owns grouped session and capabilities") {
            val surface =
                MemoEditorSurface(
                    session =
                    MemoEditorSessionState(
                        imageDirectory = "/images",
                        ownerDraftId = DraftId("contract-draft"),
                    ),
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
                    session =
                    MemoEditorSessionState(
                        imageDirectory = "/images",
                        ownerDraftId = DraftId("contract-draft"),
                    ),
                    capabilities = MemoEditorCapabilities(quickSaveOnBackEnabled = false),
                    commands = MemoEditorCommandHandler { command -> commands += command },
                    operations = MemoEditorOperations.fakeForContract(),
                )

            surface.commands.dispatch(InputEditorCommand.Action(MemoEditorToolbarActionIds.image))
            surface.commands.dispatch(InputEditorCommand.Action(MemoEditorToolbarActionIds.reminder))

            commands shouldContainExactly
                listOf(
                    InputEditorCommand.Action(MemoEditorToolbarActionIds.image),
                    InputEditorCommand.Action(MemoEditorToolbarActionIds.reminder),
                )
        }

        test("given memo toolbar metadata when built then app owns memo action labels and command ids") {
            val tools =
                memoEditorToolbarToolMetadata(
                    availableActions = memoEditorToolbarTools(recording = true),
                    canUndo = true,
                    canRedo = false,
                    canBackfill = false,
                ).associateBy { tool -> tool.id }

            (tools.getValue(MemoEditorToolbarActionIds.backfill).contentDescriptionRes > 0) shouldBe true
            tools.getValue(MemoEditorToolbarActionIds.backfill).enabled shouldBe false
            tools.getValue(MemoEditorToolbarActionIds.reminder).command shouldBe
                InputEditorCommand.Action(MemoEditorToolbarActionIds.reminder)
        }
    }
}

private fun MemoEditorOperations.Companion.fakeForContract(): MemoEditorOperations = MemoEditorOperations(
    onSaveImages = { _, _, _, _ -> },
    onSubmit = { _, _, _, _ -> true },
    submissionState = MutableStateFlow(MemoEditorSubmissionState.Idle),
    onDismiss = { _ -> },
    onToolbarOrderChanged = {},
)
