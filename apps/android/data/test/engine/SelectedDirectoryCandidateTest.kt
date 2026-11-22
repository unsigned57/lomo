package com.lomo.data.engine

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.testing.fakes.FakeCursor
import com.lomo.data.testing.fakes.completeDirectoryExtras
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.StorageLocation
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: all storage candidates prove access to the selected directory before switching; owner: data; priority: P0.
 * Scenarios:
 * - Given a writable grant parent and an unavailable selected child, when validated, then the child is rejected.
 * - Given a valid grant and readable metadata but a null child query, when validated, then incomplete enumeration is rejected.
 * - Given a complete empty directory, when validated, then selection succeeds.
 * Observable outcomes: classified rejection or successful validation; no settings writes occur in this boundary.
 * TDD proof: target/lomo/reports/manual/directory-selection/candidate-red.log and verification-green.log.
 * Excludes: recursive Rust import and real provider timing.
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
class SelectedDirectoryCandidateTest :
    FunSpec({
        afterTest { unmockkStatic(Uri::class, DocumentsContract::class, DocumentFile::class) }

        test(
            "given an available grant parent and unavailable selected child when validated then the child is rejected",
        ) {
            runTest {
                val provider = CandidateProviderFixture(childAvailable = false, listingAvailable = true)
                shouldThrow<EngineCommandFailureException> {
                    WorkspaceCandidateProbe(
                        provider.context,
                        com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    ).validate(StorageLocation(CANDIDATE))
                }.failure.code shouldBe "workspace_content_unavailable"
            }
        }
        test("given a null listing when validated then the candidate fails before switching") {
            runTest {
                val provider = CandidateProviderFixture(childAvailable = true, listingAvailable = false)
                shouldThrow<EngineCommandFailureException> {
                    WorkspaceCandidateProbe(
                        provider.context,
                        com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    ).validate(StorageLocation(CANDIDATE))
                }.failure.code shouldBe "metadata_enumeration_incomplete"
            }
        }
        test("given a complete empty selected directory when validated then it is accepted") {
            runTest {
                val provider = CandidateProviderFixture(childAvailable = true, listingAvailable = true)
                shouldNotThrowAny {
                    WorkspaceCandidateProbe(
                        provider.context,
                        com.lomo.domain.usecase.DefaultDispatcherProvider(),
                    ).validate(StorageLocation(CANDIDATE))
                }
            }
        }
        test("given a loading cursor when validated then its rows cannot certify the directory") {
            runTest {
                val extras = mockk<Bundle>()
                every { extras.getBoolean(DocumentsContract.EXTRA_LOADING) } returns true
                val provider = CandidateProviderFixture(childAvailable = true, listingAvailable = true, extras = extras)
                val failure = shouldThrow<EngineCommandFailureException> {
                    WorkspaceCandidateProbe(provider.context, com.lomo.domain.usecase.DefaultDispatcherProvider())
                        .validate(StorageLocation(CANDIDATE))
                }.failure
                failure.code shouldBe "metadata_enumeration_loading"
                failure.retryDisposition shouldBe com.lomo.domain.model.EngineRetryDisposition.TRANSIENT
            }
        }
    })

private const val CANDIDATE = "content://provider/tree/parent/document/selected-child"

private class CandidateProviderFixture(
    childAvailable: Boolean,
    listingAvailable: Boolean,
    extras: Bundle = completeDirectoryExtras,
) {
    val context = mockk<Context>()

    init {
        mockkStatic(Uri::class, DocumentsContract::class, DocumentFile::class)
        val selected = mockk<Uri>()
        val document = mockk<Uri>()
        val children = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val parent = mockk<DocumentFile>()
        val child = mockk<DocumentFile>()
        val grant = mockk<UriPermission>()
        every { Uri.parse(CANDIDATE) } returns selected
        every { selected.toString() } returns CANDIDATE
        every { DocumentsContract.getTreeDocumentId(selected) } returns "parent"
        every { DocumentsContract.getDocumentId(selected) } returns "selected-child"
        every { DocumentsContract.buildDocumentUriUsingTree(selected, "selected-child") } returns document
        every { DocumentsContract.buildChildDocumentsUriUsingTree(selected, "selected-child") } returns children
        every { DocumentFile.fromTreeUri(context, selected) } returns parent
        every { DocumentFile.fromSingleUri(context, document) } returns child
        every { parent.exists() } returns true
        every { parent.isDirectory } returns true
        every { parent.canRead() } returns true
        every { parent.canWrite() } returns true
        every { child.exists() } returns childAvailable
        every { child.isDirectory } returns true
        every { child.canRead() } returns true
        every { child.canWrite() } returns true
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } returns listOf(grant)
        every { grant.uri } returns selected
        every { grant.isReadPermission } returns true
        every { grant.isWritePermission } returns true
        every { resolver.query(children, any<Array<String>>(), null, null, null) } answers {
            if (listingAvailable) {
                FakeCursor(
                    ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
                    emptyList(),
                    extras,
                )
            } else {
                null
            }
        }
    }
}
