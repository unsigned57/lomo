package com.lomo.data.source

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/*
 * Test Contract:
 * - Unit under test: ImageMagicByteValidator.
 * - Behavior focus: imported image payloads must be validated by magic bytes rather than trusting MIME metadata.
 * - Observable outcomes: known image signatures are accepted, and arbitrary binary payloads are rejected with IOException.
 * - Red phase: Not applicable - this file adds regression coverage for behavior already present in production.
 * - Excludes: filename extension selection, backend storage writes, and ContentProvider permission handling.
 */
class ImageMagicByteValidatorTest {
    @Test
    fun `looksLikeSupportedImage accepts known image signatures`() {
        assertTrue(ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(
            ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        assertTrue(ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertTrue(
            ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50),
            ),
        )
        assertTrue(ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0x42, 0x4D)))
        assertTrue(
            ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63),
            ),
        )
    }

    @Test
    fun `requireSupportedImage rejects arbitrary payload even when stream opens successfully`() {
        val contentResolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("not-an-image".toByteArray())

        assertThrows(IOException::class.java) {
            ImageMagicByteValidator.requireSupportedImage(contentResolver, uri)
        }
    }
}
