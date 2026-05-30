package com.lomo.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * Behavior Contract:
 * - Unit under test: writeWorkspaceSafFileFromStream
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: SAF media stream restore must not hide or destroy existing destination files when overwrite cannot be committed atomically.
 *
 * Scenarios:
 * - Given no destination file exists, when a stream restore completes, then staged bytes can be committed by renaming the temporary file.
 * - Given the destination filename already exists, when a stream restore is requested, then the existing file remains under its original name and the operation fails as recoverable I/O.
 * - Given SAF commit cannot safely replace the destination, when a temporary file was created, then the temporary file is cleaned up.
 * - Given a provider-specific rename/delete rollback could hide the original file, when overwrite is attempted, then existing media is never renamed or deleted.
 *
 * Observable outcomes:
 * - thrown IOException plus explicit absence of rename/delete calls on the existing DocumentFile and cleanup of the temporary DocumentFile.
 *
 * TDD proof:
 * - RED: fails before the fix because the SAF commit path renames the existing destination to a backup before trying to rename the temp file.
 *
 * Excludes:
 * - Android document picker permissions, real DocumentsProvider duplicate-name behavior, direct filesystem replacement, and archive parsing.
 */
class WorkspaceMediaSafStreamAccessTest : DataFunSpec() {
    init {
        test("given existing SAF media when stream restore writes same filename then original file is not renamed or deleted") {
            runTest {
                val context = mockk<Context>()
                val resolver = mockk<ContentResolver>()
                val root = mockk<DocumentFile>()
                val existing = mockk<DocumentFile>()
                val temp = mockk<DocumentFile>()
                val rootUri = mockk<Uri>()
                val tempUri = mockk<Uri>()
                mockkStatic(Uri::class)
                mockkStatic(DocumentFile::class)
                try {
                    every { Uri.parse(SAF_ROOT_URI) } returns rootUri
                    every { DocumentFile.fromTreeUri(context, rootUri) } returns root
                    every { context.contentResolver } returns resolver
                    every { root.createFile("image/png", match { name -> name.startsWith("cover.png.tmp.") }) } returns temp
                    every { temp.uri } returns tempUri
                    every { resolver.openOutputStream(tempUri) } returns ByteArrayOutputStream()
                    every { root.findFile("cover.png") } returns existing
                    every { existing.renameTo(any()) } returns true
                    every { existing.delete() } returns true
                    every { temp.renameTo("cover.png") } returns true
                    every { temp.delete() } returns true

                    val error =
                        shouldThrow<IOException> {
                            writeWorkspaceSafFileFromStream(
                                context = context,
                                category = WorkspaceMediaCategory.IMAGE,
                                rootUriString = SAF_ROOT_URI,
                                filename = "cover.png",
                            ) { output ->
                                output.write("incoming".toByteArray())
                            }
                        }

                    error.message shouldContain "Cannot safely replace existing SAF media file"
                    verify(exactly = 0) { existing.renameTo(any()) }
                    verify(exactly = 0) { existing.delete() }
                    verify(exactly = 0) { temp.renameTo("cover.png") }
                    verify(exactly = 1) { temp.delete() }
                } finally {
                    unmockkStatic(DocumentFile::class)
                    unmockkStatic(Uri::class)
                }
            }
        }
    }

    private companion object {
        const val SAF_ROOT_URI = "content://workspace-media/root"
    }
}
