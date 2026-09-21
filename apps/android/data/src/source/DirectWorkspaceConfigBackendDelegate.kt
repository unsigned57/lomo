package com.lomo.data.source

import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class DirectWorkspaceConfigBackendDelegate(
    private val rootDir: File,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : WorkspaceConfigBackend {
    private val workspaceConfigDispatcher: CoroutineDispatcher = dispatcherProvider.io

    override suspend fun createDirectory(name: String): String =
        directCreateDirectory(rootDir, name, workspaceConfigDispatcher)
}

private suspend fun directCreateDirectory(
    rootDir: File,
    name: String,
    dispatcher: CoroutineDispatcher,
): String =
    withContext(dispatcher) {
        directEnsureRootExists(rootDir)
        val dir = File(rootDir, name)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create directory $name")
        }
        dir.absolutePath
    }
