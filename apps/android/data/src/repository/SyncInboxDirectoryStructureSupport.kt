package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun ensureInboxDirectoryStructure(
    context: Context,
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    if (isContentUriRoot(inboxRoot)) {
        ensureSafInboxDirectoryStructure(context, inboxRoot, dispatcherProvider)
    } else {
        ensureDirectInboxDirectoryStructure(inboxRoot, dispatcherProvider)
    }
}

private suspend fun ensureDirectInboxDirectoryStructure(
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider,
) {
    withContext(dispatcherProvider.io) {
        requiredInboxDirectories().forEach { name ->
            val directory = File(inboxRoot, name)
            when {
                directory.isDirectory -> Unit
                directory.exists() -> error("Sync inbox path exists but is not a directory: ${directory.absolutePath}")
                !directory.mkdirs() -> error("Failed to create sync inbox directory ${directory.absolutePath}")
            }
        }
    }
}

private suspend fun ensureSafInboxDirectoryStructure(
    context: Context,
    inboxRoot: String,
    dispatcherProvider: DispatcherProvider,
) {
    withContext(dispatcherProvider.io) {
        val root = DocumentFile.fromTreeUri(context, inboxRoot.toUri()) ?: error("Failed to resolve sync inbox root")
        requiredInboxDirectories().forEach { name ->
            val child = root.findFile(name)
            when {
                child == null -> root.createDirectory(name) ?: error("Failed to create sync inbox directory $name")
                child.isDirectory -> Unit
                else -> error("Sync inbox path exists but is not a directory: $name")
            }
        }
    }
}

private fun requiredInboxDirectories(): List<String> =
    listOf(
        INBOX_MEMO_DIRECTORY,
        "images",
        "voice",
    )
