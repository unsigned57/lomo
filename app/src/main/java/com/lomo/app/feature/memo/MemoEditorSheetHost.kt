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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.R
import com.lomo.app.util.CameraCaptureUtils
import com.lomo.domain.model.Memo
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
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    hints: List<String> = emptyList(),
) {
    if (!controller.isVisible) return

    val context = LocalContext.current
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun clearPendingCapture() {
        runCatching { pendingCameraFile?.delete() }
        pendingCameraFile = null
        pendingCameraUri = null
    }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                onSaveImage(
                    it,
                    controller::appendImageMarkdown,
                    null,
                )
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

    com.lomo.ui.component.input.InputSheet(
        state =
            com.lomo.ui.component.input.InputSheetState(
                inputValue = controller.inputValue,
                availableTags = availableTags,
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                recordingAmplitude = recordingAmplitude,
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
                onImageClick = {
                    if (imageDirectory == null) {
                        if (onImageDirectoryMissing != null) {
                            onImageDirectoryMissing()
                        } else {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.settings_not_set),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    } else {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                },
                onCameraClick = {
                    if (imageDirectory == null) {
                        if (onImageDirectoryMissing != null) {
                            onImageDirectoryMissing()
                        } else {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.settings_not_set),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    } else {
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
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onCancelRecording = onCancelRecording,
            ),
    )
}
