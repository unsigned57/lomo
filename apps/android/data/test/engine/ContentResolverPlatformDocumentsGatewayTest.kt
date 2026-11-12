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
 * - Given a parent already listed in this session, when sibling names are resolved repeatedly,
 *   then the last live listing answers each name — the provider is not re-queried per sibling.
 * - Given a negative answer the listing memo produced, when the name is resolved again, then the
 *   boundary re-verifies that parent live once instead of trusting the memo; an all-live miss is
 *   already fresh evidence and the chain is not re-walked.
 * - Given a miss under memo-resolved ancestors, when it is re-verified, then each ancestor handle
 *   is observed by name and kind and the miss stays authoritative; no ancestor is re-enumerated,
 *   so proving an absent `.lomo/` record never lists every dated file under the workspace root.
 * - Given a memo-resolved ancestor that was renamed, when the miss is re-verified, then the chain
 *   is resolved live again instead of trusting the stale handle.
 * - Given a replacement, when the chain is resolved, then arbitration runs on the live leaf observation
 *   from that same walk and a memo the write populates answers the next stat without a new listing.
 * - Given a committed write, when the bytes are persisted, then the provider stream pair verifies
 *   the durable digest inside one resolution chain — a provider that cannot open "rwt" degrades
 *   to the explicit two-pass output/input pair for that whole tree.
 * - Given a previously observed parent directory, when a child is created, then the parent
 *   handle is verified live without enumerating its siblings; the leaf listing stays live.
 * - Given a renamed or deleted cached parent, when a write resolves it, then a live name
 *   lookup finds its replacement or anchors creation; the stale handle cannot receive bytes.
 *
 * - Given one hundred create-only writes, when each provider receipt proves the exact requested
 *   name, then enumeration stays constant; an external name collision still rolls back the new
 *   allocation before any rename of the occupied target.
 *
 * Replacement scaling: Given 100 edits after a positive name observation, each edit reads the
 * live content for arbitration and verifies persisted bytes without enumerating siblings again.
 * A renamed cached leaf must be re-resolved live and cannot receive bytes under its old name.
 * RED/GREEN: workspace-scaling/saf-replacement-*.log.
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
 * - RED on 2026-10-12 because every name resolution re-listed the whole parent directory over
 *   binder, turning an N-sibling engine scan into O(N^2) provider row transfers.
 * - RED on 2026-10-20 because every resolution re-listed live and write arbitration re-walked the
 *   path a second time inside the mutation call, turning a deep create into three live listings
 *   and every committed write into a fresh full-chain walk.
 * - RED: verified-parent create expected two child listings but received three, because the
 *   write enumerated the root again after ensureDirectory had already resolved its child.
 * - RED: deleted-parent create surfaced the stale handle's FileNotFoundException instead of
 *   reaching the live replacement/creation decision; GREEN re-resolves only that missing handle.
 *
 * - RED create-scaling: 100 independent creates enumerate the parent 100 times instead of once.
 * - RED on 2026-11-07 (target/lomo/reports/manual/instant-feed/kotlin-saf-red.log): a miss under a
 *   memo-resolved `subdir` re-enumerated the root on every stat; on device every create paid two
 *   ~300 ms root listings to prove `.lomo/layout_head.rec` absent.
 *
 * Excludes:
 * - Provider-specific paging order, deletes, and Rust scan orchestration.
 *
 * Test Change Justification:
 * - Reason category: ancestor re-verification by handle observation instead of re-enumeration.
 * - Old behavior/assertion being replaced: a memo-consulted miss cost eight children queries
 *   (leaf list plus a full two-hop live re-walk per stat).
 * - Why old assertion is no longer correct: the leaf parent's live listing already proves the
 *   miss; the re-walk only has to establish that memo-resolved ancestors still name that path,
 *   which one handle observation per ancestor proves without transferring every sibling.
 * - Coverage preserved by: the leaf is still listed live on every memo-consulted miss, and the
 *   renamed-ancestor control proves a stale handle still forces a live chain resolution.
 * - Why this is not fitting the test to the implementation: workspace-root size must not
 *   multiply the cost of proving one nested record absent.
 * - Reason category: bounded replacement lookup with live leaf authority.
 * - Old behavior/assertion being replaced: no old assertions replaced; replacements formerly listed all siblings.
 * - Why old assertion is no longer correct: cached positive handles can be verified by live name and content.
 * - Coverage preserved by: 100 distinct persisted payloads, live arbitration digests, renamed-leaf rejection, and existing rollback/CAS controls.
 * - Why this is not fitting the test to the implementation: directory growth must not multiply the work of replacing one known file.
 * - Reason category: directory snapshot pagination replaces unsafe numeric offsets.
 * - Old behavior/assertion being replaced: a bare "1" resumed a freshly queried live directory.
 * - Why old assertion is no longer correct: offsets do not bind to a tree or observation and
 *   can skip/duplicate files after changes; malformed offsets previously restarted page one.
 * - Coverage preserved by: SafDirectoryPaginationTest checks complete 4097-row paging,
 *   changed live membership, fresh enumeration, foreign cursors and finished snapshot rejection.
 * - Why this is not fitting the test to the implementation: exact-once enumeration and one
 *   provider query are observable correctness and scaling requirements independent of token shape.
 * - Reason category: create-only allocation and stateful collision evidence.
 * - Old behavior/assertion being replaced: all creates enumerate live; a cached-miss collision
 *   test assumed exactly one rollback even when rejection happened before allocation.
 * - Why old assertion is no longer correct: either rejection before allocation or rollback of
 *   the newly allocated document protects the target; the provider fixture must distinguish
 *   the new document's opaque identity from the pre-existing foreign document's identity.
 * - Coverage preserved by: the provider owns a set of live allocations, which must be empty
 *   after failure; foreign-target deletion and rename remain forbidden, and 100 successful
 *   creates return 100 distinct, correctly named documents with one enumeration.
 * - Why this is not fitting the test to the implementation: the observable lock is no leaked
 *   allocation and no foreign mutation, independent of the point at which a conflict is found.
 * - Reason category: SAF document creation failure rollback.
 * - Old behavior/assertion being replaced: failed create without provider-allocated document cleanup.
 * - Why old assertion is no longer correct: failed stream creation must clean up zero-byte allocated documents.
 * - Coverage preserved by: all document streaming, digest hashing, and error rollbacks remain fully verified.
 * - Why this is not fitting the test to the implementation: verifies safe rollback of dangling SAF document handles.
 * - Reason category: fused single-walk write and memoized mutation outcomes.
 * - Old behavior/assertion being replaced: arbitration and mutation walked the path twice; a memo
 *   listing could never observe a committed write's own document; every miss walked live twice.
 * - Why old assertion is no longer correct: one resolution chain now carries arbitration,
 *   creation and verification, and committed mutations update the listing memo in place.
 * - Coverage preserved by: rollback, display-name repair, readback verification and the
 *   negative-answer re-verification rule are still pinned against live provider counts.
 * - Why this is not fitting the test to the implementation: the asserted counts are the
 *   provable minimum provider calls for the same durable semantics.
 */

