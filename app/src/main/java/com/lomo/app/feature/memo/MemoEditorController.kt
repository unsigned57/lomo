package com.lomo.app.feature.memo

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.util.CameraCaptureUtils
import com.lomo.domain.model.Memo
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@Stable
class MemoEditorController
    internal constructor() {
        var isVisible by mutableStateOf(false)
            private set

        var editingMemo: Memo? by mutableStateOf(null)
            private set

        var inputValue by mutableStateOf(TextFieldValue(""))
            private set

        fun openForCreate(initialText: String = "") {
            editingMemo = null
            inputValue = TextFieldValue(initialText, TextRange(initialText.length))
            isVisible = true
        }

        fun openForEdit(memo: Memo) {
            editingMemo = memo
            inputValue = TextFieldValue(memo.content, TextRange(memo.content.length))
            isVisible = true
        }

        fun appendMarkdownBlock(markdown: String) {
            val current = inputValue.text
            val newText = if (current.isEmpty()) markdown else "$current\n$markdown"
            inputValue = TextFieldValue(newText, TextRange(newText.length))
        }

        fun appendImageMarkdown(path: String) {
            val markdown = "![image]($path)"
            appendMarkdownBlock(markdown)
        }

        fun updateInputValue(value: TextFieldValue) {
            inputValue = value
        }

        fun ensureVisible() {
            isVisible = true
        }

        fun close() {
            isVisible = false
            editingMemo = null
            inputValue = TextFieldValue("")
        }
    }

@Composable
fun rememberMemoEditorController(): MemoEditorController = remember { MemoEditorController() }

@Composable
fun MemoEditorSheetHost(
    controller: MemoEditorController,
    imageDirectory: String?,
    quickSaveOnBackEnabled: Boolean = false,
    onSaveImage: (
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onSubmit: (
        memo: Memo?,
        content: String,
    ) -> Unit,
    onDismiss: () -> Unit = {},
    onImageDirectoryMissing: (() -> Unit)? = null,
    onCameraCaptureError: ((Throwable) -> Unit)? = null,
    availableTags: List<String> = emptyList(),
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    recordingAmplitude: Int = 0,
    isRecordingFlow: StateFlow<Boolean>? = null,
    recordingDurationFlow: StateFlow<Long>? = null,
    recordingAmplitudeFlow: StateFlow<Int>? = null,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    hints: List<String> = emptyList(),
) {
    if (!controller.isVisible) return

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

    com.lomo.ui.component.input.InputSheet(
        state =
            com.lomo.ui.component.input.InputSheetState(
                inputValue = controller.inputValue,
                availableTags = availableTags,
                isRecording = isRecordingValue,
                recordingDuration = recordingDurationValue,
                recordingAmplitude = recordingAmplitudeValue,
                hints = hints,
            ),
        callbacks =
            com.lomo.ui.component.input.InputSheetCallbacks(
                onInputValueChange = controller::updateInputValue,
                onDismiss = {
                    controller.close()
                    onDismiss()
                },
                onSubmit = { content ->
                    onSubmit(controller.editingMemo, content)
                    controller.close()
                },
                autoSubmitOnDismiss = quickSaveOnBackEnabled && controller.editingMemo == null,
                hasDraftPersistence = controller.editingMemo == null,
                onImageClick = mediaActions.onImageClick,
                onCameraClick = mediaActions.onCameraClick,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onCancelRecording = onCancelRecording,
            ),
    )
}

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
