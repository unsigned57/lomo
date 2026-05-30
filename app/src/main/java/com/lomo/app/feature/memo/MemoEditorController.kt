package com.lomo.app.feature.memo

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.main.MemoUiImageContentResolver
import com.lomo.app.util.CameraCaptureUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.component.input.InputEditorActionBadge
import com.lomo.ui.component.input.InputEditorCapabilities
import com.lomo.ui.component.input.InputEditorCommand
import com.lomo.ui.component.input.InputEditorCommandHandler
import com.lomo.ui.component.input.InputEditorDisplayMode
import com.lomo.ui.component.input.InputEditorRecordingState
import com.lomo.ui.component.input.InputEditorSurfaceState
import com.lomo.ui.component.input.InputSheetCallbacks
import com.lomo.ui.component.input.InputSheetState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class MemoEditorMode {
    Compact,
    Expanded,
}

@Stable
class MemoEditorController
    internal constructor() {
        private val undoRedoManager = UndoRedoManager()

        var isVisible by mutableStateOf(false)
            private set

        var focusRequestToken by mutableLongStateOf(0L)
            private set

        var editingMemo: Memo? by mutableStateOf(null)
            private set

        var inputValue by mutableStateOf(TextFieldValue(""))
            private set

        var mode by mutableStateOf(MemoEditorMode.Compact)
            private set

        var displayMode by mutableStateOf(InputEditorDisplayMode.Edit)
            private set

        internal val backfillSelection = MemoBackfillSelectionState()

        val canUndo: Boolean
            get() = undoRedoManager.canUndo

        val canRedo: Boolean
            get() = undoRedoManager.canRedo

        fun openForCreate(initialText: String = "") {
            editingMemo = null
            inputValue = TextFieldValue(initialText, TextRange(initialText.length))
            undoRedoManager.reset()
            mode = MemoEditorMode.Compact
            displayMode = InputEditorDisplayMode.Edit
            backfillSelection.clear()
            isVisible = true
            focusRequestToken += 1L
        }

        fun openForEdit(memo: Memo) {
            editingMemo = memo
            inputValue = TextFieldValue(memo.content, TextRange(memo.content.length))
            undoRedoManager.reset()
            mode = MemoEditorMode.Compact
            displayMode = InputEditorDisplayMode.Edit
            backfillSelection.clear()
            isVisible = true
            focusRequestToken += 1L
        }

        fun updateInputValue(value: TextFieldValue) {
            val processedValue = autoContinueListMarker(inputValue, value) ?: value
            undoRedoManager.recordTextChange(
                previousValue = inputValue,
                newValue = processedValue,
            )
            inputValue = processedValue
        }

        fun undo() {
            inputValue = undoRedoManager.undo(inputValue)
        }

        fun redo() {
            inputValue = undoRedoManager.redo(inputValue)
        }

        fun ensureVisible() {
            isVisible = true
            focusRequestToken += 1L
        }

        fun setExpanded(expanded: Boolean) {
            if (expanded) {
                if (mode == MemoEditorMode.Expanded) return
                mode = MemoEditorMode.Expanded
            } else {
                if (mode == MemoEditorMode.Compact) return
                mode = MemoEditorMode.Compact
                displayMode = InputEditorDisplayMode.Edit
            }
        }

        fun toggleExpanded() {
            setExpanded(mode == MemoEditorMode.Compact)
        }

        fun consumeBackPress(): Boolean =
            if (mode == MemoEditorMode.Expanded) {
                setExpanded(false)
                true
            } else {
                false
            }

        fun updateDisplayMode(mode: InputEditorDisplayMode) {
            if (displayMode == mode) return
            val previousDisplayMode = displayMode
            displayMode = mode
            val isRestoringEditFromPreview =
                isVisible &&
                    previousDisplayMode == InputEditorDisplayMode.Preview &&
                    mode == InputEditorDisplayMode.Edit
            if (isRestoringEditFromPreview) {
                focusRequestToken += 1L
            }
        }

        fun close() {
            isVisible = false
            editingMemo = null
            inputValue = TextFieldValue("")
            undoRedoManager.reset()
            mode = MemoEditorMode.Compact
            displayMode = InputEditorDisplayMode.Edit
            backfillSelection.clear()
        }
    }

