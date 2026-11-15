package com.lomo.app.widget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val WIDGET_GLANCE_SNAPSHOT_FILE_NAME = "widget-glance-snapshot.json"
internal const val WIDGET_MEMO_LIMIT = 15
internal const val WIDGET_TASK_LIMIT = 12
internal const val WIDGET_REVIEW_LIMIT = 8
internal const val WIDGET_HEATMAP_DAY_LIMIT = 126
internal const val WIDGET_TAG_LIMIT_PER_ITEM = 3
private const val WIDGET_SNAPSHOT_BYTE_LIMIT = 64 * 1024
private const val WIDGET_ITEM_ID_MAX_LENGTH = 512
private const val WIDGET_PREVIEW_MAX_LENGTH = 4096
private const val WIDGET_TAG_MAX_LENGTH = 64
private const val WIDGET_DATE_KEY_MAX_LENGTH = 64
private const val WIDGET_HEATMAP_THRESHOLD_MAX = 99
internal const val WIDGET_SNAPSHOT_SCHEMA_VERSION = 3

@Serializable
internal data class WidgetGlanceSnapshotItem(
    val id: String,
    val timestampMillis: Long,
    val previewText: String,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
)

@Serializable
internal data class WidgetGlanceTaskItem(
    val memoId: String,
    val lineIndex: Int,
    val done: Boolean,
    val text: String,
    val dateKey: String,
)

@Serializable
internal data class WidgetGlanceHeatmapDay(val epochDay: Long, val count: Int)

@Serializable
internal data class WidgetGlanceStats(
    val totalMemos: Int,
    val todayCount: Int,
    val thisWeekCount: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val activeDays: Int,
    val asOfEpochDay: Long,
    val level1Max: Int,
    val level2Max: Int,
    val level3Max: Int,
    val heatmapDays: List<WidgetGlanceHeatmapDay> = emptyList(),
)

@Serializable
internal data class WidgetGlanceReviewItem(
    val id: String,
    val timestampMillis: Long,
    val previewText: String,
    val dateKey: String,
    val tags: List<String> = emptyList(),
)

/** Generation state of the persisted snapshot; each carries a different widget meaning. */
@Serializable
internal enum class WidgetSnapshotAvailability {
    /** The writer had an admitted mount and privacy clearance; all widget payloads render. */
    READY,

    /** The workspace could not be read (opening, preparing, recovery); never render as empty. */
    UNAVAILABLE,

    /** App-lock is enabled or the security preference is unreadable; bodies are withheld. */
    REDACTED,
}

@Serializable
internal data class WidgetGlanceSnapshotFile(
    val workspaceId: String?,
    val projectionStamp: Long,
    val availability: WidgetSnapshotAvailability,
    val items: List<WidgetGlanceSnapshotItem>,
    val tasks: List<WidgetGlanceTaskItem> = emptyList(),
    val stats: WidgetGlanceStats? = null,
    val reviews: List<WidgetGlanceReviewItem> = emptyList(),
    val schemaVersion: Int = WIDGET_SNAPSHOT_SCHEMA_VERSION,
)

/**
 * Typed snapshot read: absence, ready generations and corruption stay distinguishable so a widget
 * never renders "no memos" for a file that does not exist or failed to decode.
 */
internal sealed interface WidgetGlanceSnapshot {
    /** No snapshot has ever been written in this process's filesDir. */
    data object Absent : WidgetGlanceSnapshot

    /** A complete, schema-current snapshot generation. */
    data class Ready(
        val workspaceId: String?,
        val projectionStamp: Long,
        val availability: WidgetSnapshotAvailability,
        val items: List<WidgetGlanceSnapshotItem>,
        val tasks: List<WidgetGlanceTaskItem> = emptyList(),
        val stats: WidgetGlanceStats? = null,
        val reviews: List<WidgetGlanceReviewItem> = emptyList(),
    ) : WidgetGlanceSnapshot
}

