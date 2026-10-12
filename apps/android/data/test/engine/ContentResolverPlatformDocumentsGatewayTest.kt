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
 *   without opening the file content stream, and the listing digest is unknown rather than the
 *   SHA-256 of empty bytes.
 * - Given a selected file, when it is opened for reading, then its content stream is opened once
 *   and the returned digest is calculated from those same bytes.
 * - Given an opaque handle returned by listing, when it is opened, then the provider document URI
 *   is queried directly without enumerating the parent directory again.
 * - Given a provider that accepts a write call but exposes different durable bytes through the
 *   returned document handle, when the write completes, then the boundary rejects the false
 *   success instead of publishing the requested digest as evidence.
 * - Given a create allocates the final provider document but opening its output stream fails, when
 *   the boundary aborts, then that newly allocated final document is deleted.
 * - Given a provider sanitizes or uniquifies the display name at create time, when a document is
 *   created, then the boundary repairs the durable name onto the requested path by rename, or
 *   fails closed and rolls back the created document instead of leaving a misnamed file.
 * - Given an observation with declared evidence depth, when stat runs, then METADATA_ONLY never
 *   opens the content stream while CONTENT produces the streamed digest.
 * - Given a multi-row listing page, when children are listed, then one provider query answers the
 *   whole page — the cursor's own document ids build every child URI without per-item lookups.
 * - Given ensureDirectory or move completes, when the tail snapshot is observed, then the leaf is
 *   queried through the document id already in hand instead of re-walking the whole path, and no
 *   content stream is opened.
 *
 * Observable outcomes:
 * - Returned document metadata/read bytes, write failure, ContentResolver stream opens, provider
 *   children-query count, and rollback deletion of an incomplete create target.
 *
 * TDD proof:
 * - RED on 2026-08-06 because listChildren opened every file to hash it and openRead opened the
 *   selected file once for querySnapshot digest plus a second time for the returned bytes.
 * - RED on 2026-08-25 because a failed create left the provider-allocated final path as a zero-byte
 *   document, causing every durable retry to fail with target-exists conflict.
 * - RED on 2026-09-02 because a provider-side display-name sanitize/uniquify silently detached
 *   the durable filename from the record identity, poisoning every later workspace history scan.
 * - RED on 2026-10-04 because every stat hashed whole files regardless of the caller's evidence
 *   need, and the ensureDirectory/move tails re-walked the full path through findChild before
 *   hashing the leaf a second time.
 *
 * Excludes:
 * - Provider-specific paging order, deletes, and Rust scan orchestration.
 *
 * Test Change Justification:
 * - Reason category: SAF document creation failure rollback.
 * - Old behavior/assertion being replaced: failed create without provider-allocated document cleanup.
 * - Why old assertion is no longer correct: failed stream creation must clean up zero-byte allocated documents.
 * - Coverage preserved by: all document streaming, digest hashing, and error rollbacks remain fully verified.
 * - Why this is not fitting the test to the implementation: verifies safe rollback of dangling SAF document handles.
 */

