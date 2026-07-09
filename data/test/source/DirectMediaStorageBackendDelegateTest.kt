/*
 * Behavior Contract:
 * - Unit under test: DirectMediaStorageBackendDelegate.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: direct media roots implement the same image-save backend contract as SAF roots.
 *
 * Scenarios:
 * - Given a direct image root and a readable source URI, when saveImage runs, then the source bytes are
 *   copied into the requested filename under the direct root.
 * - Given the source URI opens but root preparation fails, when saveImage fails, then the
 *   already-open source stream is closed.
 * - Given the source URI opens but the target output cannot be opened, when saveImage fails, then
 *   the already-open source stream is closed.
 *
 * Observable outcomes:
 * - persisted file bytes under the direct root path and source stream close state after failed
 *   root preparation or target open.
 *
 * TDD proof:
 * - RED: fails before implementation because DirectMediaStorageBackendDelegate has no Context-backed
 *   constructor and its saveImage contract throws UnsupportedOperationException.
 * - RED: fails before stream ownership fix because root preparation failure leaves the opened source
 *   stream unclosed.
 * - RED: fails before stream ownership fix because target output failure leaves the opened source
 *   stream unclosed.
 *
 * Excludes:
 * - image magic-byte validation, filename generation, SAF document writes, and Android permission
 *   handling.
 */

package com.lomo.data.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

class DirectMediaStorageBackendDelegateTest : DataFunSpec() {
    init {
        test("given direct image root when saveImage runs then source bytes are copied into requested file") {
            runTest {
                val rootDir = Files.createTempDirectory("direct-media-backend-test").toFile()
                val sourceUri = mockk<Uri>()
                val contentResolver = mockk<ContentResolver>()
                val context = mockk<Context>()
                val imageBytes = byteArrayOf(0x01, 0x23, 0x45, 0x67)
                every { context.contentResolver } returns contentResolver
                every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(imageBytes)
                val backend =
                    DirectMediaStorageBackendDelegate(
                        context = context,
                        rootDir = rootDir,
                    )

                backend.saveImage(sourceUri = sourceUri, filename = "direct-image.png")

                rootDir.resolve("direct-image.png").readBytes() shouldBe imageBytes
                rootDir.deleteRecursively()
            }
        }

        test("given source opens and root preparation fails when saveImage fails then source stream is closed") {
            runTest {
                val rootFile = RootPreparationFailureFile()
                val sourceUri = mockk<Uri>()
                val contentResolver = mockk<ContentResolver>()
                val context = mockk<Context>()
                val sourceStream = CloseRecordingInputStream(byteArrayOf(0x01, 0x23, 0x45, 0x67))
                every { context.contentResolver } returns contentResolver
                every { contentResolver.openInputStream(sourceUri) } returns sourceStream
                val backend =
                    DirectMediaStorageBackendDelegate(
                        context = context,
                        rootDir = rootFile,
                    )

                val error =
                    runCatching {
                        backend.saveImage(sourceUri = sourceUri, filename = "blocked-root.png")
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<SecurityException>()
                sourceStream.closed shouldBe true
            }
        }

        test("given source opens and target output fails when saveImage fails then source stream is closed") {
            runTest {
                val rootPath = Files.createTempFile("direct-media-backend-root-file", ".tmp")
                val rootFile = rootPath.toFile()
                val sourceUri = mockk<Uri>()
                val contentResolver = mockk<ContentResolver>()
                val context = mockk<Context>()
                val sourceStream = CloseRecordingInputStream(byteArrayOf(0x01, 0x23, 0x45, 0x67))
                every { context.contentResolver } returns contentResolver
                every { contentResolver.openInputStream(sourceUri) } returns sourceStream
                val backend =
                    DirectMediaStorageBackendDelegate(
                        context = context,
                        rootDir = rootFile,
                    )

                val error =
                    runCatching {
                        backend.saveImage(sourceUri = sourceUri, filename = "blocked-output.png")
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IOException>()
                sourceStream.closed shouldBe true
                rootFile.delete()
            }
        }
    }
}

private class RootPreparationFailureFile : File("direct-media-backend-root-denied") {
    override fun exists(): Boolean = false

    override fun mkdirs(): Boolean = throw SecurityException("Cannot prepare direct media root")
}

private class CloseRecordingInputStream(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    var closed: Boolean = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}