fun MemoEditorController.appendMarkdownBlock(markdown: String) {
    val currentText = inputValue.text
    val selectionStart = inputValue.selection.start.coerceIn(0, currentText.length)
    val selectionEnd = inputValue.selection.end.coerceIn(0, currentText.length)
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)

    val prefix = currentText.substring(0, start)
    val suffix = currentText.substring(end)

    val needsLeadingNewline = start > 0 && prefix[start - 1] != '\n'
    val needsTrailingNewline = end < currentText.length && suffix[0] != '\n'

    val insertion = buildString {
        if (needsLeadingNewline) append('\n')
        append(markdown)
        if (needsTrailingNewline) append('\n')
    }

    val newText = prefix + insertion + suffix
    val newCursorPosition = start + insertion.length
    updateInputValue(TextFieldValue(newText, TextRange(newCursorPosition)))
}

fun MemoEditorController.appendImageMarkdown(path: String) {
    appendMarkdownBlock(markdown = "![image]($path)")
}

internal fun MemoEditorController.cancelBackfillSelection() {
    backfillSelection.clear()
}

@Composable
fun rememberMemoEditorController(): MemoEditorController = remember { MemoEditorController() }

internal fun autoContinueListMarker(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
    val oldText = oldValue.text
    val newText = newValue.text

    if (newText.length != oldText.length + 1) return null
    val cursor = newValue.selection.start
    if (cursor <= 0 || newText[cursor - 1] != '\n') return null

    val newlineIndex = cursor - 1

    var lineStart = newlineIndex - 1
    while (lineStart >= 0 && oldText[lineStart] != '\n') {
        lineStart--
    }
    lineStart++

    val completedLine = oldText.substring(lineStart, newlineIndex)

    val bulletRegex = Regex("""^([\t ]*)([-*+])([\t ]+\[[ xX]])?([\t ]+)""")
    val orderedRegex = Regex("""^([\t ]*)(\d+)\.([\t ]+)""")

    val bulletMatch = bulletRegex.find(completedLine)
    val orderedMatch = orderedRegex.find(completedLine)

    val result = when {
        bulletMatch != null -> {
            val indent = bulletMatch.groupValues[1]
            val symbol = bulletMatch.groupValues[2]
            val checkbox = bulletMatch.groupValues[3]
            val trailingSpaces = bulletMatch.groupValues[4]

            val markerLength = bulletMatch.value.length
            val restText = completedLine.substring(markerLength)

            if (restText.isBlank()) {
                val prefix = oldText.substring(0, lineStart)
                val suffix = oldText.substring(newlineIndex)
                val cleanText = prefix + "\n" + suffix.removePrefix("\n")
                val newCursor = lineStart + 1
                TextFieldValue(cleanText, TextRange(newCursor))
            } else {
                val newCheckbox = if (checkbox.isNotEmpty()) " [ ]" else ""
                val nextMarker = indent + symbol + newCheckbox + trailingSpaces
                val prefix = newText.substring(0, cursor)
                val suffix = newText.substring(cursor)
                val updatedText = prefix + nextMarker + suffix
                TextFieldValue(updatedText, TextRange(cursor + nextMarker.length))
            }
        }
        orderedMatch != null -> {
            val indent = orderedMatch.groupValues[1]
            val numStr = orderedMatch.groupValues[2]
            val trailingSpaces = orderedMatch.groupValues[3]

            val markerLength = orderedMatch.value.length
            val restText = completedLine.substring(markerLength)

            if (restText.isBlank()) {
                val prefix = oldText.substring(0, lineStart)
                val suffix = oldText.substring(newlineIndex)
                val cleanText = prefix + "\n" + suffix.removePrefix("\n")
                val newCursor = lineStart + 1
                TextFieldValue(cleanText, TextRange(newCursor))
            } else {
                val nextNum = (numStr.toIntOrNull() ?: 1) + 1
                val nextMarker = "$indent$nextNum.$trailingSpaces"
                val prefix = newText.substring(0, cursor)
                val suffix = newText.substring(cursor)
                val updatedText = prefix + nextMarker + suffix
                TextFieldValue(updatedText, TextRange(cursor + nextMarker.length))
            }
        }
        else -> null
    }

    return result
}