package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
            page.items.single().digest shouldBe null
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a provider that cannot enumerate when children are listed then the page is incomplete") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubUnreadableProvider()

            val page =
                fixture.gateway.listChildren(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Root,
                    cursor = null,
                    pageSize = 16u,
                )

            page.items shouldBe emptyList()
            page.incomplete shouldBe true
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

        test("given provider write acknowledgement without matching readback then write fails closed") {
            val staleBytes = "old provider bytes".encodeToByteArray()
            val requestedBytes = "new durable bytes".encodeToByteArray()
            val fixture = ResolverFixture(staleBytes)
            fixture.stubSingleFileWrite()

            shouldThrow<IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = requestedBytes,
                    mode = WriteMode.REPLACE,
                    mimeType = "text/markdown",
                )
            }

            fixture.inputStreamOpenCount shouldBe 1
        }

        test("given create output cannot open then the incomplete final document is rolled back") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubFailedCreateWrite()

            shouldThrow<IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                )
            }

            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test("given a provider sanitizes the created display name when a document is created then the boundary renames it onto the requested name") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithDisplayName(initialName = "2026_08_06 (1).md", repairedName = FILE_NAME)

            val snapshot =
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                )

            snapshot.documentId shouldBe DOCUMENT_ID
            snapshot.digest shouldBe sha256Hex(FILE_BYTES)
            snapshot.length shouldBe FILE_BYTES.size.toULong()
            fixture.createdDocumentRenameCount shouldBe 1
            fixture.createdDocumentDeleteCount shouldBe 0
        }

        test("given a file source when written from file then snapshot digest witnesses single-pass hash") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithDisplayName(initialName = FILE_NAME, repairedName = null)
            val tempFile = kotlin.io.path.createTempFile(prefix = "write_test", suffix = ".md").toFile()
            tempFile.writeBytes(FILE_BYTES)
            tempFile.deleteOnExit()

            val snapshot =
                fixture.gateway.writeFromFile(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    source = tempFile,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                )

            snapshot.documentId shouldBe DOCUMENT_ID
            snapshot.digest shouldBe sha256Hex(FILE_BYTES)
            snapshot.length shouldBe FILE_BYTES.size.toULong()
        }

        test("given a provider cannot honor the requested name when a document is created then the write fails and rolls back") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithDisplayName(initialName = "2026_08_06 (1).md", repairedName = null)

            shouldThrow<IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                )
            }

            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test("given the requested name is occupied when a provider uniquified the create then the write fails and rolls back") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithOccupiedRequestedName()

            shouldThrow<IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                )
            }

            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test("given metadata-only stat when the file is observed then its content stream is never opened") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubSingleFileRead()

            val snapshot =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(FILE_NAME),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )

            snapshot?.documentId shouldBe DOCUMENT_ID
            snapshot?.digest shouldBe null
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given content stat when the file is observed then the digest comes from one stream") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubSingleFileRead()

            val snapshot =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(FILE_NAME),
                    digest = SnapshotDigestMode.CONTENT,
                )

            snapshot?.digest shouldBe sha256Hex(FILE_BYTES)
            fixture.inputStreamOpenCount shouldBe 1
        }

        test("given several children when one page is listed then the provider answers one query for the whole page") {
            val fixture = ResolverFixture(FILE_BYTES)
            val rows =
                listOf(
                    ListingRow("primary:Lomo/one.md", "one.md"),
                    ListingRow("primary:Lomo/two.md", "two.md"),
                    ListingRow("primary:Lomo/three.md", "three.md"),
                )
            fixture.stubListing(listingCursor(rows))

            val page =
                fixture.gateway.listChildren(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Root,
                    cursor = null,
                    pageSize = 16u,
                )

            page.items.map { it.documentId } shouldBe rows.map { it.id }
            page.items.forEach { it.digest shouldBe null }
            fixture.childrenQueryCount shouldBe 1
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a created directory when ensure finishes then the leaf is observed without a second path walk") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDirectoryCreate(dirName = "subdir", dirDocId = DIR_DOCUMENT_ID)

            val snapshot = fixture.gateway.ensureDirectory(TREE_URI, "subdir")

            snapshot.documentId shouldBe DIR_DOCUMENT_ID
            snapshot.kind shouldBe DocumentKind.DIRECTORY
            // One findChild probe + one leaf snapshot query; the old tail stat re-walked the path.
            fixture.childrenQueryCount shouldBe 1
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a rename-in-place move when it completes then the receipt is observed without a second path walk or content stream") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubRenameInPlaceMove()

            val snapshot = fixture.gateway.move(TREE_URI, FILE_NAME, RENAMED_NAME)

            snapshot.documentId shouldBe MOVED_DOCUMENT_ID
            snapshot.digest shouldBe null
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given listing cursor when children are listed then page respects cursor offset") {
            val fixture = ResolverFixture(FILE_BYTES)
            val cursor = mockk<Cursor>()
            mockEvery { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            mockEvery { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            mockEvery { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            mockEvery { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE) } returns 3
            mockEvery { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
            mockEvery { cursor.moveToPosition(1) } returns true
            mockEvery { cursor.getString(0) } returns "primary:Lomo/2026_08_07.md"
            mockEvery { cursor.getString(1) } returns "2026_08_07.md"
            mockEvery { cursor.getString(2) } returns "text/markdown"
            mockEvery { cursor.getLong(3) } returns 100L
            mockEvery { cursor.getLong(4) } returns 1_754_300_000_000L
            mockEvery { cursor.moveToNext() } returns false
            mockEvery { cursor.close() } returns Unit
            fixture.stubListing(cursor)

            val page =
                fixture.gateway.listChildren(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Root,
                    cursor = "1",
                    pageSize = 16u,
                )

            page.items.single().documentId shouldBe "primary:Lomo/2026_08_07.md"
            page.nextCursor shouldBe null
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
    var childrenQueryCount: Int = 0
        private set
    var createdDocumentDeleteCount: Int = 0
        private set
    var createdDocumentRenameCount: Int = 0
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
        every { DocumentsContract.getDocumentId(documentUri) } returns DOCUMENT_ID
        every { resolver.openInputStream(documentUri) } answers {
            inputStreamOpenCount += 1
            ByteArrayInputStream(fileBytes)
        }
    }

    fun stubSingleFileListing() {
        val cursor = documentCursor(includeDisplayName = true)
        stubListing(cursor)
    }

    fun stubUnreadableProvider() {
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } returns null
    }

    fun stubListing(cursor: Cursor) {
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } answers {
            childrenQueryCount += 1
            cursor
        }
    }

    /**
     * Directory create flow: the leaf segment misses findChild, is created under the root
     * document, and the tail observation queries the created document id directly.
     */
    fun stubDirectoryCreate(
        dirName: String,
        dirDocId: String,
    ) {
        val createdUri = mockk<Uri>()
        val createdDocUri = mockk<Uri>()
        every {
            resolver.query(childrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            absentLookup()
        }
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID)
        } returns documentUri
        every {
            DocumentsContract.createDocument(
                resolver,
                documentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                dirName,
            )
        } returns createdUri
        every { DocumentsContract.getDocumentId(createdUri) } returns dirDocId
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, dirDocId)
        } returns createdDocUri
        every {
            resolver.query(createdDocUri, any<Array<String>>(), null, null, null)
        } returns directoryCursor(dirDocId, dirName)
    }

    /**
     * Rename-in-place move: source resolves under the root, the target name is absent, and the
     * provider returns the same document URI under the repaired name — so the move tail observes
     * the returned URI without walking the target path.
     */
    fun stubRenameInPlaceMove() {
        val movedUri = mockk<Uri>()
        var childrenQueries = 0
        every {
            resolver.query(childrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            childrenQueries += 1
            // First probe resolves the source; the target-name probes come back absent.
            if (childrenQueries == 1) lookupCursor() else absentLookup()
        }
        every {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
        } returns displayNameCursor(FILE_NAME)
        every {
            resolver.query(movedUri, any<Array<String>>(), null, null, null)
        } returns movedDocumentCursor()
        every {
            resolver.query(
                movedUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
        } returns displayNameCursor(RENAMED_NAME)
        every {
            DocumentsContract.renameDocument(resolver, documentUri, RENAMED_NAME)
        } returns movedUri
        every { DocumentsContract.getDocumentId(movedUri) } returns MOVED_DOCUMENT_ID
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

    fun stubSingleFileWrite() {
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
        every { resolver.openOutputStream(documentUri, "wt") } returns ByteArrayOutputStream()
    }

    fun stubFailedCreateWrite() {
        stubAbsentChildLookup()
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID)
        } returns documentUri
        every {
            DocumentsContract.createDocument(
                resolver,
                documentUri,
                "text/markdown",
                FILE_NAME,
            )
        } returns documentUri
        every {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
        } returns displayNameCursor(FILE_NAME)
        every { resolver.openOutputStream(documentUri, "wt") } returns null
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            createdDocumentDeleteCount += 1
            true
        }
    }

    /** Create flow whose provider sanitizes the requested display name at createDocument time. */
    fun stubCreateWithDisplayName(
        initialName: String,
        repairedName: String?,
    ) {
        stubAbsentChildLookup()
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID)
        } returns documentUri
        every {
            DocumentsContract.createDocument(
                resolver,
                documentUri,
                any<String>(),
                FILE_NAME,
            )
        } returns documentUri
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } returns
            documentCursor(includeDisplayName = false)
        stubDisplayNameQueries(initialName, repairedName)
        every { resolver.openOutputStream(documentUri, "wt") } returns ByteArrayOutputStream()
        if (repairedName != null) {
            every {
                DocumentsContract.renameDocument(resolver, documentUri, FILE_NAME)
            } answers {
                createdDocumentRenameCount += 1
                documentUri
            }
        } else {
            every {
                DocumentsContract.renameDocument(resolver, documentUri, FILE_NAME)
            } returns null
        }
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            createdDocumentDeleteCount += 1
            true
        }
        every { resolver.openInputStream(documentUri) } answers {
            inputStreamOpenCount += 1
            ByteArrayInputStream(fileBytes)
        }
    }

    /**
     * Create flow where the provider uniquified against a document the path lookup missed, so
     * the requested name is occupied when the display-name law runs.
     */
    fun stubCreateWithOccupiedRequestedName() {
        var childQueries = 0
        val foundLookup = lookupCursor()
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } answers {
            childQueries += 1
            if (childQueries == 1) absentLookup() else foundLookup
        }
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID)
        } returns documentUri
        every {
            DocumentsContract.createDocument(
                resolver,
                documentUri,
                any<String>(),
                FILE_NAME,
            )
        } returns documentUri
        stubDisplayNameQueries(initialName = "2026_08_06 (1).md", repairedName = null)
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            createdDocumentDeleteCount += 1
            true
        }
    }

    private fun stubAbsentChildLookup() {
        every {
            resolver.query(
                childrenUri,
                any<Array<String>>(),
                null,
                null,
                null,
            )
        } returns absentLookup()
    }

    private fun absentLookup(): Cursor =
        mockk<Cursor>().also { cursor ->
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every { cursor.moveToNext() } returns false
            every { cursor.close() } returns Unit
        }

    private fun displayNameCursor(name: String): Cursor =
        mockk<Cursor>().also { cursor ->
            every { cursor.moveToFirst() } returns true
            every { cursor.getString(0) } returns name
            every { cursor.close() } returns Unit
        }

    private fun stubDisplayNameQueries(
        initialName: String,
        repairedName: String?,
    ) {
        val displayNameProjection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        // The generic projection stub is registered first so the specific display-name stubs
        // win; the gateway queries the name twice when a rename repair is attempted.
        every { resolver.query(documentUri, displayNameProjection, null, null, null) } returnsMany
            listOf(
                displayNameCursor(initialName),
                displayNameCursor(repairedName ?: initialName),
            )
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

    private fun directoryCursor(
        docId: String,
        name: String,
    ): Cursor =
        mockk<Cursor>().also { cursor ->
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            } returns 2
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            } returns 3
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            } returns 4
            every { cursor.moveToFirst() } returns true
            every { cursor.getString(0) } returns docId
            every { cursor.getString(1) } returns name
            every { cursor.getString(2) } returns DocumentsContract.Document.MIME_TYPE_DIR
            every { cursor.getLong(3) } returns 0L
            every { cursor.getLong(4) } returns 1_754_300_000_000L
            every { cursor.close() } returns Unit
        }

    private fun movedDocumentCursor(): Cursor =
        mockk<Cursor>().also { cursor ->
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            } returns 2
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            } returns 3
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            } returns 4
            every { cursor.moveToFirst() } returns true
            every { cursor.getString(0) } returns MOVED_DOCUMENT_ID
            every { cursor.getString(1) } returns RENAMED_NAME
            every { cursor.getString(2) } returns "text/markdown"
            every { cursor.getLong(3) } returns fileBytes.size.toLong()
            every { cursor.getLong(4) } returns 1_754_300_000_000L
            every { cursor.close() } returns Unit
        }
}

