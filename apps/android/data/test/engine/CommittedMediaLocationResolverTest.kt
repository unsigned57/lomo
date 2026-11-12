package com.lomo.data.engine

import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.StorageLocation
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * - Unit under test: CommittedMediaLocationResolver; owning layer: data platform adapter; P1.
 * - Capability: Direct and SAF media locations retain exact file/provider identity.
 * Scenarios:
 * - Given a Direct file with spaces or hash, when resolved, then a canonical file URI round-trips without a fragment.
 * - Given an opaque provider document id, when a committed path resolves, then the URI uses that
 *   id under the active tree and requests metadata only.
 * - Given a missing committed document, when resolved, then no location is fabricated.
 * - Observable outcomes: resolved StorageLocation or absence; gateway evidence mode.
 * - TDD proof: repository RED could publish no SAF location at all; this locks its platform edge.
 * - URI TDD proof: committed-uri-red.log throws URISyntaxException for the raw file address;
 *   committed-uri-green.log passes with canonical URI encoding (target/lomo/reports/manual/media-toolbar-search/).
 * Test Change Justification:
 * Reason category: path identity regression coverage.
 * Old behavior/assertion being replaced: no existing SAF assertions are changed.
 * Why old assertion is no longer correct: existing assertions remain correct; Direct URI coverage was missing.
 * Coverage preserved by: both SAF cases and the new filesystem round-trip case.
 * Why this is not fitting the test to the implementation: the expected decoded path is the actual created file.
 * - Excludes: Rust commit authority, image decoding, full provider traversal.
 */
class CommittedMediaLocationResolverTest : DataFunSpec() {
    init {
        afterTest {
            unmockkStatic(Uri::class)
            unmockkStatic(DocumentsContract::class)
        }

        test(
            "given a Direct path containing spaces and hash when resolved then its URI preserves the exact file identity",
        ) {
            val root = createTempDirectory(prefix = "lomo media ").toFile()
            try {
                val file = File(root, "media/photo #1.png").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeBytes(byteArrayOf(1))
                }
                val resolver = CommittedMediaLocationResolver({ StorageLocation(root.absolutePath) }, mockk(), { null })
                val raw = requireNotNull(resolver.resolve("media/photo #1.png")).raw
                java.net.URI(raw).path shouldBe file.absolutePath
                java.net.URI(raw).fragment shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        test("given opaque SAF document identity when committed media resolves then the observed URI is returned") {
            val tree = "content://provider/tree/root"
            val location = "content://provider/tree/root/document/opaque-42"
            val treeUri = mockk<Uri>()
            val finalUri = mockk<Uri>()
            mockkStatic(Uri::class)
            mockkStatic(DocumentsContract::class)
            every { Uri.parse(tree) } returns treeUri
            every { DocumentsContract.buildDocumentUriUsingTree(treeUri, "opaque-42") } returns finalUri
            every { finalUri.toString() } returns location
            val gateway = mockk<PlatformDocumentsGateway>()
            every {
                gateway.stat(
                    tree,
                    WorkspaceTarget.Relative("media/photo.png"),
                    SnapshotDigestMode.METADATA_ONLY,
                )
            } returns
                PlatformDocumentSnapshot(
                    target = WorkspaceTarget.Relative("media/photo.png"),
                    kind = DocumentKind.FILE,
                    mimeType = "image/png",
                    length = 10uL,
                    lastModifiedEpochMillis = 1L,
                    documentId = "opaque-42",
                    digest = null,
                )

            CommittedMediaLocationResolver(
                { StorageLocation(tree) },
                gateway,
                { null },
            ).resolve("media/photo.png") shouldBe
                StorageLocation(location)
        }

        test("given missing SAF media when its committed path resolves then no URI is fabricated") {
            val tree = "content://provider/tree/root"
            val gateway = mockk<PlatformDocumentsGateway>()
            every {
                gateway.stat(
                    tree,
                    WorkspaceTarget.Relative("media/missing.png"),
                    SnapshotDigestMode.METADATA_ONLY,
                )
            } returns null

            CommittedMediaLocationResolver(
                { StorageLocation(tree) },
                gateway,
                { null },
            ).resolve("media/missing.png") shouldBe null
        }
    }
}
