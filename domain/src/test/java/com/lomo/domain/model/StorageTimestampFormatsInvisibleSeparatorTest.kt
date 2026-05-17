package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Test Contract:
 * - Unit under test: StorageTimestampFormats
 * - Behavior focus: memo header parsing when invisible separators appear before the leading dash or between the dash and timestamp token.
 * - Observable outcomes: parsed header presence, parsed time token, and stripped content remainder.
 * - Red phase: Fails before the fix when a UTF-8 BOM or zero-width space prevents `- HH:mm:ss` memo headers from being recognized.
 * - Excludes: file loading, markdown rendering, and memo repository orchestration.
 */
class StorageTimestampFormatsInvisibleSeparatorTest : DomainFunSpec() {
    init {
        test("parseMemoHeaderLine ignores utf8 bom before storage header") {
            val parsed = StorageTimestampFormats.parseMemoHeaderLine("﻿- 17:56:16  正文")

            parsed shouldNotBe null
            parsed?.timePart shouldBe "17:56:16"
            parsed?.contentPart shouldBe "正文"
        }
    }
    init {
        test("parseMemoHeaderLine ignores zero width space between dash and timestamp") {
            val parsed = StorageTimestampFormats.parseMemoHeaderLine("- ​17:56:16  正文")

            parsed shouldNotBe null
            parsed?.timePart shouldBe "17:56:16"
            parsed?.contentPart shouldBe "正文"
        }
    }
}
