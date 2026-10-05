package com.lomo.app.feature.memo

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.util.CameraCaptureUtils
import com.lomo.domain.model.DraftId
import java.io.File

internal data class MemoEditorMediaActions(
    val onImageClick: () -> Unit,
    val onCameraClick: () -> Unit,
)

@Composable
internal fun rememberMemoEditorMediaActions(
    controller: MemoEditorController,
    imageDirectory: String?,
    ownerDraftId: DraftId,
    onSaveImage: (
        uri: Uri,
        draftId: DraftId,
        onResult: (String) -> Unit,
        onError: (() -> Unit)?,
    ) -> Unit,
    onImageDirectoryMissing: (() -> Unit)?,
    onCameraCaptureError: ((Throwable) -> Unit)?,
): MemoEditorMediaActions {
    /** The draft that must own newly staged media: an open edit session's durable draft wins. */
    fun effectiveDraftId(): DraftId = controller.editingSession?.draftId ?: ownerDraftId
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
                onSaveImage(selectedUri, effectiveDraftId(), controller::appendImageMarkdown, null)
            }
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            val file = pendingCameraFile
            val uri = pendingCameraUri
            if (isSuccess && uri != null) {
                onSaveImage(
                    uri,
                    effectiveDraftId(),
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
