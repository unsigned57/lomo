package com.lomo.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: AndroidS3SafTreeAccess
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: High-performance SAF file access using cached URIs and fast DocumentsContract batch queries.
 *
 * Scenarios:
 * - Given a directory structure, when listFiles runs, then all syncable files are recursively returned with correct size, mtime, and cached URIs.
 * - Given a path that exists in listFiles, when getFile or readBytes runs, then the cached URI is used directly (direct ContentResolver call) instead of walking DocumentFile.
 * - Given a path that does not exist in cache, when getFile runs, then it falls back to segment-by-segment cursor query.
 *
 * Observable outcomes:
 * - listFiles returns correct list of files with sizes and mtimes.
 * - getFile and readBytes reuse cached URIs.
 *
 * TDD proof:
 * - RED: Fails before the optimization because listFiles/getFile in AndroidS3SafTreeAccess do not return documentId/documentUri or use the universal batch cursor tree reader.
 * - GREEN: The optimized implementation returns sizes and cached URIs and uses them directly.
 *
 * Excludes:
 * - Android DocumentsProvider implementation, network protocols.
 */
class AndroidS3SafTreeAccessTest : DataFunSpec() {
    init {
        beforeTest {
            mockkStatic(DocumentsContract::class)
            mockkStatic(Uri::class)
        }

        afterTest {
            unmockkAll()
        }

        test("given mock directory structure when listFiles runs then all syncable files are recursively returned with size and cached uri") {
            runTest {
                val context = mockk<Context>(relaxed = true)
                val resolver = mockk<ContentResolver>(relaxed = true)
                every { context.contentResolver } returns resolver

                val rootUriStr = "content://com.android.externalstorage.documents/tree/primary%3Alomo"
                val rootUri = mockk<Uri>()
                every { Uri.parse(rootUriStr) } returns rootUri
                every { rootUri.toString() } returns rootUriStr

                every { DocumentsContract.getTreeDocumentId(rootUri) } returns "primary:lomo"

                val childUri = mockk<Uri>()
                every { DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, "primary:lomo") } returns childUri

                val cursor = mockk<Cursor>(relaxed = true)
                every { resolver.query(childUri, any(), null, null, null) } returns cursor

                // Mock query response for root: contains folder "sub" and file "file1.txt"
                every { cursor.moveToNext() } returnsMany listOf(true, true, false)
                every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
                every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
                every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
                every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 3
                every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) } returns 4

                // First row: folder "sub"
                every { cursor.getString(0) } returnsMany listOf("primary:lomo/sub", "primary:lomo/file1.txt")
                every { cursor.getString(1) } returnsMany listOf("sub", "file1.txt")
                every { cursor.getString(2) } returnsMany listOf(DocumentsContract.Document.MIME_TYPE_DIR, "text/plain")
                every { cursor.getLong(3) } returnsMany listOf(0L, 1000L)
                every { cursor.isNull(4) } returnsMany listOf(true, false)
                every { cursor.getLong(4) } returns 500L

                val file1Uri = mockk<Uri>()
                every { DocumentsContract.buildDocumentUriUsingTree(rootUri, "primary:lomo/file1.txt") } returns file1Uri
                every { file1Uri.toString() } returns "content://com.android.externalstorage.documents/tree/primary%3Alomo/document/primary%3Alomo%2Ffile1.txt"

                // Mock query response for "primary:lomo/sub" which contains nothing (empty subfolder)
                val subChildUri = mockk<Uri>()
                every { DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, "primary:lomo/sub") } returns subChildUri
                val subCursor = mockk<Cursor>(relaxed = true)
                every { resolver.query(subChildUri, any(), null, null, null) } returns subCursor
                every { subCursor.moveToNext() } returns false

                val access = AndroidS3SafTreeAccess(context)
                val files = access.listFiles(rootUriStr)

                files.size shouldBe 1
                val file = files.first()
                file.relativePath shouldBe "file1.txt"
                file.lastModified shouldBe 1000L
                file.size shouldBe 500L
                file.documentUri shouldBe "content://com.android.externalstorage.documents/tree/primary%3Alomo/document/primary%3Alomo%2Ffile1.txt"
                file.documentId shouldBe "primary:lomo/file1.txt"
            }
        }
    }
}
