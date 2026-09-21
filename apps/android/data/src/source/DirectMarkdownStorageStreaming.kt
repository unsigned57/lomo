package com.lomo.data.source

import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

internal fun directStreamFile(
    rootDir: File,
    filename: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): Flow<String> =
    flow {
        val file = File(rootDir, filename)
        ensureWithinDirectory(rootDir, file)
        if (file.exists()) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line -> emit(line) }
            }
        }
    }.flowOn(dispatcherProvider.io)

internal fun directStreamTrashFile(
    rootDir: File,
    filename: String,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): Flow<String> =
    flow {
        val trashDir = directTrashDir(rootDir)
        val file = File(trashDir, filename)
        ensureWithinDirectory(trashDir, file)
        if (file.exists()) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line -> emit(line) }
            }
        }
    }.flowOn(dispatcherProvider.io)
