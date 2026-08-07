package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: ContentResolverPlatformDocumentsGateway.
 * - Owning layer: data Android DocumentsContract boundary.
 * - Priority tier: P0.
 * - Capability: enumerate SAF metadata and read a selected document with I/O proportional to the
 *   requested content, without turning directory enumeration into a full-content scan.
 *
 * Scenarios:
 * - Given a directory containing a file, when children are listed, then metadata is returned
 *   without opening the file content stream.
 * - Given a selected file, when it is opened for reading, then its content stream is opened once
 *   and the returned digest is calculated from those same bytes.
 * - Given an opaque handle returned by listing, when it is opened, then the provider document URI
 *   is queried directly without enumerating the parent directory again.
 *
 * Observable outcomes:
 * - Returned document metadata/read bytes and the number of ContentResolver input-stream opens.
 *
 * TDD proof:
 * - RED on 2026-08-06 because listChildren opened every file to hash it and openRead opened the
 *   selected file once for querySnapshot digest plus a second time for the returned bytes.
 *
 * Excludes:
 * - Provider-specific paging order, writes, moves, deletes, and Rust scan orchestration.
 */

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class ContentResolverPlatformDocumentsGatewayTest : DataFunSpec() {
    init {
        afterTest {
            unmockkStatic(Uri::class)
            unmockkStatic(DocumentsContract::class)
        }

        test("given a SAF file when children are listed then content is not opened") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubSingleFileListing()

            val page =
                fixture.gateway.listChildren(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Root,
                    cursor = null,
                    pageSize = 16u,
                )

            page.items.single().documentId shouldBe DOCUMENT_ID
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a SAF file when it is read then the content stream is opened exactly once") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubSingleFileRead()

            val handle = fixture.gateway.openRead(TREE_URI, FILE_NAME)

            handle.bytes shouldBe FILE_BYTES
            handle.snapshot.digest shouldBe sha256Hex(FILE_BYTES)
            fixture.inputStreamOpenCount shouldBe 1
        }

        test("given listed handle when it is read then parent path is not resolved again") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubHandleRead()

            val handle = fixture.gateway.openReadByHandle(TREE_URI, "renamed.md", DOCUMENT_ID)

            handle.bytes shouldBe FILE_BYTES
            handle.snapshot.target shouldBe WorkspaceTarget.Relative("renamed.md")
            fixture.parentQueryCount shouldBe 0
            fixture.inputStreamOpenCount shouldBe 1
        }
    }
}

private class ResolverFixture(
    private val fileBytes: ByteArray,
) {
    private val resolver = mockk<ContentResolver>()
    private val rootUri = mockk<Uri>()
    private val childrenUri = mockk<Uri>()
    private val documentUri = mockk<Uri>()
    val gateway = ContentResolverPlatformDocumentsGateway(resolver)
    var inputStreamOpenCount: Int = 0
        private set
    var parentQueryCount: Int = 0
        private set

    init {
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        every { Uri.parse(TREE_URI) } returns rootUri
        every { DocumentsContract.getTreeDocumentId(rootUri) } returns ROOT_DOCUMENT_ID
        every {
            DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, ROOT_DOCUMENT_ID)
        } returns childrenUri
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, DOCUMENT_ID)
        } returns documentUri
        every { resolver.openInputStream(documentUri) } answers {
            inputStreamOpenCount += 1
            ByteArrayInputStream(fileBytes)
        }
    }

    fun stubSingleFileListing() {
        val cursor = documentCursor(includeDisplayName = true)
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } returns cursor
    }

    fun stubSingleFileRead() {
        val lookup = lookupCursor()
        val metadata = documentCursor(includeDisplayName = false)
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } answers {
            parentQueryCount += 1
            lookup
        }
        every {
            resolver.query(
                documentUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } returns metadata
    }

    fun stubHandleRead() {
        val metadata = documentCursor(includeDisplayName = false)
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } answers {
            parentQueryCount += 1
            lookupCursor()
        }
        every {
            resolver.query(
                documentUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } returns metadata
    }

    private fun lookupCursor(): Cursor =
        mockk<Cursor>().also { cursor ->
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every { cursor.moveToNext() } returnsMany listOf(true, false)
            every { cursor.getString(0) } returns DOCUMENT_ID
            every { cursor.getString(1) } returns FILE_NAME
            every { cursor.close() } returns Unit
        }

    private fun documentCursor(includeDisplayName: Boolean): Cursor =
        mockk<Cursor>().also { cursor ->
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            if (includeDisplayName) {
                every {
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                } returns 1
            }
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            } returns 2
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            } returns 3
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            } returns 4
            if (includeDisplayName) {
                every { cursor.moveToNext() } returnsMany listOf(true, false)
            } else {
                every { cursor.moveToFirst() } returns true
            }
            every { cursor.getString(0) } returns DOCUMENT_ID
            every { cursor.getString(1) } returns FILE_NAME
            every { cursor.getString(2) } returns "text/markdown"
            every { cursor.getLong(3) } returns fileBytes.size.toLong()
            every { cursor.getLong(4) } returns 1_754_300_000_000L
            every { cursor.close() } returns Unit
        }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val TREE_URI = "content://com.lomo.documents/tree/primary%3ALomo"
private const val ROOT_DOCUMENT_ID = "primary:Lomo"
private const val DOCUMENT_ID = "primary:Lomo/2026_08_06.md"
private const val FILE_NAME = "2026_08_06.md"
private val FILE_BYTES = "# memo\n\nbody".encodeToByteArray()
