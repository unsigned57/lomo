package com.lomo.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StorageFilenameFormatsTest {
    @Test
    fun `normalize returns default when pattern is null or unsupported`() {
        assertEquals(StorageFilenameFormats.DEFAULT_PATTERN, StorageFilenameFormats.normalize(null))
        assertEquals(StorageFilenameFormats.DEFAULT_PATTERN, StorageFilenameFormats.normalize("yyyy/MM/dd"))
    }

    @Test
    fun `normalize keeps supported pattern`() {
        assertEquals("yyyy-MM-dd", StorageFilenameFormats.normalize("yyyy-MM-dd"))
    }

    @Test
    fun `parseOrNull parses all supported formats`() {
        val expected = LocalDate.of(2024, 2, 29)
        val samples =
            listOf(
                "2024_02_29",
                "2024-02-29",
                "2024.02.29",
                "20240229",
                "02-29-2024",
            )

        samples.forEach { raw ->
            assertEquals(expected, StorageFilenameFormats.parseOrNull(raw))
        }
    }

    @Test
    fun `parseOrNull rejects unsupported and invalid dates`() {
        assertNull(StorageFilenameFormats.parseOrNull("2024/02/29"))
        assertNull(StorageFilenameFormats.parseOrNull("2023_02_29"))
        assertNull(StorageFilenameFormats.parseOrNull("02-30-2024"))
    }
}
