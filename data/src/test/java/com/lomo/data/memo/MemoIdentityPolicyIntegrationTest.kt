package com.lomo.data.memo

import com.lomo.data.parser.MarkdownParser
import com.lomo.data.repository.MemoSavePlanFactory
import com.lomo.data.util.MemoTextProcessor
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoIdentityPolicyIntegrationTest {
    private val textProcessor = MemoTextProcessor()
    private val memoIdentityPolicy = MemoIdentityPolicy()
    private val parser = MarkdownParser(textProcessor, memoIdentityPolicy)
    private val factory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy)

    @Test
    fun `save plan and parser share same identity for first occurrence`() {
        val timestamp = dateTimeMillis(2026, 2, 1, 10, 0, 0)
        val savePlan =
            factory.create(
                content = "Buy milk",
                timestamp = timestamp,
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm",
                existingFileContent = "",
                precomputedSameTimestampCount = 0,
                precomputedCollisionCount = 0,
            )

        val parsed =
            parser.parseContent(
                content = savePlan.rawContent,
                filename = savePlan.dateKey,
                fallbackTimestampMillis = timestamp,
            ).single()

        assertEquals(savePlan.memo.id, parsed.id)
        assertEquals(savePlan.memo.timestamp, parsed.timestamp)
    }

    @Test
    fun `save plan and parser share same identity for collision occurrence`() {
        val timestamp = dateTimeMillis(2026, 2, 1, 10, 0, 0)
        val fileContent =
            """
            - 10:00 Duplicate
            - 10:00 Duplicate
            """.trimIndent()
        val parsedSecond =
            parser.parseContent(
                content = fileContent,
                filename = "2026_02_01",
                fallbackTimestampMillis = timestamp,
            )[1]

        val savePlanSecond =
            factory.create(
                content = "Duplicate",
                timestamp = timestamp,
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm",
                existingFileContent = fileContent,
                precomputedSameTimestampCount = 1,
                precomputedCollisionCount = 1,
            )

        assertEquals(parsedSecond.id, savePlanSecond.memo.id)
        assertEquals(parsedSecond.timestamp, savePlanSecond.memo.timestamp)
    }

    private fun dateTimeMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long =
        LocalDateTime
            .of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