package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeCursor
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

            val error =
                shouldThrow<PlatformActionAccessException> {
                    fixture.gateway.writeFromExchange(
                        treeUri = TREE_URI,
                        path = FILE_NAME,
                        bytes = requestedBytes,
                        mode = WriteMode.REPLACE,
                        mimeType = "text/markdown",
                        arbitrate = { null },
                    )
                }

            error.code shouldBe "platform_postcondition_mismatch"
            // One content observation for write arbitration plus one persisted-byte readback;
            // the separate stat stream the access layer used to open is folded into this walk.
            fixture.inputStreamOpenCount shouldBe 2
        }

        test("given create output cannot open then the incomplete final document is rolled back") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubFailedCreateWrite()

            val error =
                shouldThrow<PlatformActionAccessException> {
                    fixture.gateway.writeFromExchange(
                        treeUri = TREE_URI,
                        path = FILE_NAME,
                        bytes = FILE_BYTES,
                        mode = WriteMode.CREATE,
                        mimeType = "text/markdown",
                        arbitrate = { null },
                    )
                }

            error.code shouldBe "platform_io_error"
            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test(
            "given a provider sanitizes the created display name when a document is created then the boundary renames it onto the requested name",
        ) {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithDisplayName(initialName = "2026_08_06 (1).md", repairedName = FILE_NAME)

            val outcome =
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )

            val snapshot = (outcome as WriteOutcome.Written).snapshot
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

            val outcome =
                fixture.gateway.writeFromFile(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    source = tempFile,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )

            val snapshot = (outcome as WriteOutcome.Written).snapshot
            snapshot.documentId shouldBe DOCUMENT_ID
            snapshot.digest shouldBe sha256Hex(FILE_BYTES)
            snapshot.length shouldBe FILE_BYTES.size.toULong()
        }

        test(
            "given a provider cannot honor the requested name when a document is created then the write fails and rolls back",
        ) {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithDisplayName(initialName = "2026_08_06 (1).md", repairedName = null)

            val error =
                shouldThrow<PlatformActionAccessException> {
                    fixture.gateway.writeFromExchange(
                        treeUri = TREE_URI,
                        path = FILE_NAME,
                        bytes = FILE_BYTES,
                        mode = WriteMode.CREATE,
                        mimeType = "text/markdown",
                        arbitrate = { null },
                    )
                }

            error.code shouldBe "platform_io_error"
            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test(
            "given the requested name is occupied when a provider uniquified the create then the write fails and rolls back",
        ) {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithOccupiedRequestedName()

            val error =
                shouldThrow<PlatformActionAccessException> {
                    fixture.gateway.writeFromExchange(
                        treeUri = TREE_URI,
                        path = FILE_NAME,
                        bytes = FILE_BYTES,
                        mode = WriteMode.CREATE,
                        mimeType = "text/markdown",
                        arbitrate = { null },
                    )
                }

            error.code shouldBe "platform_postcondition_mismatch"
            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test("given a document is absent when it is read then the failure reports document_not_found") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubAbsentDocument()

            val error =
                shouldThrow<PlatformActionAccessException> {
                    fixture.gateway.openRead(TREE_URI, FILE_NAME)
                }

            error.code shouldBe "document_not_found"
            fixture.inputStreamOpenCount shouldBe 0
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

        test(
            "given a rename-in-place move when it completes then the receipt is observed without a second path walk or content stream",
        ) {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubRenameInPlaceMove()

            val snapshot = fixture.gateway.move(TREE_URI, FILE_NAME, RENAMED_NAME)

            snapshot.documentId shouldBe MOVED_DOCUMENT_ID
            snapshot.digest shouldBe null
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a listed directory when each child is statted then the parent listing is queried once") {
            val fixture = ResolverFixture(FILE_BYTES)
            val rows =
                listOf(
                    ListingRow("primary:Lomo/one.md", "one.md"),
                    ListingRow("primary:Lomo/two.md", "two.md"),
                    ListingRow("primary:Lomo/three.md", "three.md"),
                    ListingRow("primary:Lomo/four.md", "four.md"),
                )
            fixture.stubSiblingDocuments(rows)

            rows.forEach { row ->
                val snapshot =
                    fixture.gateway.stat(
                        treeUri = TREE_URI,
                        target = WorkspaceTarget.Relative(row.name),
                        digest = SnapshotDigestMode.METADATA_ONLY,
                    )
                snapshot?.documentId shouldBe row.id
            }

            fixture.childrenQueryCount shouldBe 1
        }

        test("given a miss consulted from a listing memo when stat runs then the answer is re-verified live once") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDirListingWithLeafMiss()

            val warm =
                fixture.gateway.listChildren(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(DIR_NAME),
                    cursor = null,
                    pageSize = 16u,
                )
            val first =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative("$DIR_NAME/$FILE_NAME"),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )
            val second =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative("$DIR_NAME/$FILE_NAME"),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )

            warm.items shouldBe emptyList()
            first shouldBe null
            second shouldBe null
            // The memo only accelerates positive hits — a name it does not hold always lists
            // live: warm root+subdir (2), then one live leaf-parent listing per stat. The
            // memo-resolved ancestor is re-verified by observing its own handle.
            fixture.childrenQueryCount shouldBe 4
            fixture.rootChildrenQueryCount shouldBe 1
        }

        test("given a renamed memo-resolved ancestor when a miss is re-verified then the chain resolves live") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDirListingWithLeafMiss(ancestorName = "renamed")

            fixture.gateway.listChildren(
                treeUri = TREE_URI,
                target = WorkspaceTarget.Relative(DIR_NAME),
                cursor = null,
                pageSize = 16u,
            )
            val stat =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative("$DIR_NAME/$FILE_NAME"),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )

            stat shouldBe null
            // A stale ancestor handle cannot certify the miss: the root is enumerated again.
            fixture.rootChildrenQueryCount shouldBe 2
        }

        test("given every live listing answers a miss when stat runs then the chain is not re-walked") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDocumentAppearingAfterAbsence()

            val first =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(FILE_NAME),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )
            val second =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(FILE_NAME),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )

            first shouldBe null
            second?.documentId shouldBe DOCUMENT_ID
            // One live listing per stat — an all-live miss is fresh evidence, not a guess.
            fixture.childrenQueryCount shouldBe 2
        }

        test("given a deep create when the write commits then one live chain walks each level once") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDeepCreate()

            val outcome =
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = "$DIR_NAME/$FILE_NAME",
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )

            (outcome as WriteOutcome.Written).snapshot.documentId shouldBe DOCUMENT_ID
            // One live listing per walked level; the old flow re-walked the chain inside ensure.
            fixture.childrenQueryCount shouldBe 2
        }

        test("given a verified parent when a child is created then sibling enumeration is not repeated") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDeepCreate()
            fixture.gateway.ensureDirectory(TREE_URI, DIR_NAME).kind shouldBe DocumentKind.DIRECTORY

            val outcome = fixture.gateway.writeFromExchange(
                treeUri = TREE_URI,
                path = "$DIR_NAME/$FILE_NAME",
                bytes = FILE_BYTES,
                mode = WriteMode.CREATE,
                mimeType = "text/markdown",
                arbitrate = { null },
            )

            outcome.shouldBeInstanceOf<WriteOutcome.Written>().snapshot.documentId shouldBe DOCUMENT_ID
            fixture.childrenQueryCount shouldBe 2
        }

        test("given a renamed cached parent when a child is written then the old handle cannot receive the write") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDeepCreate()
            fixture.gateway.ensureDirectory(TREE_URI, DIR_NAME)
            fixture.renameDeepParent()

            shouldThrow<java.io.IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = "$DIR_NAME/$FILE_NAME",
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )
            }.message shouldBe "replacement directory refused"
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given a deleted cached parent when a child is written then its absence is re-resolved live") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubDeepCreate()
            fixture.gateway.ensureDirectory(TREE_URI, DIR_NAME)
            fixture.removeDeepParent()

            shouldThrow<java.io.IOException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = "$DIR_NAME/$FILE_NAME",
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )
            }.message shouldBe "replacement directory refused"
            fixture.inputStreamOpenCount shouldBe 0
        }

        test("given independent creates when receipts prove each name then provider enumeration stays constant") {
            val fixture = ResolverFixture(FILE_BYTES)
            val names = (0 until 100).map { "import-$it.rec" }
            fixture.stubIndependentCreates(names)

            val ids = names.map { name ->
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = name,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "application/octet-stream",
                    arbitrate = { null },
                ).shouldBeInstanceOf<WriteOutcome.Written>().snapshot.let { snapshot ->
                    snapshot.displayName shouldBe name
                    snapshot.documentId
                }
            }

            ids.toSet().size shouldBe 100
            fixture.childrenQueryCount shouldBe 1
        }

        test("given repeated replacements when cached leaf names are live then sibling enumeration stays constant") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubRepeatedReplacements()
            fixture.gateway.stat(TREE_URI, WorkspaceTarget.Relative(FILE_NAME), SnapshotDigestMode.METADATA_ONLY)
            var previous = FILE_BYTES
            repeat(100) { index ->
                val bytes = "replacement-$index".encodeToByteArray()
                val result = fixture.gateway.writeFromExchange(
                    TREE_URI,
                    FILE_NAME,
                    bytes,
                    WriteMode.REPLACE,
                    "text/markdown",
                ) { existing ->
                    requireNotNull(existing).digest shouldBe sha256Hex(previous)
                    null
                }.shouldBeInstanceOf<WriteOutcome.Written>()
                result.snapshot.digest shouldBe sha256Hex(bytes)
                fixture.replacementBytes.toList() shouldBe bytes.toList()
                previous = bytes
            }
            fixture.childrenQueryCount shouldBe 1
        }

        test("given a renamed cached leaf when replacing its old name then the foreign handle cannot receive bytes") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubRepeatedReplacements()
            fixture.gateway.stat(TREE_URI, WorkspaceTarget.Relative(FILE_NAME), SnapshotDigestMode.METADATA_ONLY)
            fixture.renameReplacementLeaf()
            shouldThrow<java.io.IOException> {
                fixture.gateway.writeFromExchange(
                    TREE_URI,
                    FILE_NAME,
                    FILE_BYTES,
                    WriteMode.REPLACE,
                    "text/markdown",
                ) { existing ->
                    existing shouldBe null
                    throw java.io.IOException("replacement baseline missing")
                }
            }.message shouldBe "replacement baseline missing"
            fixture.childrenQueryCount shouldBe 2
            fixture.replacementBytes.toList() shouldBe FILE_BYTES.toList()
        }

        test(
            "given an external create after a cached miss when the provider uniquifies then the foreign target stays protected",
        ) {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWithOccupiedRequestedName()
            fixture.gateway.stat(
                TREE_URI,
                WorkspaceTarget.Relative("warmup.rec"),
                SnapshotDigestMode.METADATA_ONLY,
            ) shouldBe null

            shouldThrow<PlatformActionAccessException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )
            }.code shouldBe "platform_postcondition_mismatch"
            fixture.remainingCreatedDocuments shouldBe emptySet()
            fixture.foreignDocumentDeleteCount shouldBe 0
            fixture.createdDocumentRenameCount shouldBe 0
        }

        test("given a nameless create receipt when the write completes then the unverified allocation is rolled back") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubNamelessCreate()
            shouldThrow<PlatformActionAccessException> {
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.CREATE,
                    mimeType = "text/markdown",
                    arbitrate = { null },
                )
            }
            fixture.createdDocumentDeleteCount shouldBe 1
        }

        test("given a committed create when the next stat runs then the write-populated memo answers it") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubCreateWrite()

            fixture.gateway.writeFromExchange(
                treeUri = TREE_URI,
                path = FILE_NAME,
                bytes = FILE_BYTES,
                mode = WriteMode.CREATE,
                mimeType = "text/markdown",
                arbitrate = { null },
            )
            val snapshot =
                fixture.gateway.stat(
                    treeUri = TREE_URI,
                    target = WorkspaceTarget.Relative(FILE_NAME),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )

            snapshot?.documentId shouldBe DOCUMENT_ID
            // The one live listing the create walked; the stat resolves the memoized child.
            fixture.childrenQueryCount shouldBe 1
        }

        test("given arbitration is already satisfied when a write replays then no mutation or readback opens") {
            val fixture = ResolverFixture(FILE_BYTES)
            fixture.stubSingleFileWrite()

            val outcome =
                fixture.gateway.writeFromExchange(
                    treeUri = TREE_URI,
                    path = FILE_NAME,
                    bytes = FILE_BYTES,
                    mode = WriteMode.REPLACE,
                    mimeType = "text/markdown",
                    arbitrate = { existing ->
                        existing?.let {
                            ActionOutcome.AlreadySatisfied(
                                PlatformActionOutput.WriteComplete(it.toMetadata()),
                            )
                        }
                    },
                )

            outcome.shouldBeInstanceOf<WriteOutcome.Satisfied>()
            // The single content observation the arbitration ran against; nothing else opens.
            fixture.inputStreamOpenCount shouldBe 1
        }

        test("given an unbound offset cursor when children are listed then the boundary rejects it") {
            val fixture = ResolverFixture(FILE_BYTES)
            val failure = shouldThrow<PlatformActionAccessException> {
                fixture.gateway.listChildren(TREE_URI, WorkspaceTarget.Root, "1", 16u)
            }
            failure.code shouldBe "invalid_page_cursor"
            fixture.childrenQueryCount shouldBe 0
        }
    }
}

