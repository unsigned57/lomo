package com.lomo.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

internal fun directStreamFile(
    rootDir: File,
    filename: String,
): Flow<String> =
    flow {
        val file = File(rootDir, filename)
        ensureWithinDirectory(rootDir, file)
        if (file.exists()) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line -> emit(line) }
            }
        }
    }.flowOn(Dispatchers.IO)

internal fun directStreamTrashFile(
    rootDir: File,
    filename: String,
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
    }.flowOn(Dispatchers.IO)
