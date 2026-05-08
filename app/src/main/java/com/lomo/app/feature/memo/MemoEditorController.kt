package com.lomo.app.feature.memo

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.main.MemoUiImageContentResolver
import com.lomo.app.util.CameraCaptureUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.component.input.InputEditorDisplayMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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
            undoRedoManager.recordTextChange(
                previousValue = inputValue,
                newValue = value,
            )
            inputValue = value
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
    val appendedText =
        if (currentText.isEmpty()) {
            markdown
        } else {
            "$currentText\n$markdown"
        }
    updateInputValue(TextFieldValue(appendedText, TextRange(appendedText.length)))
}

fun MemoEditorController.appendImageMarkdown(path: String) {
    appendMarkdownBlock(markdown = "![image]($path)")
}

internal fun MemoEditorController.cancelBackfillSelection() {
    backfillSelection.clear()
}

@Composable
fun rememberMemoEditorController(): MemoEditorController = remember { MemoEditorController() }

@Composable
fun MemoEditorSheetHost(
    controller: MemoEditorController,
    imageDirectory: String?,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onSubmit: (
        memo: Memo?,
        content: String,
        timestampMillis: Long?,
    ) -> Unit,
    rootPath: String? = null,
    imageMap: ImmutableMap<String, Uri> = persistentMapOf(),
    quickSaveOnBackEnabled: Boolean = false,
    onDismiss: () -> Unit = {},
    onImageDirectoryMissing: (() -> Unit)? = null,
    onCameraCaptureError: ((Throwable) -> Unit)? = null,
    availableTags: ImmutableList<String> = persistentListOf(),
    dateFormat: String = "yyyy-MM-dd",
    timeFormat: String = "HH:mm",
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    isRecordingFlow: StateFlow<Boolean>? = null,
    recordingDurationFlow: StateFlow<Long>? = null,
    recordingAmplitudeFlow: StateFlow<Int>? = null,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onClearLocation: () -> Unit = {},
    attachedGeoLocation: String? = null,
    hints: ImmutableList<String> = persistentListOf(),
    inputToolbarToolOrder: ImmutableList<String> = persistentListOf(),
    onInputToolbarToolOrderChanged: (List<String>) -> Unit = {},
) {
    if (!controller.isVisible) return

    var showBackfillDialog by remember { mutableStateOf(false) }
    val isRecordingValue = isRecordingFlow?.collectAsStateWithLifecycle()?.value ?: isRecording
    val recordingDurationValue = recordingDurationFlow?.collectAsStateWithLifecycle()?.value ?: recordingDuration
    val recordingAmplitudeValue = recordingAmplitudeFlow?.collectAsStateWithLifecycle()?.value ?: recordingAmplitude
    val mediaActions =
        rememberMemoEditorMediaActions(
            controller = controller,
            imageDirectory = imageDirectory,
            onSaveImage = onSaveImage,
            onImageDirectoryMissing = onImageDirectoryMissing,
            onCameraCaptureError = onCameraCaptureError,
        )
    var previewContent by remember { mutableStateOf(controller.inputValue.text) }
    LaunchedEffect(controller.inputValue.text, rootPath, imageDirectory, imageMap) {
        previewContent =
            withContext(Dispatchers.Default) {
                buildMemoEditorPreviewContent(
                    content = controller.inputValue.text,
                    rootPath = rootPath,
                    imagePath = imageDirectory,
                    imageMap = imageMap,
                )
            }
    }
    val sheetState =
        buildMemoEditorSheetState(
            controller = controller,
            previewContent = previewContent,
            availableTags = availableTags,
            isRecording = isRecordingValue,
            recordingDuration = recordingDurationValue,
            recordingAmplitude = recordingAmplitudeValue,
            hints = hints,
            attachedGeoLocation = attachedGeoLocation,
            inputToolbarToolOrder = inputToolbarToolOrder,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
        )
    val sheetCallbacks =
        buildMemoEditorSheetCallbacks(
            controller = controller,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            onSubmit = onSubmit,
            onDismiss = onDismiss,
            mediaActions = mediaActions,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onLocationClick = onLocationClick,
            onClearLocation = onClearLocation,
            onInputToolbarToolOrderChanged = onInputToolbarToolOrderChanged,
        ) {
            if (shouldOpenMemoBackfillDialog(isEditingExistingMemo = controller.editingMemo != null)) {
                showBackfillDialog = true
            }
        }

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
}

