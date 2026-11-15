package com.lomo.app.widget

import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val WIDGET_REVIEW_CURSOR_FILE_NAME = "widget-review-cursor.json"

internal data class WidgetReviewSelection(val item: WidgetGlanceReviewItem?, val index: Int, val total: Int)

internal fun selectWidgetReviewItem(reviews: List<WidgetGlanceReviewItem>, cursorIndex: Int): WidgetReviewSelection {
    if (reviews.isEmpty()) {
        return WidgetReviewSelection(item = null, index = 0, total = 0)
    }
    val normalizedIndex = cursorIndex.mod(reviews.size)
    return WidgetReviewSelection(
        item = reviews[normalizedIndex],
        index = normalizedIndex,
        total = reviews.size,
    )
}

internal class WidgetReviewCursorStore(
    private val file: File,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    private val fileMutex = Mutex()

    suspend fun readCursor(widgetKey: String): Int = withContext(dispatcherProvider.io) {
        fileMutex.withLock {
            readCursorLocked(widgetKey)
        }
    }

    suspend fun advanceCursor(widgetKey: String, totalCount: Int): Int {
        if (totalCount <= 0) {
            return 0
        }
        return withContext(dispatcherProvider.io) {
            fileMutex.withLock {
                val map = readCursorMapLocked().toMutableMap()
                val current = (map[widgetKey] ?: 0).coerceAtLeast(0)
                val next = (current + 1).mod(totalCount)
                map[widgetKey] = next
                writeCursorMap(map)
                next
            }
        }
    }

    private fun readCursorLocked(widgetKey: String): Int {
        val stored = readCursorMapLocked()[widgetKey] ?: return 0
        return stored.coerceAtLeast(0)
    }

    private fun readCursorMapLocked(): Map<String, Int> {
        if (!file.isFile) {
            return emptyMap()
        }
        return when (val decoded = decodeCursorFile()) {
            is DecodeCursorResult.Valid -> decoded.cursors

            is DecodeCursorResult.Corrupt -> {
                Timber.w(decoded.cause, "Discarding corrupt widget review cursor store")
                emptyMap()
            }
        }
    }

    private fun decodeCursorFile(): DecodeCursorResult = try {
        val rawText = FileInputStream(file).use(::readBoundedCursorText)
        DecodeCursorResult.Valid(cursorJson.decodeFromString<Map<String, Int>>(rawText))
    } catch (failure: SerializationException) {
        DecodeCursorResult.Corrupt(failure)
    } catch (failure: IOException) {
        DecodeCursorResult.Corrupt(failure)
    } catch (failure: IllegalArgumentException) {
        DecodeCursorResult.Corrupt(failure)
    }

    private fun readBoundedCursorText(input: FileInputStream): String {
        val bytes = ByteArray(WIDGET_CURSOR_BYTE_LIMIT + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count <= 0) break
            size += count
        }
        if (size > WIDGET_CURSOR_BYTE_LIMIT) {
            throw IOException("Widget review cursor store exceeded byte budget")
        }
        return bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    }

    private suspend fun writeCursorMap(cursors: Map<String, Int>) {
        withContext(dispatcherProvider.io) {
            val directory = requireNotNull(file.parentFile) { "Widget cursor store requires parent directory" }
            Files.createDirectories(directory.toPath())
            val bounded =
                if (cursors.size <= WIDGET_CURSOR_KEY_LIMIT) {
                    cursors
                } else {
                    cursors.entries.take(WIDGET_CURSOR_KEY_LIMIT).associate { (key, value) -> key to value }
                }
            val bytes = cursorJson.encodeToString(bounded).toByteArray()
            val temporary = File.createTempFile("widget-review-cursor-", ".tmp", directory)
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
        }
    }

    private sealed interface DecodeCursorResult {
        data class Valid(val cursors: Map<String, Int>) : DecodeCursorResult

        data class Corrupt(val cause: Exception) : DecodeCursorResult
    }
}

private val cursorJson = Json { encodeDefaults = true }
private const val WIDGET_CURSOR_BYTE_LIMIT = 16 * 1024
private const val WIDGET_CURSOR_KEY_LIMIT = 64
