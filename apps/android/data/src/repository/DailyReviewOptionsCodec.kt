package com.lomo.data.repository

import com.lomo.domain.model.DailyReviewOptions
import com.lomo.domain.model.DailyReviewSort
import com.lomo.domain.model.MemoListFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
private data class ReviewOptionsRecord(
    val sort: String,
    val start: String?,
    val end: String?,
    val todo: Boolean?,
    val attachment: Boolean?,
    val url: Boolean?,
)

internal fun encodeReviewOptions(options: DailyReviewOptions): String = Json.encodeToString(
    ReviewOptionsRecord(
        options.sort.name,
        options.filter.startDate?.toString(),
        options.filter.endDate?.toString(),
        options.filter.hasTodo,
        options.filter.hasAttachment,
        options.filter.hasUrl,
    ),
)

internal fun decodeReviewOptions(value: String?): DailyReviewOptions {
    // Sessions written before review options existed use the documented recent-month default.
    if (value == null) return DailyReviewOptions()
    val record = Json.decodeFromString<ReviewOptionsRecord>(value)
    return DailyReviewOptions(
        sort = DailyReviewSort.valueOf(record.sort),
        filter = MemoListFilter(
            startDate = record.start?.let(LocalDate::parse),
            endDate = record.end?.let(LocalDate::parse),
            hasTodo = record.todo,
            hasAttachment = record.attachment,
            hasUrl = record.url,
        ),
    )
}