private class ResolverFixture(private val fileBytes: ByteArray) {
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
    var rootChildrenQueryCount: Int = 0
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
        // Unit tests cannot produce a real ParcelFileDescriptor: the provider rejects rwt and
        // every write takes the two-pass stream pair. The single-descriptor path is proven on device.
        every { resolver.openFileDescriptor(any(), any()) } returns null
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

    fun stubAbsentDocument() {
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
            absentLookup()
        }
    }

    /**
     * Directory create flow: the leaf segment misses findChild, is created under the root
     * document, and the tail observation queries the created document id directly.
     */
    fun stubDirectoryCreate(dirName: String, dirDocId: String) {
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

    /** A root listing of many siblings plus a per-document metadata answer for each row. */
    fun stubSiblingDocuments(rows: List<ListingRow>) {
        stubListing(listingCursor(rows))
        rows.forEach { row ->
            val docUri = mockk<Uri>()
            every {
                DocumentsContract.buildDocumentUriUsingTree(rootUri, row.id)
            } returns docUri
            every {
                resolver.query(docUri, any<Array<String>>(), null, null, null)
            } returns documentSnapshotCursor(row)
        }
    }

    /**
     * The root listing answers absent once, then finds the document — the all-live miss needs
     * no second verification pass, so the second stat re-lists live and hits the new document.
     */
    fun stubDocumentAppearingAfterAbsence() {
        var childQueries = 0
        val foundLookup = lookupCursor()
        every {
            resolver.query(childrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            childQueries += 1
            if (childQueries <= 1) absentLookup() else foundLookup
        }
        every {
            resolver.query(documentUri, any<Array<String>>(), null, null, null)
        } returns documentCursor(includeDisplayName = false)
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

    var replacementBytes: ByteArray = fileBytes.copyOf()
        private set

    fun stubRepeatedReplacements() {
        every { resolver.query(childrenUri, any<Array<String>>(), null, null, null) } answers {
            childrenQueryCount += 1
            lookupCursor()
        }
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } answers {
            documentCursor(includeDisplayName = false)
        }
        every { resolver.openInputStream(documentUri) } answers {
            inputStreamOpenCount += 1
            ByteArrayInputStream(replacementBytes)
        }
        every { resolver.openOutputStream(documentUri, "wt") } answers {
            object : ByteArrayOutputStream() {
                override fun close() {
                    replacementBytes = toByteArray()
                    super.close()
                }
            }
        }
    }

    fun renameReplacementLeaf() {
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } answers {
            documentCursor(includeDisplayName = false, name = RENAMED_NAME)
        }
        every { resolver.query(childrenUri, any<Array<String>>(), null, null, null) } answers {
            childrenQueryCount += 1
            absentLookup()
        }
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
        every { resolver.openOutputStream(documentUri, "wt") } returns null
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            createdDocumentDeleteCount += 1
            true
        }
    }

    /**
     * Create flow whose provider sanitizes the requested display name at createDocument time.
     * The write receipt first observes the sanitized name, then (after the repair rename)
     * the honored name — the same document query answers both observations.
     */
    fun stubCreateWithDisplayName(initialName: String, repairedName: String?) {
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
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } returnsMany
            listOf(
                documentCursor(includeDisplayName = false, name = initialName),
                documentCursor(includeDisplayName = false, name = repairedName ?: initialName),
            )
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
    private val allocatedDocuments = mutableSetOf<String>()
    val remainingCreatedDocuments: Set<String> get() = allocatedDocuments.toSet()
    var foreignDocumentDeleteCount = 0
        private set

    fun stubCreateWithOccupiedRequestedName() {
        var childQueries = 0
        every { resolver.query(childrenUri, any<Array<String>>(), null, null, null) } answers {
            childQueries += 1
            if (childQueries == 1) absentLookup() else lookupCursor()
        }
        val parent = mockk<Uri>()
        val created = mockk<Uri>()
        val createdId = "opaque-uniquified-create"
        every { DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID) } returns parent
        every { DocumentsContract.createDocument(resolver, parent, any<String>(), FILE_NAME) } answers {
            allocatedDocuments += createdId
            created
        }
        every { DocumentsContract.getDocumentId(created) } returns createdId
        every { resolver.query(created, any<Array<String>>(), null, null, null) } returns
            documentCursor(includeDisplayName = false, name = "2026_08_06 (1).md")
        every { resolver.openOutputStream(created, "wt") } returns ByteArrayOutputStream()
        every { resolver.openInputStream(created) } answers { ByteArrayInputStream(fileBytes) }
        every { DocumentsContract.deleteDocument(resolver, created) } answers {
            allocatedDocuments -= createdId
            createdDocumentDeleteCount += 1
            true
        }
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            foreignDocumentDeleteCount += 1
            true
        }
    }

    fun stubNamelessCreate() {
        stubCreateWrite()
        val receipt = documentCursor(includeDisplayName = false)
        every { receipt.getString(1) } returns null
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } returns receipt
        every { DocumentsContract.deleteDocument(resolver, documentUri) } answers {
            createdDocumentDeleteCount += 1
            true
        }
    }

    fun stubIndependentCreates(names: List<String>) {
        every { resolver.query(childrenUri, any<Array<String>>(), null, null, null) } answers {
            childrenQueryCount += 1
            absentLookup()
        }
        every { DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID) } returns documentUri
        names.forEachIndexed { index, name ->
            val created = mockk<Uri>()
            val id = "opaque-created-$index"
            every { DocumentsContract.createDocument(resolver, documentUri, any<String>(), name) } returns created
            every { DocumentsContract.getDocumentId(created) } returns id
            every { DocumentsContract.buildDocumentUriUsingTree(rootUri, id) } returns created
            every { resolver.query(created, any<Array<String>>(), null, null, null) } returns
                documentCursor(includeDisplayName = false, name = name)
            every { resolver.openOutputStream(created, "wt") } returns ByteArrayOutputStream()
            every { resolver.openInputStream(created) } answers { ByteArrayInputStream(fileBytes) }
        }
    }

    /**
     * Create flow for a correctly-named file: the write lands under the resolved root, the
     * receipt observes the requested name, and later stats resolve the created document
     * straight from the listing memo the write populated.
     */
    fun stubCreateWrite() {
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
            absentLookup()
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
        every { resolver.openOutputStream(documentUri, "wt") } returns ByteArrayOutputStream()
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } returns
            documentCursor(includeDisplayName = false)
    }

    /**
     * Deep create under an existing `subdir` directory: the root listing holds the directory,
     * its children listing is empty, and the file is created under the resolved parent id.
     */
    fun stubDeepCreate() {
        val dirChildrenUri = mockk<Uri>()
        val dirUri = mockk<Uri>()
        every {
            resolver.query(childrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            dirRowLookup()
        }
        every {
            DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        } returns dirChildrenUri
        every {
            resolver.query(dirChildrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            absentLookup()
        }
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        } returns dirUri
        every {
            DocumentsContract.createDocument(
                resolver,
                dirUri,
                "text/markdown",
                FILE_NAME,
            )
        } returns documentUri
        every { resolver.query(dirUri, any<Array<String>>(), null, null, null) } answers {
            directoryCursor(DIR_DOCUMENT_ID, DIR_NAME)
        }
        every { resolver.openOutputStream(documentUri, "wt") } returns ByteArrayOutputStream()
        every { resolver.query(documentUri, any<Array<String>>(), null, null, null) } returns
            documentCursor(includeDisplayName = false)
    }

    fun renameDeepParent() {
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        every { resolver.query(dirUri, any<Array<String>>(), null, null, null) } answers {
            directoryCursor(DIR_DOCUMENT_ID, "renamed")
        }
        every { resolver.query(childrenUri, any<Array<String>>(), null, null, null) } answers {
            childrenQueryCount += 1
            absentLookup()
        }
        every { DocumentsContract.buildDocumentUriUsingTree(rootUri, ROOT_DOCUMENT_ID) } returns rootUri
        every {
            DocumentsContract.createDocument(resolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, DIR_NAME)
        } throws java.io.IOException("replacement directory refused")
    }

    fun removeDeepParent() {
        renameDeepParent()
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        every { resolver.query(dirUri, any<Array<String>>(), null, null, null) } throws
            java.io.FileNotFoundException("cached directory was removed")
    }

    /**
     * A `subdir` directory under the root whose children listing always answers absent —
     * used to pin the memo-consulted miss re-verification rule.
     */
    fun stubDirListingWithLeafMiss(ancestorName: String = DIR_NAME) {
        val dirChildrenUri = mockk<Uri>()
        val dirUri = mockk<Uri>()
        every {
            resolver.query(childrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            rootChildrenQueryCount += 1
            dirRowLookup()
        }
        every {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        } returns dirUri
        every { resolver.query(dirUri, any<Array<String>>(), null, null, null) } answers {
            directoryCursor(DIR_DOCUMENT_ID, ancestorName)
        }
        every {
            DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, DIR_DOCUMENT_ID)
        } returns dirChildrenUri
        every {
            resolver.query(dirChildrenUri, any<Array<String>>(), null, null, null)
        } answers {
            childrenQueryCount += 1
            absentLookup()
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
        } answers {
            childrenQueryCount += 1
            absentLookup()
        }
    }

    private fun absentLookup(): Cursor = mockk<Cursor>().also { cursor ->
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        } returns 0
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } returns 1
        // Full-projection callers (listChildren) index the same cursor.
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        } returns 2
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        } returns 3
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        } returns 4
        every { cursor.moveToNext() } returns false
        every { cursor.close() } returns Unit
    }

    private fun displayNameCursor(name: String): Cursor = mockk<Cursor>().also { cursor ->
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns name
        every { cursor.close() } returns Unit
    }

    private fun stubDisplayNameQueries(initialName: String, repairedName: String?) {
        val displayNameProjection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        // The write receipt already carries the provider-observed name, so the repair only
        // re-queries the name once — to verify the rename honored the requested name.
        every { resolver.query(documentUri, displayNameProjection, null, null, null) } returns
            displayNameCursor(repairedName ?: initialName)
    }

    private fun lookupCursor(): Cursor = mockk<Cursor>().also { cursor ->
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

    private fun documentCursor(includeDisplayName: Boolean, name: String = FILE_NAME): Cursor =
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
            if (includeDisplayName) {
                every { cursor.moveToNext() } returnsMany listOf(true, false)
            } else {
                every { cursor.moveToFirst() } returns true
            }
            every { cursor.getString(0) } returns DOCUMENT_ID
            every { cursor.getString(1) } returns name
            every { cursor.getString(2) } returns "text/markdown"
            every { cursor.getLong(3) } returns fileBytes.size.toLong()
            every { cursor.getLong(4) } returns 1_754_300_000_000L
            every { cursor.close() } returns Unit
        }

    private fun dirRowLookup(): Cursor = mockk<Cursor>().also { cursor ->
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        } returns 0
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } returns 1
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getString(0) } returns DIR_DOCUMENT_ID
        every { cursor.getString(1) } returns DIR_NAME
        every { cursor.close() } returns Unit
    }

    private fun directoryCursor(docId: String, name: String): Cursor = mockk<Cursor>().also { cursor ->
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

    private fun documentSnapshotCursor(row: ListingRow): Cursor = mockk<Cursor>().also { cursor ->
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
        every { cursor.getString(0) } returns row.id
        every { cursor.getString(1) } returns row.name
        every { cursor.getString(2) } returns row.mime
        every { cursor.getLong(3) } returns row.size
        every { cursor.getLong(4) } returns row.lastModified
        every { cursor.close() } returns Unit
    }

    private fun movedDocumentCursor(): Cursor = mockk<Cursor>().also { cursor ->
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

private fun listingCursor(rows: List<ListingRow>): Cursor = FakeCursor(
    ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
    rows.map { row -> listOf(row.id, row.name, row.mime, row.size, row.lastModified) },
)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val TREE_URI = "content://com.lomo.documents/tree/primary%3ALomo"
private const val ROOT_DOCUMENT_ID = "primary:Lomo"
private const val DOCUMENT_ID = "primary:Lomo/2026_08_06.md"
private const val FILE_NAME = "2026_08_06.md"
private const val DIR_DOCUMENT_ID = "primary:Lomo/subdir"
private const val DIR_NAME = "subdir"
private const val MOVED_DOCUMENT_ID = "primary:Lomo/renamed.md"
private const val RENAMED_NAME = "renamed.md"
private val FILE_BYTES = "# memo\n\nbody".encodeToByteArray()
