package com.lomo.app.feature.memo

import android.Manifest
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import com.lomo.app.R
import com.lomo.domain.model.Memo
import com.lomo.ui.component.input.InputEditorCommand
import com.lomo.ui.component.input.InputToolbarActionId
import com.lomo.ui.component.input.InputToolbarTool
import com.lomo.ui.component.input.InputToolbarToolTintRole
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList

data class MemoEditorSessionState(
    val imageDirectory: String?,
    val rootPath: String? = null,
    val imageMap: ImmutableMap<String, Uri> = persistentMapOf(),
    val availableTags: ImmutableList<String> = persistentListOf(),
    val hints: ImmutableList<String> = persistentListOf(),
    val attachedGeoLocation: String? = null,
    val isRecording: Boolean = false,
    val recordingDuration: Long = 0L,
    val recordingAmplitude: Int = 0,
    val dateFormat: String = "yyyy-MM-dd",
    val timeFormat: String = "HH:mm",
    val onImageDirectoryMissing: (() -> Unit)? = null,
    val onCameraCaptureError: ((Throwable) -> Unit)? = null,
)

data class MemoEditorCapabilities(
    val quickSaveOnBackEnabled: Boolean,
    val toolbarActions: ImmutableSet<InputToolbarActionId> =
        memoEditorToolbarTools(recording = false, location = false),
    val toolbarToolOrder: ImmutableList<String> = persistentListOf(),
)

fun interface MemoEditorCommandHandler {
    fun dispatch(command: InputEditorCommand)
}

data class MemoEditorOperations(
    val onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    val onSubmit: (
        memo: Memo?,
        content: String,
        timestampMillis: Long?,
    ) -> Unit,
    val onDismiss: (() -> Unit)?,
    val onToolbarOrderChanged: (List<InputToolbarActionId>) -> Unit,
) {
    companion object
}

data class MemoEditorSurface(
    val session: MemoEditorSessionState,
    val capabilities: MemoEditorCapabilities,
    val commands: MemoEditorCommandHandler,
    val operations: MemoEditorOperations,
)

fun memoEditorToolbarTools(
    recording: Boolean,
    location: Boolean,
): ImmutableSet<InputToolbarActionId> = memoEditorToolbarActionSet(recording = recording, location = location)

object MemoEditorToolbarActionIds {
    val camera = InputToolbarActionId("camera")
    val image = InputToolbarActionId("image")
    val record = InputToolbarActionId("record")
    val tag = InputToolbarActionId("tag")
    val location = InputToolbarActionId("location")
    val backfill = InputToolbarActionId("backfill")
    val clearBackfill = InputToolbarActionId("clear-backfill")
    val todo = InputToolbarActionId("todo")
    val reminder = InputToolbarActionId("reminder")
    val underline = InputToolbarActionId("underline")
    val undo = InputToolbarActionId("undo")
    val redo = InputToolbarActionId("redo")
}

fun defaultMemoEditorToolbarOrder(): List<InputToolbarActionId> =
    listOf(
        MemoEditorToolbarActionIds.camera,
        MemoEditorToolbarActionIds.image,
        MemoEditorToolbarActionIds.record,
        MemoEditorToolbarActionIds.tag,
        MemoEditorToolbarActionIds.location,
        MemoEditorToolbarActionIds.backfill,
        MemoEditorToolbarActionIds.todo,
        MemoEditorToolbarActionIds.reminder,
        MemoEditorToolbarActionIds.underline,
        MemoEditorToolbarActionIds.undo,
        MemoEditorToolbarActionIds.redo,
    )

fun memoEditorToolbarToolMetadata(
    availableActions: Set<InputToolbarActionId>,
    canUndo: Boolean,
    canRedo: Boolean,
    canBackfill: Boolean,
    hasAttachedLocation: Boolean,
): ImmutableList<InputToolbarTool> =
    defaultMemoEditorToolbarOrder()
        .asSequence()
        .filter { actionId -> actionId in availableActions }
        .map { actionId ->
            memoEditorToolbarTool(
                actionId = actionId,
                canUndo = canUndo,
                canRedo = canRedo,
                canBackfill = canBackfill,
                hasAttachedLocation = hasAttachedLocation,
            )
        }.toImmutableList()

fun unsupportedMemoEditorCommand(command: InputEditorCommand): Nothing =
    error("Unsupported memo editor command reached app adapter: $command")

