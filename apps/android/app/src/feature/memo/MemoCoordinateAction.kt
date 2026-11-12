package com.lomo.app.feature.memo

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lomo.app.R
import kotlinx.coroutines.launch

@Composable
internal fun rememberMemoCoordinateInsertAction(controller: MemoEditorController): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dispatchers = org.koin.compose.koinInject<com.lomo.domain.usecase.DispatcherProvider>()
    var isAcquiring by remember { mutableStateOf(false) }

    val acquire: () -> Unit = {
        if (!isAcquiring) {
            isAcquiring = true
            scope.launch {
                try {
                    when (val result = readCurrentMemoLocation(context, dispatchers.main)) {
                        is MemoLocationResult.Located -> {
                            controller.updateInputValue(
                                buildMemoCoordinateInsertionValue(controller.inputValue, result.coordinates),
                            )
                        }

                        is MemoLocationResult.Failed -> {
                            Toast.makeText(context, result.reason.messageResource(), Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    isAcquiring = false
                }
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (isCoordinatePermissionGranted(results, hasMemoLocationPermission(context))) {
            acquire()
        } else {
            Toast.makeText(
                context,
                R.string.memo_coordinates_permission_denied,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    return remember(controller, context, scope, isAcquiring) {
        {
            if (hasMemoLocationPermission(context)) {
                acquire()
            } else {
                permissions.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }
}

internal fun MemoLocationFailure.messageResource(): Int = when (this) {
    MemoLocationFailure.PermissionDenied -> R.string.memo_coordinates_permission_denied
    MemoLocationFailure.Unavailable -> R.string.memo_coordinates_unavailable
    MemoLocationFailure.TimedOut -> R.string.memo_coordinates_timeout
    MemoLocationFailure.InvalidFix -> R.string.memo_coordinates_invalid_fix
}
