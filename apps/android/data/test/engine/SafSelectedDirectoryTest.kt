package com.lomo.data.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.fakes.FakeCursor
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

/*
 * Behavior Contract:
 * Capability: a selected directory document stays the capability root inside its broader tree grant; owner: data; P0.
 * Scenarios:
 * - Given a child-directory document URI, when stat or listing targets Root, then only the child is addressed.
 * - Given a selected child and its parent grant, when identity is derived, then the child has its own directory identity.
 * Observable outcomes: observed document id, enumerated parent id, and canonical identity equality.
 * TDD proof: target/lomo/reports/manual/four-bugs/saf-child-red.log observes parent instead of parent/images; GREEN uses the selected document id.
 * Excludes: real provider permissions and AndroidX DocumentFile implementation.
 * Test Change Justification:
 * Reason category: behavior-preserving organization of Android boundary fixtures.
 * Old behavior/assertion being replaced: inline platform stubbing; scenario names and outcomes stay unchanged.
 * Why old assertion is no longer correct: existing outcomes remain correct; platform setup belongs
 * in a scenario fixture, and successful validation/broadcast delivery needs an explicit outcome assertion.
 * Coverage preserved by: the same selected-directory, validation, or widget routing scenarios,
 * with all former success and rejection expectations retained.
 * Why this is not fitting the test to the implementation: fixtures expose provider results or
 * recorded broadcast payloads; production code and the behavior contract are unchanged.
 * Regression preservation: initial CI verification passed app 952/data 855 tests before this
 * fixture cleanup. After cleanup, data passed 855 tests and the widget dispatch target passed
 * all six scenarios; the final CI gate rechecks the complete workspace.
 * Evidence: reports/manual/directory-tidy-2026-11-14/test-fixtures.stdout.log and widget-fixture.stdout.log.
 */
class SafSelectedDirectoryTest :
    FunSpec({
        afterTest {
            unmockkStatic(Uri::class)
            unmockkStatic(DocumentsContract::class)
        }
        test("given a selected child document when observing root then the parent tree is never substituted") {
            with(SafSelectedDirectoryFixture()) {
                gateway.stat(
                    raw,
                    WorkspaceTarget.Root,
                    SnapshotDigestMode.METADATA_ONLY,
                )?.documentId shouldBe "parent/images"
                gateway.listChildren(
                    raw,
                    WorkspaceTarget.Root,
                    null,
                    64u,
                ).items.map { it.displayName } shouldBe listOf("inside.png")
            }
        }
        test(
            "given a selected directory document when identifying the capability then its own document id is canonical",
        ) {
            SafWorkspaceIdentity.fromTreeUri("content://provider/tree/parent/document/parent%2Fimages") shouldBe
                SafWorkspaceIdentity.fromTreeUri("content://provider/tree/parent%2Fimages")
        }
    })

private class SafSelectedDirectoryFixture {
    val raw = "content://provider/tree/parent/document/parent%2Fimages"
    val gateway: ContentResolverPlatformDocumentsGateway

    init {
        val root = mockk<Uri>()
        val parent = mockk<Uri>()
        val child = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val selectedChildren = mockk<Uri>()
        val parentChildren = mockk<Uri>()
        val imageUri = mockk<Uri>()
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        every { Uri.parse(raw) } returns root
        every { DocumentsContract.getTreeDocumentId(root) } returns "parent"
        every { DocumentsContract.getDocumentId(root) } returns "parent/images"
        every { DocumentsContract.buildDocumentUriUsingTree(root, "parent") } returns parent
        every { DocumentsContract.buildDocumentUriUsingTree(root, "parent/images") } returns child
        every { DocumentsContract.buildChildDocumentsUriUsingTree(root, "parent/images") } returns selectedChildren
        every { DocumentsContract.buildChildDocumentsUriUsingTree(root, "parent") } returns parentChildren
        every { DocumentsContract.buildDocumentUriUsingTree(root, "parent/images/inside.png") } returns imageUri
        every { resolver.query(any<Uri>(), any<Array<String>>(), null, null, null) } answers {
            val row = when (firstArg<Uri>()) {
                selectedChildren -> listOf("parent/images/inside.png", "inside.png", "image/png", 3L, 1L)
                parentChildren -> listOf("parent/outside.png", "outside.png", "image/png", 3L, 1L)
                child -> listOf("parent/images", "images", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1L)
                else -> listOf("parent", "notes", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1L)
            }
            FakeCursor(ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION, listOf(row))
        }
        gateway = ContentResolverPlatformDocumentsGateway(resolver)
    }
}
