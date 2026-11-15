package com.lomo.ui.component.input

import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.icon.LomoIconSpec
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@JvmInline
value class InputToolbarActionId(val persistedId: String) {
    init {
        require(persistedId.isNotBlank()) { "Input toolbar action id must not be blank" }
    }

    companion object {
        fun fromPersistedId(id: String): InputToolbarActionId? {
            val normalized = id.trim()
            return if (normalized.isBlank()) {
                null
            } else {
                InputToolbarActionId(normalized)
            }
        }
    }
}

sealed interface InputEditorCommand {
    data object Undo : InputEditorCommand
    data object Redo : InputEditorCommand
    data object StopRecording : InputEditorCommand
    data object CancelRecording : InputEditorCommand
    data object ToggleTagSelector : InputEditorCommand
    data object InsertTodo : InputEditorCommand
    data object InsertUnderline : InputEditorCommand

    data class RemoveText(val sourceText: String, val range: androidx.compose.ui.text.TextRange) : InputEditorCommand

    data class Action(val id: InputToolbarActionId) : InputEditorCommand
}

fun interface InputEditorCommandHandler {
    fun dispatch(command: InputEditorCommand)
}

enum class InputToolbarToolTintRole {
    Default,
    Highlight,
}

enum class InputToolbarToolPlacement {
    Primary,
    SecondaryTray,
    HeaderEnd,
}

data class InputToolbarTool(
    val id: InputToolbarActionId,
    val icon: LomoIconSpec,
    val contentDescriptionRes: Int,
    val command: InputEditorCommand,
    val enabled: Boolean,
    val tintRole: InputToolbarToolTintRole = InputToolbarToolTintRole.Default,
    val placement: InputToolbarToolPlacement = InputToolbarToolPlacement.Primary,
    val requiredPermission: String? = null,
)

data class InputEditorCapabilities(val toolbarTools: ImmutableList<InputToolbarTool>)

data class InputEditorRecordingState(
    val isRecording: Boolean = false,
    val durationMillis: Long = 0L,
    /** Latest device sample; null while idle or when the device read is unavailable. */
    val amplitude: Int? = null,
)

data class InputEditorSurfaceState(
    val inputState: androidx.compose.foundation.text.input.TextFieldState,
    val previewState: MarkdownRenderState,
    val focusRequestToken: Long = 0L,
    val isExpanded: Boolean = false,
    val displayMode: InputEditorDisplayMode = InputEditorDisplayMode.Edit,
    val availableTags: ImmutableList<String> = persistentListOf(),
    val recordingState: InputEditorRecordingState = InputEditorRecordingState(),
    val hints: ImmutableList<String> = persistentListOf(),
    val toolbarOrder: ImmutableList<InputToolbarActionId> = persistentListOf(),
    val capabilities: InputEditorCapabilities,
    val actionBadges: ImmutableList<InputEditorActionBadge> = persistentListOf(),
    val hiddenTextRanges: ImmutableList<androidx.compose.ui.text.TextRange> = persistentListOf(),
    val inputTransformation: androidx.compose.foundation.text.input.InputTransformation? = null,
) {
    val inputValue: TextFieldValue
        get() = TextFieldValue(inputState.text.toString(), inputState.selection, inputState.composition)
}

data class InputToolbarRegistryState(
    val tools: ImmutableList<InputToolbarTool>,
    val highlightedCommands: Set<InputEditorCommand> = emptySet(),
)

class InputToolbarRegistry private constructor(private val state: InputToolbarRegistryState) {
    fun resolveTools(persistedOrder: List<String>): ImmutableList<InputToolbarTool> {
        val normalizedOrder = normalizePersistedOrder(persistedOrder)
        val seen = mutableSetOf<InputToolbarActionId>()
        val toolsById = state.tools.associateBy(InputToolbarTool::id)
        val orderedIds =
            buildList {
                normalizedOrder
                    .asSequence()
                    .filter { toolId -> toolId in toolsById }
                    .filter(seen::add)
                    .forEach(::add)
                state.tools
                    .asSequence()
                    .map(InputToolbarTool::id)
                    .filter(seen::add)
                    .forEach(::add)
            }
        return orderedIds.map { id ->
            val tool = toolsById.getValue(id)
            if (tool.command in state.highlightedCommands) {
                tool.copy(tintRole = InputToolbarToolTintRole.Highlight)
            } else {
                tool
            }
        }.toImmutableList()
    }

    fun normalizePersistedOrder(persistedOrder: List<String>): List<InputToolbarActionId> {
        val availableIds = state.tools.mapTo(mutableSetOf(), InputToolbarTool::id)
        val seen = mutableSetOf<InputToolbarActionId>()
        return persistedOrder
            .asSequence()
            .mapNotNull(InputToolbarActionId::fromPersistedId)
            .filter { toolId -> toolId in availableIds }
            .filter(seen::add)
            .toList()
    }

    companion object {
        fun create(state: InputToolbarRegistryState): InputToolbarRegistry = InputToolbarRegistry(state)
    }
}

data class InputEditorActionBadge(
    val text: String,
    val icon: LomoIconSpec,
    val command: InputEditorCommand,
    val benchmarkTag: String? = null,
)
