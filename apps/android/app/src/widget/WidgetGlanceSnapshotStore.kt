package com.lomo.app.widget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val WIDGET_GLANCE_SNAPSHOT_FILE_NAME = "widget-glance-snapshot.json"
internal const val WIDGET_MEMO_LIMIT = 3
private const val WIDGET_SNAPSHOT_BYTE_LIMIT = 64 * 1024
private const val WIDGET_ITEM_ID_MAX_LENGTH = 512
private const val WIDGET_PREVIEW_MAX_LENGTH = 4096

@Serializable
internal data class WidgetGlanceSnapshotItem(
    val id: String,
    val timestampMillis: Long,
    val previewText: String,
)

@Serializable
private data class WidgetGlanceSnapshotFile(val items: List<WidgetGlanceSnapshotItem>)

/** The file is a bounded, atomic projection. Absence is initial state; corruption is an error. */
internal class WidgetGlanceSnapshotStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(): List<WidgetGlanceSnapshotItem> = withContext(ioDispatcher) { readSnapshotItems() }

    private fun readSnapshotItems(): List<WidgetGlanceSnapshotItem> {
        val stream =
            try {
                FileInputStream(file)
            } catch (missing: FileNotFoundException) {
                // behavior-contract: silent-result-ok: absence is the initial state; a file that
                // exists but cannot be opened is corruption and is rethrown.
                if (file.exists()) throw missing
                return emptyList()
            }
        val text = stream.use(::readBoundedSnapshotText)
        return snapshotJson.decodeFromString(WidgetGlanceSnapshotFile.serializer(), text).items.also(::validateItems)
    }

    /**
     * Reads the snapshot through a bounded buffer. The caller owns the stream lifetime; oversize
     * snapshots are rejected instead of being loaded into memory.
     */
    private fun readBoundedSnapshotText(input: FileInputStream): String {
        if (input.channel.size() > WIDGET_SNAPSHOT_BYTE_LIMIT) {
            throw IOException("Widget snapshot exceeds its byte budget")
        }
        val bytes = ByteArray(WIDGET_SNAPSHOT_BYTE_LIMIT + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        if (size > WIDGET_SNAPSHOT_BYTE_LIMIT) {
            throw IOException("Widget snapshot grew past its byte budget")
        }
        return bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    }

    suspend fun write(items: List<WidgetGlanceSnapshotItem>) = withContext(ioDispatcher) {
        validateItems(items)
        val bytes =
            snapshotJson
                .encodeToString(
                    WidgetGlanceSnapshotFile.serializer(),
                    WidgetGlanceSnapshotFile(items),
                ).toByteArray()
        require(bytes.size <= WIDGET_SNAPSHOT_BYTE_LIMIT) {
            "Widget snapshot exceeds its byte budget"
        }
        val directory =
            requireNotNull(file.parentFile) { "Widget snapshot must have a private parent directory" }
        Files.createDirectories(directory.toPath())
        val temporary = File.createTempFile("widget-snapshot-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        Unit
    }

    private fun validateItems(items: List<WidgetGlanceSnapshotItem>) {
        require(items.size <= WIDGET_MEMO_LIMIT) { "Widget snapshot exceeds its item budget" }
        require(
            items.all {
                it.id.isNotBlank() &&
                    it.id.length <= WIDGET_ITEM_ID_MAX_LENGTH &&
                    it.previewText.length <= WIDGET_PREVIEW_MAX_LENGTH
            },
        ) {
            "Widget snapshot has invalid identity or oversized preview"
        }
    }
}

private val snapshotJson = Json { encodeDefaults = true }
