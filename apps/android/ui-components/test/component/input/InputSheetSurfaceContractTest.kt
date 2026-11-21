package com.lomo.ui.component.input

import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

class InputSheetSurfaceContractTest : UiComponentsFunSpec() {
    init {
        test("given input sheet state when constructed then editor state lives behind typed surface") {
            val state =
                InputSheetState(
                    surface =
                    InputEditorSurfaceState(
                        inputState = androidx.compose.foundation.text.input.TextFieldState("hello"),
                        previewState = MarkdownRenderState.Ready(emptyPreviewDocument()),
                        availableTags = persistentListOf("work"),
                        hints = persistentListOf("hint"),
                        toolbarOrder = persistentListOf(InputToolbarActionId("primary")),
                        capabilities = InputEditorCapabilities(
                            toolbarTools = persistentListOf(contractTool("primary")),
                        ),
                    ),
                    ownerSubmission = InputSheetOwnerSubmission.Pending,
                )

            state.surface.inputValue.text shouldBe "hello"
            state.surface.previewState shouldBe MarkdownRenderState.Ready(emptyPreviewDocument())
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
                    onExpandedChange = {},
                    onDisplayModeChange = {},
                    commands = InputEditorCommandHandler { command -> commands += command },
                    onSubmit = { true },
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
    }

    private fun contractTool(id: String): InputToolbarTool = InputToolbarTool(
        id = InputToolbarActionId(id),
        icon = LomoIcons.Image,
        contentDescriptionRes = android.R.string.ok,
        command = InputEditorCommand.Action(InputToolbarActionId(id)),
        enabled = true,
    )
}

private fun emptyPreviewDocument() = MarkdownRenderDocument(
    sourceByteLength = 0uL,
    plainText = "preview",
    tagNames = emptyList(),
    attachments = emptyList(),
    blocks = emptyList(),
)
