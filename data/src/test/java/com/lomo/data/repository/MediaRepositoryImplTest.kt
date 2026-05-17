/*
 * Test Contract:
 * - Unit under test: MediaRepositoryImpl
 * - Behavior focus: cached media location updates, workspace fallback behavior, and S3 local change journal recording for image and voice mutations.
 * - Observable outcomes: returned storage locations, emitted image-location maps, null workspace results on directory failures, and recorder invocations for S3-visible media changes.
 * - Red phase: Fails before the fix because media imports and voice capture mutations do not record S3 local journal entries, so the new recorder assertions stay red.
 * - Excludes: FileDataSource backend internals, platform URI parsing behavior, and downstream S3 executor reconciliation.
 */
package com.lomo.data.repository


import android.net.Uri
import com.lomo.data.local.dao.ImageLocationCacheDao
import com.lomo.data.local.entity.ImageLocationCacheEntity
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.StorageRootType
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class MediaRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("refreshImageLocations emits file-backed image map") { `refreshImageLocations emits file-backed image map`() }

        test("refreshImageLocations clears map when image root is missing") { `refreshImageLocations clears map when image root is missing`() }

        test("importImage updates cached image map incrementally") { `importImage updates cached image map incrementally`() }

        test("removeImage removes cached entry incrementally") { `removeImage removes cached entry incrementally`() }

        test("importImage records image upsert in s3 local journal") { `importImage records image upsert in s3 local journal`() }

        test("voice capture lifecycle records s3 journal mutations") { `voice capture lifecycle records s3 journal mutations`() }

        test("ensureCategoryWorkspace returns null when image directory creation fails") { `ensureCategoryWorkspace returns null when image directory creation fails`() }

        test("ensureCategoryWorkspace returns null when voice directory creation fails") { `ensureCategoryWorkspace returns null when voice directory creation fails`() }
    }


    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var s3LocalChangeRecorder: S3LocalChangeRecorder

    @MockK(relaxed = true)
    private lateinit var webDavLocalChangeRecorder: WebDavLocalChangeRecorder

    @MockK(relaxed = true)
    private lateinit var imageLocationCacheDao: ImageLocationCacheDao

    private lateinit var repository: MediaRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        coEvery { imageLocationCacheDao.readAll() } returns emptyList<ImageLocationCacheEntity>()
        coEvery { imageLocationCacheDao.clearAll() } returns Unit
        coEvery { imageLocationCacheDao.upsertAll(any()) } returns Unit
        repository =
            MediaRepositoryImpl(
                workspaceConfigSource = dataSource,
                mediaStorageDataSource = dataSource,
                s3LocalChangeRecorder = s3LocalChangeRecorder,
                webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                imageLocationCacheDao = imageLocationCacheDao,
            )
    }

    private fun `refreshImageLocations emits file-backed image map`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf("content://images")
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "uri://keep",
                    "new.jpg" to "uri://new",
                )

            repository.refreshImageLocations()

            val map = repository.observeImageLocations().first()
            map shouldBe mapOf(
                    MediaEntryId("keep.jpg") to StorageLocation("uri://keep"),
                    MediaEntryId("new.jpg") to StorageLocation("uri://new"),
                )
        }

    private fun `refreshImageLocations clears map when image root is missing`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf(null)

            repository.refreshImageLocations()

            repository.observeImageLocations().first() shouldBe emptyMap<MediaEntryId, StorageLocation>()
        }

    private fun `importImage updates cached image map incrementally`() =
        runTest {
            val source = StorageLocation("content://source/image")
            val sourceUri = mockk<Uri>()
            mockkStatic(Uri::class)
            try {
                every { Uri.parse(source.raw) } returns sourceUri
                coEvery { dataSource.saveImage(sourceUri) } returns "new.jpg"
                coEvery { dataSource.getImageLocation("new.jpg") } returns "content://images/new.jpg"

                val saved = repository.importImage(source)

                saved shouldBe StorageLocation("new.jpg")
                repository.observeImageLocations().first() shouldBe mapOf(MediaEntryId("new.jpg") to StorageLocation("content://images/new.jpg"))
                coVerify(exactly = 0) { dataSource.listImageFiles() }
            } finally {
                unmockkStatic(Uri::class)
            }
        }

    private fun `removeImage removes cached entry incrementally`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf("content://images")
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "content://images/keep.jpg",
                    "drop.jpg" to "content://images/drop.jpg",
                )
            repository.refreshImageLocations()

            repository.removeImage(MediaEntryId("drop.jpg"))

            repository.observeImageLocations().first() shouldBe mapOf(MediaEntryId("keep.jpg") to StorageLocation("content://images/keep.jpg"))
            coVerify { dataSource.deleteImage("drop.jpg") }
            coVerify(exactly = 1) { dataSource.listImageFiles() }
        }

    private fun `importImage records image upsert in s3 local journal`() =
        runTest {
            val source = StorageLocation("content://source/image")
            val sourceUri = mockk<Uri>()
            mockkStatic(Uri::class)
            try {
                every { Uri.parse(source.raw) } returns sourceUri
                coEvery { dataSource.saveImage(sourceUri) } returns "new.jpg"
                coEvery { dataSource.getImageLocation("new.jpg") } returns "content://images/new.jpg"

                repository.importImage(source)

                coVerify(exactly = 1) { s3LocalChangeRecorder.recordImageUpsert("new.jpg") }
            } finally {
                unmockkStatic(Uri::class)
            }
        }

    private fun `voice capture lifecycle records s3 journal mutations`() =
        runTest {
            val targetUri = mockk<Uri>()
            every { targetUri.toString() } returns "content://voice/voice_1.m4a"
            coEvery { dataSource.createVoiceFile("voice_1.m4a") } returns targetUri

            val allocated = repository.allocateVoiceCaptureTarget(MediaEntryId("voice_1.m4a"))
            repository.removeVoiceCapture(MediaEntryId("voice_1.m4a"))

            allocated shouldBe StorageLocation("content://voice/voice_1.m4a")
            coVerify(exactly = 1) { s3LocalChangeRecorder.recordVoiceUpsert("voice_1.m4a") }
            coVerify(exactly = 1) { s3LocalChangeRecorder.recordVoiceDelete("voice_1.m4a") }
        }

    private fun `ensureCategoryWorkspace returns null when image directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("images") } throws IllegalStateException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.IMAGE)

            result.shouldBeNull()
            coVerify(exactly = 0) { dataSource.setRoot(StorageRootType.IMAGE, any()) }
        }

    private fun `ensureCategoryWorkspace returns null when voice directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("voice") } throws IllegalArgumentException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.VOICE)

            result.shouldBeNull()
            coVerify(exactly = 0) { dataSource.setRoot(StorageRootType.VOICE, any()) }
        }
}