private fun buildMemoEditorSheetState(
    controller: MemoEditorController,
    previewContent: String,
    availableTags: ImmutableList<String>,
    isRecording: Boolean,
    recordingDuration: Long,
    recordingAmplitude: Int,
    hints: ImmutableList<String>,
    attachedGeoLocation: String?,
    inputToolbarToolOrder: ImmutableList<String>,
    dateFormat: String,
    timeFormat: String,
): com.lomo.ui.component.input.InputSheetState =
    com.lomo.ui.component.input.InputSheetState(
        inputValue = controller.inputValue,
        previewContent = previewContent,
        focusRequestToken = controller.focusRequestToken,
        isExpanded = controller.mode == MemoEditorMode.Expanded,
        displayMode = controller.displayMode,
        availableTags = availableTags,
        isRecording = isRecording,
        recordingDuration = recordingDuration,
        recordingAmplitude = recordingAmplitude,
        hints = hints,
        attachedGeoLocation = attachedGeoLocation,
        inputToolbarToolOrder = inputToolbarToolOrder,
        isBackfillEnabled = controller.editingMemo == null,
        backfillBadgeText =
            controller.backfillSelection.timestampMillis?.let { timestampMillis ->
                formatMemoBackfillBadgeText(
                    timestampMillis = timestampMillis,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                )
            },
    )

private fun buildMemoEditorSheetCallbacks(
    controller: MemoEditorController,
    quickSaveOnBackEnabled: Boolean,
    onSubmit: (
        memo: Memo?,
        content: String,
        timestampMillis: Long?,
    ) -> Unit,
    onDismiss: () -> Unit,
    mediaActions: MemoEditorMediaActions,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onLocationClick: () -> Unit,
    onClearLocation: () -> Unit,
    onInputToolbarToolOrderChanged: (List<String>) -> Unit,
    onBackfillRequested: () -> Unit,
): com.lomo.ui.component.input.InputSheetCallbacks =
    com.lomo.ui.component.input.InputSheetCallbacks(
        onInputValueChange = controller::updateInputValue,
        onDismiss = {
            controller.close()
            onDismiss()
        },
        onToggleExpanded = controller::toggleExpanded,
        onCollapse = { controller.setExpanded(false) },
        onDisplayModeChange = controller::updateDisplayMode,
        onUndo = controller::undo,
        onRedo = controller::redo,
        onConsumeBackPress = controller::consumeBackPress,
        onSubmit = { content ->
            onSubmit(
                controller.editingMemo,
                content,
                controller.backfillSelection.timestampMillisForCreateSubmit(
                    isEditingExistingMemo = controller.editingMemo != null,
                ),
            )
            controller.close()
        },
        canUndo = controller.canUndo,
        canRedo = controller.canRedo,
        autoSubmitOnDismiss = quickSaveOnBackEnabled && controller.editingMemo == null,
        hasDraftPersistence = controller.editingMemo == null,
        onImageClick = mediaActions.onImageClick,
        onCameraClick = mediaActions.onCameraClick,
        onStartRecording = onStartRecording,
        onStopRecording = onStopRecording,
        onCancelRecording = onCancelRecording,
        onLocationClick = onLocationClick,
        onClearLocation = onClearLocation,
        onBackfillClick = onBackfillRequested,
        onBackfillBadgeClick = controller::cancelBackfillSelection,
        onInputToolbarToolOrderChanged = onInputToolbarToolOrderChanged,
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