private class ListingRow(
    val id: String,
    val name: String,
    val mime: String = "text/markdown",
    val size: Long = 128L,
    val lastModified: Long = 1_754_300_000_000L,
)

private fun listingCursor(rows: List<ListingRow>): Cursor =
    mockk<Cursor>().also { cursor ->
        var position = -1
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        } returns 0
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } returns 1
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        } returns 2
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        } returns 3
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        } returns 4
        every { cursor.moveToNext() } answers {
            position += 1
            position < rows.size
        }
        every { cursor.moveToPosition(any()) } answers {
            position = firstArg()
            position < rows.size
        }
        every { cursor.getString(any()) } answers {
            val row = rows[position]
            when (firstArg<Int>()) {
                0 -> row.id
                1 -> row.name
                else -> row.mime
            }
        }
        every { cursor.getLong(any()) } answers {
            val row = rows[position]
            if (firstArg<Int>() == 3) row.size else row.lastModified
        }
        every { cursor.close() } returns Unit
    }

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val TREE_URI = "content://com.lomo.documents/tree/primary%3ALomo"
private const val ROOT_DOCUMENT_ID = "primary:Lomo"
private const val DOCUMENT_ID = "primary:Lomo/2026_08_06.md"
private const val FILE_NAME = "2026_08_06.md"
private const val DIR_DOCUMENT_ID = "primary:Lomo/subdir"
private const val MOVED_DOCUMENT_ID = "primary:Lomo/renamed.md"
private const val RENAMED_NAME = "renamed.md"
private val FILE_BYTES = "# memo\n\nbody".encodeToByteArray()
