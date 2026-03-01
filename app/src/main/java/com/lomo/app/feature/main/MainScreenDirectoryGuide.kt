package com.lomo.app.feature.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lomo.app.R

enum class DirectorySetupType(
    val labelResId: Int,
    val subfolder: String,
) {
    Image(R.string.settings_image_storage, "images"),
    Voice(R.string.settings_voice_storage, "voice"),
}

@Stable
class MainDirectoryGuideController(
    initialType: DirectorySetupType? = null,
) {
    var setupType: DirectorySetupType? by mutableStateOf(initialType)
        private set

    fun request(type: DirectorySetupType) {
        setupType = type
    }

    fun requestImage() = request(DirectorySetupType.Image)

    fun requestVoice() = request(DirectorySetupType.Voice)

    fun clear() {
        setupType = null
    }
}

@Composable
fun rememberMainDirectoryGuideController(): MainDirectoryGuideController = remember { MainDirectoryGuideController() }

data class MainDirectoryGuideActions(
    val onConfirmCreate: (DirectorySetupType) -> Unit,
    val onGoToSettings: () -> Unit,
    val onBeforeGoToSettings: () -> Unit = {},
)

@Composable
fun MainDirectoryGuideHost(
    controller: MainDirectoryGuideController,
    actions: MainDirectoryGuideActions,
) {
    DirectorySetupDialog(
        type = controller.setupType,
        onDismiss = controller::clear,
        onConfirmCreate = { type ->
            actions.onConfirmCreate(type)
            controller.clear()
        },
        onGoToSettings = {
            controller.clear()
            actions.onBeforeGoToSettings()
            actions.onGoToSettings()
        },
    )
}

@Composable
private fun DirectorySetupDialog(
    type: DirectorySetupType?,
    onDismiss: () -> Unit,
    onConfirmCreate: (DirectorySetupType) -> Unit,
    onGoToSettings: () -> Unit,
) {
    val currentType = type ?: return
    val typeLabel = stringResource(currentType.labelResId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_directory_title, typeLabel)) },
        text = { Text(stringResource(R.string.setup_directory_message, typeLabel, currentType.subfolder)) },
        confirmButton = {
            TextButton(
                onClick = { onConfirmCreate(currentType) },
            ) {
                Text(stringResource(R.string.action_auto_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onGoToSettings) {
                Text(stringResource(R.string.action_go_to_settings))
            }
        },
    )
}
