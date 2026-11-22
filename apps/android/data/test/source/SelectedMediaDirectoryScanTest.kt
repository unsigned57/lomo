package com.lomo.data.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.engine.ContentResolverPlatformDocumentsGateway
import com.lomo.data.testing.fakes.FakeCursor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: media scans honor a selected SAF child directory; owner: data; priority: P0.
 * Scenarios:
 * - Given a selected directory inside a broader tree grant containing unrelated files,
 *   when images are scanned or media playback resolves, then only the selected directory answers.
 * Observable outcomes: exact relative names and provider locations; absence for outside files.
 * TDD proof: target/lomo/reports/manual/directory-interaction-bugs/lomo-media-scan-red.log returns outside.png from the grant root;
 * GREEN: target/lomo/reports/manual/directory-interaction-bugs/lomo-bugs-data-green.log.
 * Audio child RED: target/lomo/reports/manual/directory-interaction-bugs/lomo-audio-child-red.log
 * returns null instead of the selected provider URI; the same data GREEN covers both adapters.
 * Excludes: Rust media classification (stubbed boundary) and real provider permission checks.
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
class SelectedMediaDirectoryScanTest :
    FunSpec({
        afterTest {
            unmockkStatic(Uri::class, DocumentsContract::class, DocumentFile::class)
            unmockkStatic("com.lomo.data.source.MediaStorageClassificationKt")
        }
        test("given a selected SAF child when scanning then the parent grant cannot supply images") {
            runTest {
                with(SelectedMediaDirectoryFixture()) {
                    val backend = SafMediaStorageBackendDelegate(SafDocumentAccess(context, selected))
                    backend.listImageFiles() shouldBe listOf("inside.png" to inside.toString())
                    backend.getImageLocation("inside.png") shouldBe inside.toString()
                    backend.getImageLocation("outside.png") shouldBe null
                    val player = com.lomo.data.media.AudioPlaybackUriResolverImpl(
                        context,
                        { com.lomo.data.media.audioMount(null, raw) },
                    )
                    player.resolve("voice/inside.png") shouldBe inside.toString()
                    player.resolve("voice/outside.png") shouldBe null
                }
            }
        }
    })

private class SelectedMediaDirectoryFixture {
    val raw = "content://provider/tree/parent/document/parent%2Fimages"
    val selected = mockk<Uri>()
    val inside = mockk<Uri>()
    val context = mockk<Context>()

    init {
        val outside = mockk<Uri>()
        val children = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val parent = mockk<DocumentFile>()
        val unrelated = mockk<DocumentFile>()
        mockkStatic(Uri::class, DocumentsContract::class, DocumentFile::class)
        mockkStatic("com.lomo.data.source.MediaStorageClassificationKt")
        every { isImageAttachmentFileName(any()) } returns true
        every { selected.toString() } returns raw
        every { Uri.parse(raw) } returns selected
        every { inside.toString() } returns "content://provider/tree/parent/document/opaque-image"
        every { outside.toString() } returns "content://provider/tree/parent/document/outside"
        every { context.contentResolver } returns resolver
        every { DocumentsContract.getDocumentId(selected) } returns "parent/images"
        every { DocumentsContract.getTreeDocumentId(selected) } returns "parent"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(selected, "parent/images") } returns children
        every { DocumentsContract.buildDocumentUriUsingTree(selected, "opaque-image") } returns inside
        every { resolver.query(children, any<Array<String>>(), null, null, null) } answers {
            FakeCursor(
                ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
                listOf(
                    listOf("opaque-image", "inside.png", "image/png", 3L, 1L),
                ),
            )
        }
        every { resolver.query(inside, any<Array<String>>(), null, null, null) } answers {
            FakeCursor(
                ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
                listOf(
                    listOf("opaque-image", "inside.png", "image/png", 3L, 1L),
                ),
            )
        }
        // AndroidX fromTreeUri re-roots at the grant. This models the original wrong lane.
        every { DocumentFile.fromTreeUri(context, selected) } returns parent
        every { parent.listFiles() } returns arrayOf(unrelated)
        every { unrelated.name } returns "outside.png"
        every { unrelated.isFile } returns true
        every { unrelated.uri } returns outside
        every { parent.findFile("inside.png") } returns null
        every { parent.findFile("outside.png") } returns unrelated
    }
}
