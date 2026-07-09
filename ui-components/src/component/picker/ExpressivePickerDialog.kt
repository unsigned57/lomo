package com.lomo.ui.component.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Shared Material 3 Expressive dialog skeleton used by every Lomo date/time picker
 * (reminder, backfill, filter). Centralises shape, surface, tonal elevation, header
 * typography, and footer button arrangement so the callers stay visually aligned.
 *
 * The title and footer buttons are pinned; only [content] scrolls, inside a height cap of
 * [MAX_DIALOG_HEIGHT_FRACTION] of the screen. This guarantees the confirm/cancel row stays on screen
 * no matter how tall a picker surface is, so a tall composite picker can never push its footer off the
 * bottom of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressivePickerDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    neutralLabel: String? = null,
    onNeutral: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val maxDialogHeight = with(LocalDensity.current) {
        (windowHeightPx * MAX_DIALOG_HEIGHT_FRACTION).toDp()
    }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier.width(EXPRESSIVE_PICKER_DIALOG_WIDTH),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .padding(vertical = 24.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (neutralLabel != null && onNeutral != null) {
                        TextButton(onClick = onNeutral) {
                            Text(text = neutralLabel)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = dismissLabel ?: defaultCancelLabel())
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        enabled = confirmEnabled,
                        onClick = onConfirm,
                    ) {
                        Text(text = confirmLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun defaultCancelLabel(): String =
    androidx.compose.ui.res.stringResource(android.R.string.cancel)

private val EXPRESSIVE_PICKER_DIALOG_WIDTH = 360.dp
private const val MAX_DIALOG_HEIGHT_FRACTION = 0.9f