/** The file is a bounded, atomic projection. Absence is initial state; corruption is an error. */
internal class WidgetGlanceSnapshotStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(): WidgetGlanceSnapshot = withContext(ioDispatcher) { readSnapshot() }

    private fun readSnapshot(): WidgetGlanceSnapshot {
        val stream =
            try {
                FileInputStream(file)
            } catch (missing: FileNotFoundException) {
                // behavior-contract: silent-result-ok: absence is the initial state; a file that
                // exists but cannot be opened is corruption and is rethrown.
                if (file.exists()) throw missing
                return WidgetGlanceSnapshot.Absent
            }
        val text = stream.use(::readBoundedSnapshotText)
        val decoded =
            snapshotJson.decodeFromString<WidgetGlanceSnapshotFile>(text)
        require(decoded.schemaVersion == WIDGET_SNAPSHOT_SCHEMA_VERSION) {
            "Widget snapshot schema ${decoded.schemaVersion} is not $WIDGET_SNAPSHOT_SCHEMA_VERSION"
        }
        validateGeneration(
            availability = decoded.availability,
            items = decoded.items,
            tasks = decoded.tasks,
            stats = decoded.stats,
            reviews = decoded.reviews,
        )
        return WidgetGlanceSnapshot.Ready(
            workspaceId = decoded.workspaceId,
            projectionStamp = decoded.projectionStamp,
            availability = decoded.availability,
            items = decoded.items,
            tasks = decoded.tasks,
            stats = decoded.stats,
            reviews = decoded.reviews,
        )
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

    suspend fun write(
        workspaceId: String?,
        projectionStamp: Long,
        availability: WidgetSnapshotAvailability,
        items: List<WidgetGlanceSnapshotItem>,
        tasks: List<WidgetGlanceTaskItem> = emptyList(),
        stats: WidgetGlanceStats? = null,
        reviews: List<WidgetGlanceReviewItem> = emptyList(),
    ) = withContext(ioDispatcher) {
        validateGeneration(
            availability = availability,
            items = items,
            tasks = tasks,
            stats = stats,
            reviews = reviews,
        )
        val bytes =
            snapshotJson
                .encodeToString(
                    WidgetGlanceSnapshotFile(
                        workspaceId = workspaceId,
                        projectionStamp = projectionStamp,
                        availability = availability,
                        items = items,
                        tasks = tasks,
                        stats = stats,
                        reviews = reviews,
                    ),
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

    private fun validateGeneration(
        availability: WidgetSnapshotAvailability,
        items: List<WidgetGlanceSnapshotItem>,
        tasks: List<WidgetGlanceTaskItem>,
        stats: WidgetGlanceStats?,
        reviews: List<WidgetGlanceReviewItem>,
    ) {
        validateItems(items)
        validateTasks(tasks)
        if (stats != null) {
            validateStats(stats)
        }
        validateReviews(reviews)
        when (availability) {
            WidgetSnapshotAvailability.UNAVAILABLE ->
                require(items.isEmpty() && tasks.isEmpty() && stats == null && reviews.isEmpty()) {
                    "Unavailable widget snapshot must not carry memo content"
                }

            WidgetSnapshotAvailability.REDACTED ->
                require(
                    items.all { it.previewText.isEmpty() && it.tags.isEmpty() && !it.isPinned } &&
                        tasks.isEmpty() &&
                        stats == null &&
                        reviews.isEmpty(),
                ) {
                    "Redacted widget snapshot must not leak memo previews, tasks, stats, or reviews"
                }

            WidgetSnapshotAvailability.READY -> Unit
        }
    }

    private fun validateItems(items: List<WidgetGlanceSnapshotItem>) {
        require(items.size <= WIDGET_MEMO_LIMIT) { "Widget snapshot exceeds its item budget" }
        require(
            items.all { item ->
                item.id.isNotBlank() &&
                    item.id.length <= WIDGET_ITEM_ID_MAX_LENGTH &&
                    item.previewText.length <= WIDGET_PREVIEW_MAX_LENGTH &&
                    item.tags.size <= WIDGET_TAG_LIMIT_PER_ITEM &&
                    item.tags.all { tag -> tag.isNotBlank() && tag.length <= WIDGET_TAG_MAX_LENGTH }
            },
        ) {
            "Widget snapshot has invalid item identity, tag, or oversized preview"
        }
    }

    private fun validateTasks(tasks: List<WidgetGlanceTaskItem>) {
        require(tasks.size <= WIDGET_TASK_LIMIT) { "Widget snapshot exceeds its task budget" }
        require(
            tasks.all { task ->
                task.memoId.isNotBlank() &&
                    task.memoId.length <= WIDGET_ITEM_ID_MAX_LENGTH &&
                    task.lineIndex >= 0 &&
                    task.text.length <= WIDGET_PREVIEW_MAX_LENGTH &&
                    task.dateKey.length <= WIDGET_DATE_KEY_MAX_LENGTH
            },
        ) {
            "Widget snapshot has invalid task metadata or oversized body"
        }
    }

    private fun validateStats(stats: WidgetGlanceStats) {
        require(
            stats.totalMemos >= 0 &&
                stats.todayCount >= 0 &&
                stats.thisWeekCount >= 0 &&
                stats.currentStreak >= 0 &&
                stats.longestStreak >= 0 &&
                stats.activeDays >= 0 &&
                stats.level1Max in 1..WIDGET_HEATMAP_THRESHOLD_MAX &&
                stats.level2Max in (stats.level1Max + 1)..WIDGET_HEATMAP_THRESHOLD_MAX &&
                stats.level3Max in (stats.level2Max + 1)..WIDGET_HEATMAP_THRESHOLD_MAX &&
                stats.heatmapDays.size <= WIDGET_HEATMAP_DAY_LIMIT &&
                stats.heatmapDays.all { it.count >= 0 },
        ) {
            "Widget snapshot has invalid statistics or heatmap bounds"
        }
    }

    private fun validateReviews(reviews: List<WidgetGlanceReviewItem>) {
        require(reviews.size <= WIDGET_REVIEW_LIMIT) { "Widget snapshot exceeds its review budget" }
        require(
            reviews.all { review ->
                review.id.isNotBlank() &&
                    review.id.length <= WIDGET_ITEM_ID_MAX_LENGTH &&
                    review.previewText.length <= WIDGET_PREVIEW_MAX_LENGTH &&
                    review.dateKey.length <= WIDGET_DATE_KEY_MAX_LENGTH &&
                    review.tags.size <= WIDGET_TAG_LIMIT_PER_ITEM &&
                    review.tags.all { tag -> tag.isNotBlank() && tag.length <= WIDGET_TAG_MAX_LENGTH }
            },
        ) {
            "Widget snapshot has invalid review item"
        }
    }
}

private val snapshotJson = Json { encodeDefaults = true }
