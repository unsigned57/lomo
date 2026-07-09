package com.lomo.app.feature.statistics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.content.FileProvider
import com.lomo.app.R
import com.lomo.app.util.requireSuccessfulPngEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

private const val STATS_SCREENSHOT_PNG_QUALITY = 100

@Composable
internal fun StatisticsShareEffects(
    context: Context,
    shareImageEvent: StatisticsShareImageEvent?,
    shareErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onShareEventConsumed: (Long) -> Unit,
    onShareFailed: (Throwable) -> Unit,
    onShareErrorConsumed: () -> Unit,
) {
    LaunchedEffect(shareImageEvent) {
        val event = shareImageEvent ?: return@LaunchedEffect
        runCatching {
            shareStatisticsImageFile(context = context, filePath = event.filePath)
        }.onFailure(onShareFailed)
        onShareEventConsumed(event.id)
    }
    LaunchedEffect(shareErrorMessage) {
        shareErrorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onShareErrorConsumed()
        }
    }
}

interface StatisticsPngSource {
    suspend fun writeTo(output: OutputStream)

    fun close()
}

internal suspend fun captureStatisticsPngSource(graphicsLayer: GraphicsLayer): StatisticsPngSource {
    val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
    return BitmapStatisticsPngSource(bitmap)
}

private class BitmapStatisticsPngSource(
    private val bitmap: android.graphics.Bitmap,
) : StatisticsPngSource {
    override suspend fun writeTo(output: OutputStream) {
        withContext(Dispatchers.Default) {
            requireSuccessfulPngEncode(
                bitmap.compress(Bitmap.CompressFormat.PNG, STATS_SCREENSHOT_PNG_QUALITY, output),
            )
        }
    }

    override fun close() {
        bitmap.recycle()
    }
}

private fun shareStatisticsImageFile(
    context: Context,
    filePath: String,
) {
    val imageUri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(filePath),
        )
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "statistics_image", imageUri)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(
        Intent.createChooser(
            sendIntent,
            context.getString(R.string.stats_share_sheet_title),
        ),
    )
}