fun existingMemoEditorSurface(
    session: MemoEditorSessionState,
    toolbarToolOrder: ImmutableList<String>,
    onUpdateMemo: (Memo, String) -> Unit,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onToolbarOrderChanged: (List<String>) -> Unit,
): MemoEditorSurface =
    MemoEditorSurface(
        session = session,
        capabilities =
            MemoEditorCapabilities(
                quickSaveOnBackEnabled = false,
                toolbarActions = memoEditorToolbarTools(recording = false, location = false),
                toolbarToolOrder = toolbarToolOrder,
            ),
        commands = MemoEditorCommandHandler(::unsupportedMemoEditorCommand),
        operations =
            MemoEditorOperations(
                onSaveImage = onSaveImage,
                onSubmit = { memo, content, _ ->
                    checkNotNull(memo) {
                        "Existing memo editor surface cannot submit a new memo"
                    }
                    onUpdateMemo(memo, content)
                },
                onDismiss = null,
                onToolbarOrderChanged = { tools ->
                    onToolbarOrderChanged(tools.map { tool -> tool.persistedId })
                },
            ),
    )

private fun memoEditorToolbarActionSet(
    recording: Boolean,
    location: Boolean,
): ImmutableSet<InputToolbarActionId> {
    val actions = defaultMemoEditorToolbarOrder().toMutableSet()
    if (!recording) {
        actions -= MemoEditorToolbarActionIds.record
    }
    if (!location) {
        actions -= MemoEditorToolbarActionIds.location
    }
    return actions.fold(persistentSetOf()) { result, actionId -> result.add(actionId) }
}

private fun memoEditorToolbarTool(
    actionId: InputToolbarActionId,
    canUndo: Boolean,
    canRedo: Boolean,
    canBackfill: Boolean,
    hasAttachedLocation: Boolean,
): InputToolbarTool =
    when (actionId) {
        MemoEditorToolbarActionIds.camera ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_take_photo,
                icon = Icons.Rounded.PhotoCamera,
            )
        MemoEditorToolbarActionIds.image ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_add_image,
                icon = Icons.Rounded.Image,
            )
        MemoEditorToolbarActionIds.record ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_add_voice_memo,
                icon = Icons.Rounded.Mic,
                requiredPermission = Manifest.permission.RECORD_AUDIO,
            )
        MemoEditorToolbarActionIds.tag ->
            InputToolbarTool(
                id = actionId,
                icon = Icons.AutoMirrored.Rounded.Label,
                contentDescriptionRes = R.string.cd_memo_editor_add_tag,
                command = InputEditorCommand.ToggleTagSelector,
                enabled = true,
            )
        MemoEditorToolbarActionIds.location ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_attach_location,
                icon = Icons.Rounded.LocationOn,
                tintRole =
                    if (hasAttachedLocation) {
                        InputToolbarToolTintRole.Highlight
                    } else {
                        InputToolbarToolTintRole.Default
                    },
            )
        MemoEditorToolbarActionIds.backfill ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_backfill_memo,
                icon = Icons.Rounded.History,
                enabled = canBackfill,
            )
        MemoEditorToolbarActionIds.todo ->
            InputToolbarTool(
                id = actionId,
                icon = Icons.Rounded.CheckBox,
                contentDescriptionRes = R.string.cd_memo_editor_add_checkbox,
                command = InputEditorCommand.InsertTodo,
                enabled = true,
            )
        MemoEditorToolbarActionIds.reminder ->
            toolbarTool(
                actionId = actionId,
                contentDescriptionRes = R.string.cd_memo_editor_add_reminder,
                icon = Icons.Rounded.Alarm,
            )
        MemoEditorToolbarActionIds.underline ->
            InputToolbarTool(
                id = actionId,
                icon = Icons.Rounded.FormatUnderlined,
                contentDescriptionRes = R.string.cd_memo_editor_add_underline,
                command = InputEditorCommand.InsertUnderline,
                enabled = true,
            )
        MemoEditorToolbarActionIds.undo ->
            InputToolbarTool(
                id = actionId,
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescriptionRes = R.string.cd_memo_editor_undo,
                command = InputEditorCommand.Undo,
                enabled = canUndo,
            )
        MemoEditorToolbarActionIds.redo ->
            InputToolbarTool(
                id = actionId,
                icon = Icons.AutoMirrored.Rounded.Redo,
                contentDescriptionRes = R.string.cd_memo_editor_redo,
                command = InputEditorCommand.Redo,
                enabled = canRedo,
            )
        else -> error("Unsupported memo toolbar action id: ${actionId.persistedId}")
    }

private fun toolbarTool(
    actionId: InputToolbarActionId,
    contentDescriptionRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    tintRole: InputToolbarToolTintRole = InputToolbarToolTintRole.Default,
    requiredPermission: String? = null,
): InputToolbarTool =
    InputToolbarTool(
        id = actionId,
        icon = icon,
        contentDescriptionRes = contentDescriptionRes,
        command = InputEditorCommand.Action(actionId),
        enabled = enabled,
        tintRole = tintRole,
        requiredPermission = requiredPermission,
    )