@Composable
fun MemoEditorSheetHost(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
) {
    if (!controller.isVisible) return

    val session = surface.session
    var showBackfillDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    val onReminderRequested = rememberReminderInsertGate(onReady = { showReminderDialog = true })
    val mediaActions =
        rememberMemoEditorMediaActions(
            controller = controller,
            imageDirectory = session.imageDirectory,
            onSaveImage = surface.operations.onSaveImage,
            onImageDirectoryMissing = session.onImageDirectoryMissing,
            onCameraCaptureError = session.onCameraCaptureError,
        )
    var previewContent by remember { mutableStateOf(controller.inputValue.text) }
    LaunchedEffect(controller.inputValue.text, session.rootPath, session.imageDirectory, session.imageMap) {
        previewContent =
            withContext(Dispatchers.Default) {
                buildMemoEditorPreviewContent(
                    content = controller.inputValue.text,
                    rootPath = session.rootPath,
                    imagePath = session.imageDirectory,
                    imageMap = session.imageMap,
                )
            }
    }
    val sheetState =
        buildMemoEditorSheetState(
            controller = controller,
            surface = surface,
            previewContent = previewContent,
        )
    val editorCommandHandler =
        InputEditorCommandHandler { command ->
            when (command) {
                InputEditorCommand.Undo -> controller.undo()
                InputEditorCommand.Redo -> controller.redo()
                is InputEditorCommand.Action ->
                    when (command.id) {
                        MemoEditorToolbarActionIds.camera -> mediaActions.onCameraClick()
                        MemoEditorToolbarActionIds.image -> mediaActions.onImageClick()
                        MemoEditorToolbarActionIds.backfill -> {
                            if (shouldOpenMemoBackfillDialog(isEditingExistingMemo = controller.editingMemo != null)) {
                                showBackfillDialog = true
                            }
                        }
                        MemoEditorToolbarActionIds.clearBackfill -> controller.cancelBackfillSelection()
                        MemoEditorToolbarActionIds.reminder -> onReminderRequested()
                        else -> surface.commands.dispatch(command)
                    }
                else -> surface.commands.dispatch(command)
            }
        }
    val sheetCallbacks =
        buildMemoEditorSheetCallbacks(
            controller = controller,
            surface = surface,
            editorCommandHandler = editorCommandHandler,
        )

    com.lomo.ui.component.input.InputSheet(
        state = sheetState,
        callbacks = sheetCallbacks,
        benchmarkRootTag = BenchmarkAnchorContract.INPUT_SHEET_ROOT,
        benchmarkEditorTag = BenchmarkAnchorContract.INPUT_EDITOR,
        benchmarkSubmitTag = BenchmarkAnchorContract.INPUT_SUBMIT,
    )

    if (showBackfillDialog) {
        MemoBackfillDateTimeDialog(
            initialTimestampMillis = controller.backfillSelection.timestampMillis,
            onDismiss = { showBackfillDialog = false },
            onConfirm = { timestampMillis ->
                controller.backfillSelection.setTimestampForCreate(
                    timestampMillis = timestampMillis,
                    isEditingExistingMemo = controller.editingMemo != null,
                )
                showBackfillDialog = false
            },
        )
    }

    if (showReminderDialog) {
        ReminderInsertDialog(
            onDismiss = { showReminderDialog = false },
            onConfirm = { token ->
                controller.updateInputValue(buildReminderInsertionValue(controller.inputValue, token))
                showReminderDialog = false
            },
        )
    }
}

private fun buildMemoEditorSheetState(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
    previewContent: String,
): InputSheetState {
    val session = surface.session
    return InputSheetState(
        surface =
            InputEditorSurfaceState(
                inputValue = controller.inputValue,
                previewContent = previewContent,
                focusRequestToken = controller.focusRequestToken,
                isExpanded = controller.mode == MemoEditorMode.Expanded,
                displayMode = controller.displayMode,
                availableTags = session.availableTags,
                recordingState =
                    InputEditorRecordingState(
                        isRecording = session.isRecording,
                        durationMillis = session.recordingDuration,
                        amplitude = session.recordingAmplitude,
                    ),
                hints = session.hints,
                toolbarOrder =
                    surface.capabilities.toolbarToolOrder.mapNotNull { id ->
                        com.lomo.ui.component.input.InputToolbarActionId.fromPersistedId(id)
                    }.toImmutableList(),
                capabilities =
                    InputEditorCapabilities(
                        toolbarTools =
                            memoEditorToolbarToolMetadata(
                                availableActions = surface.capabilities.toolbarActions,
                                canUndo = controller.canUndo,
                                canRedo = controller.canRedo,
                                canBackfill = controller.editingMemo == null,
                                hasAttachedLocation = session.attachedGeoLocation != null,
                            ),
                    ),
                actionBadge =
                    controller.backfillSelection.timestampMillis?.let { timestampMillis ->
                        InputEditorActionBadge(
                            text =
                                formatMemoBackfillBadgeText(
                                    timestampMillis = timestampMillis,
                                    dateFormat = session.dateFormat,
                                    timeFormat = session.timeFormat,
                                ),
                            icon = Icons.Rounded.History,
                            command = InputEditorCommand.Action(MemoEditorToolbarActionIds.clearBackfill),
                        )
                    },
            ),
    )
}

