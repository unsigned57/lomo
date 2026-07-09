package com.lomo.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object CameraCaptureUtils {
    private const val CAMERA_CACHE_DIR = "shared_memos"

    fun createTempCaptureUri(context: Context): Pair<File, Uri> {
        val directory = File(context.cacheDir, CAMERA_CACHE_DIR).apply { mkdirs() }
        val file = File.createTempFile("camera_capture_", ".jpg", directory)
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        return file to uri
    }
}
