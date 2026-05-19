package com.lomo.data.source

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: ImageMagicByteValidator.
 * - Behavior focus: imported image payloads must be validated by magic bytes rather than trusting MIME metadata.
 * - Observable outcomes: known image signatures are accepted, and arbitrary binary payloads are rejected with IOException.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: filename extension selection, backend storage writes, and ContentProvider permission handling.
 */
class ImageMagicByteValidatorTest : DataFunSpec() {
    init {
        test("looksLikeSupportedImage accepts known image signatures") { `looksLikeSupportedImage accepts known image signatures`() }

        test("requireSupportedImage rejects arbitrary payload even when stream opens successfully") { `requireSupportedImage rejects arbitrary payload even when stream opens successfully`() }
    }


    private fun `looksLikeSupportedImage accepts known image signatures`() {
        (ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))).shouldBeTrue()
        (ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            )).shouldBeTrue()
        (ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))).shouldBeTrue()
        (ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50),
            )).shouldBeTrue()
        (ImageMagicByteValidator.looksLikeSupportedImage(byteArrayOf(0x42, 0x4D))).shouldBeTrue()
        (ImageMagicByteValidator.looksLikeSupportedImage(
                byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63),
            )).shouldBeTrue()
    }

    private fun `requireSupportedImage rejects arbitrary payload even when stream opens successfully`() {
        val contentResolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("not-an-image".toByteArray())

        shouldThrow<IOException> {
            ImageMagicByteValidator.requireSupportedImage(contentResolver, uri)
        }
    }
}