private fun buildMemoEditorSheetCallbacks(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
    editorCommandHandler: InputEditorCommandHandler,
): InputSheetCallbacks =
    InputSheetCallbacks(
        onInputValueChange = controller::updateInputValue,
        onDismiss = {
            controller.close()
            surface.operations.onDismiss?.invoke()
        },
        onToggleExpanded = controller::toggleExpanded,
        onCollapse = { controller.setExpanded(false) },
        onDisplayModeChange = controller::updateDisplayMode,
        onConsumeBackPress = controller::consumeBackPress,
        onSubmit = { content ->
            surface.operations.onSubmit(
                controller.editingMemo,
                content,
                controller.backfillSelection.timestampMillisForCreateSubmit(
                    isEditingExistingMemo = controller.editingMemo != null,
                ),
            )
            controller.close()
        },
        commands = editorCommandHandler,
        onToolbarOrderChanged = surface.operations.onToolbarOrderChanged,
        autoSubmitOnDismiss = surface.capabilities.quickSaveOnBackEnabled && controller.editingMemo == null,
        hasDraftPersistence = controller.editingMemo == null,
    )

private data class MemoEditorMediaActions(
    val onImageClick: () -> Unit,
    val onCameraClick: () -> Unit,
)

@Composable
private fun rememberMemoEditorMediaActions(
    controller: MemoEditorController,
    imageDirectory: String?,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onImageDirectoryMissing: (() -> Unit)?,
    onCameraCaptureError: ((Throwable) -> Unit)?,
): MemoEditorMediaActions {
    val context = LocalContext.current
    val settingsNotSetMessage = stringResource(R.string.settings_not_set)
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun clearPendingCapture() {
        runCatching { pendingCameraFile?.delete() }
        pendingCameraFile = null
        pendingCameraUri = null
    }

    fun requireImageDirectory(action: () -> Unit) {
        if (imageDirectory == null) {
            showImageDirectoryMissingToast(
                context = context,
                message = settingsNotSetMessage,
                onImageDirectoryMissing = onImageDirectoryMissing,
            )
        } else {
            action()
        }
    }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { selectedUri ->
                onSaveImage(selectedUri, controller::appendImageMarkdown, null)
            }
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            val file = pendingCameraFile
            val uri = pendingCameraUri
            if (isSuccess && uri != null) {
                onSaveImage(
                    uri,
                    { path ->
                        controller.appendImageMarkdown(path)
                        runCatching { file?.delete() }
                        pendingCameraFile = null
                        pendingCameraUri = null
                    },
                    ::clearPendingCapture,
                )
            } else {
                clearPendingCapture()
            }
        }

    return MemoEditorMediaActions(
        onImageClick = {
            requireImageDirectory {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        },
        onCameraClick = {
            requireImageDirectory {
                runCatching {
                    val (file, uri) = CameraCaptureUtils.createTempCaptureUri(context)
                    pendingCameraFile = file
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }.onFailure {
                    clearPendingCapture()
                    onCameraCaptureError?.invoke(it)
                }
            }
        },
    )
}

internal fun buildMemoEditorPreviewContent(
    content: String,
    rootPath: String?,
    imagePath: String?,
    imageMap: Map<String, Uri>,
    resolver: MemoUiImageContentResolver = MemoUiImageContentResolver(),
): String =
    resolver.buildProcessedContent(
        content = content,
        rootPath = rootPath,
        imagePath = imagePath,
        imageMap = imageMap,
    )

private fun showImageDirectoryMissingToast(
    context: android.content.Context,
    message: String,
    onImageDirectoryMissing: (() -> Unit)?,
) {
    if (onImageDirectoryMissing != null) {
        onImageDirectoryMissing()
        return
    }

    Toast
        .makeText(
            context,
            message,
            Toast.LENGTH_SHORT,
        ).show()
}
