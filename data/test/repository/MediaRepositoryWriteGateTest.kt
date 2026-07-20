package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: MediaRepositoryImpl write hard gate.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: media mutations fail closed unless EngineReadiness is Ready and writes are not frozen.
 *
 * Scenarios:
 * - Given ReadOnlyRecovery, when importImage is requested, then no storage write runs.
 * - Given write freeze, when importImage is requested, then no storage write runs.
 * - Given Ready, when importImage is requested, then storage write runs.
 *
 * Observable outcomes: exception messages and storage data-source invocation counts.
 * TDD proof: fails before MediaRepositoryImpl requires Ready + freeze.
 * Excludes: S3/WebDAV journal semantics and full image pipeline.
 */

import android.net.Uri
import com.lomo.data.local.dao.ImageLocationCacheDao
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest

class MediaRepositoryWriteGateTest : DataFunSpec() {
    init {
        test("given non-Ready engine when importImage runs then write fails closed") {
            runTest {
                val mediaStorage = mockk<MediaStorageDataSource>(relaxed = true)
                val repository =
                    MediaRepositoryImpl(
                        workspaceConfigSource = mockk(relaxed = true),
                        mediaStorageDataSource = mediaStorage,
                        s3LocalChangeRecorder = mockk(relaxed = true),
                        webDavLocalChangeRecorder = mockk(relaxed = true),
                        imageLocationCacheDao = mockk(relaxed = true),
                        writeAuthority =
                            WorkspaceWriteAuthority(
                                engineReadinessRepository = FakeEngineReadinessRepository(
                                EngineReadiness.ReadOnlyRecovery(
                                category = EngineReadiness.FailureCategory.PERMISSION,
                                code = "saf_grant_revoked",
                                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                                diagnostic = "revoked",
                                ),
                                ),
                                writeFreezeRepository = ProcessWriteFreezeRepository(),
                            ),
                    )

                val error =
                    shouldThrow<IllegalStateException> {
                        repository.importImage(StorageLocation("content://source/image"))
                    }
                error.message.shouldContain("read-only recovery")
                coVerify(exactly = 0) { mediaStorage.saveImage(any()) }
            }
        }

        test("given write freeze when importImage runs then write fails closed") {
            runTest {
                val freeze = ProcessWriteFreezeRepository()
                freeze.begin()
                val mediaStorage = mockk<MediaStorageDataSource>(relaxed = true)
                val repository =
                    MediaRepositoryImpl(
                        workspaceConfigSource = mockk(relaxed = true),
                        mediaStorageDataSource = mediaStorage,
                        s3LocalChangeRecorder = mockk(relaxed = true),
                        webDavLocalChangeRecorder = mockk(relaxed = true),
                        imageLocationCacheDao = mockk(relaxed = true),
                        writeAuthority =
                            WorkspaceWriteAuthority(
                                engineReadinessRepository = FakeEngineReadinessRepository(),
                                writeFreezeRepository = freeze,
                            ),
                    )

                val error =
                    shouldThrow<IllegalStateException> {
                        repository.importImage(StorageLocation("content://source/image"))
                    }
                error.message.shouldContain("switch is in progress")
                coVerify(exactly = 0) { mediaStorage.saveImage(any()) }
            }
        }

        test("given Ready engine when importImage runs then storage write proceeds") {
            runTest {
                val mediaStorage = mockk<MediaStorageDataSource>()
                val sourceUri = mockk<Uri>()
                mockkStatic(Uri::class)
                try {
                    every { Uri.parse("content://source/image") } returns sourceUri
                    coEvery { mediaStorage.saveImage(sourceUri) } returns "new.jpg"
                    coEvery { mediaStorage.getImageLocation("new.jpg") } returns "content://images/new.jpg"
                    val repository =
                        MediaRepositoryImpl(
                            workspaceConfigSource = mockk<WorkspaceConfigSource>(relaxed = true),
                            mediaStorageDataSource = mediaStorage,
                            s3LocalChangeRecorder = mockk(relaxed = true),
                            webDavLocalChangeRecorder = mockk(relaxed = true),
                            imageLocationCacheDao = mockk<ImageLocationCacheDao>(relaxed = true),
                            writeAuthority =
                                WorkspaceWriteAuthority(
                                    engineReadinessRepository = FakeEngineReadinessRepository(),
                                    writeFreezeRepository = ProcessWriteFreezeRepository(),
                                ),
                        )

                    repository.importImage(StorageLocation("content://source/image")) shouldBe
                        StorageLocation("new.jpg")
                    coVerify(exactly = 1) { mediaStorage.saveImage(sourceUri) }
                } finally {
                    unmockkStatic(Uri::class)
                }
            }
        }
    }
}
