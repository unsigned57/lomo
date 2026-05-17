package com.lomo.data.testing

import java.io.File
import java.util.UUID

class KotestTemporaryFolder(
    private val rootDirectory: File = createRootDirectory(),
) {
    val root: File
        get() = rootDirectory

    fun newFolder(
        path: String,
        vararg additionalPathSegments: String,
    ): File =
        pathSegments(path, *additionalPathSegments)
            .fold(rootDirectory) { parent, child -> parent.resolve(child) }
            .apply { mkdirs() }

    fun newFile(name: String): File =
        rootDirectory.resolve(name).apply {
            parentFile?.mkdirs()
            createNewFile()
        }

    fun cleanup() {
        rootDirectory.deleteRecursively()
    }

    private companion object {
        fun pathSegments(
            path: String,
            vararg additionalPathSegments: String,
        ): List<String> = listOf(path, *additionalPathSegments)

        fun createRootDirectory(): File {
            val baseDirectory = File("build/kotest-temp/data").apply { mkdirs() }
            return baseDirectory.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
        }
    }
}
