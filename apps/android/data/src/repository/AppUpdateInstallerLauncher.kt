package com.lomo.data.repository

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import com.lomo.data.resources.DataAndroidResources
import java.io.File

internal interface AppUpdateInstallerLauncher {
    fun launch(apkFile: File)
}

internal class FileProviderAppUpdateInstallerLauncher(
    private val context: android.content.Context,
    private val resources: DataAndroidResources,
) : AppUpdateInstallerLauncher {
    override fun launch(apkFile: File) {
        val apkUri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException(resources.getString(resources.appUpdateNoInstaller), error)
        }
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
