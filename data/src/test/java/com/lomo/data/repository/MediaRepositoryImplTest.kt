package com.lomo.data.repository

import android.net.Uri
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    private lateinit var repository: MediaRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = MediaRepositoryImpl(workspaceConfigSource = dataSource, mediaStorageDataSource = dataSource)
    }

    @Test
    fun `refreshImageLocations emits file-backed image map`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf("content://images")
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "uri://keep",
                    "new.jpg" to "uri://new",
                )

            repository.refreshImageLocations()

            val map = repository.observeImageLocations().first()
            assertEquals(
                mapOf(
                    MediaEntryId("keep.jpg") to StorageLocation("uri://keep"),
                    MediaEntryId("new.jpg") to StorageLocation("uri://new"),
                ),
                map,
            )
        }

    @Test
    fun `refreshImageLocations clears map when image root is missing`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf(null)

            repository.refreshImageLocations()

            assertEquals(emptyMap<MediaEntryId, StorageLocation>(), repository.observeImageLocations().first())
        }

    @Test
    fun `importImage updates cached image map incrementally`() =
        runTest {
            val source = StorageLocation("content://source/image")
            val sourceUri = mockk<Uri>()
            mockkStatic(Uri::class)
            try {
                every { Uri.parse(source.raw) } returns sourceUri
                coEvery { dataSource.saveImage(sourceUri) } returns "new.jpg"
                coEvery { dataSource.getImageLocation("new.jpg") } returns "content://images/new.jpg"

                val saved = repository.importImage(source)

                assertEquals(StorageLocation("new.jpg"), saved)
                assertEquals(
                    mapOf(MediaEntryId("new.jpg") to StorageLocation("content://images/new.jpg")),
                    repository.observeImageLocations().first(),
                )
                coVerify(exactly = 0) { dataSource.listImageFiles() }
            } finally {
                unmockkStatic(Uri::class)
            }
        }

    @Test
    fun `removeImage removes cached entry incrementally`() =
        runTest {
            coEvery { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf("content://images")
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "content://images/keep.jpg",
                    "drop.jpg" to "content://images/drop.jpg",
                )
            repository.refreshImageLocations()

            repository.removeImage(MediaEntryId("drop.jpg"))

            assertEquals(
                mapOf(MediaEntryId("keep.jpg") to StorageLocation("content://images/keep.jpg")),
                repository.observeImageLocations().first(),
            )
            coVerify { dataSource.deleteImage("drop.jpg") }
            coVerify(exactly = 1) { dataSource.listImageFiles() }
        }

    @Test
    fun `ensureCategoryWorkspace returns null when image directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("images") } throws IllegalStateException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.IMAGE)

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setRoot(StorageRootType.IMAGE, any()) }
        }

    @Test
    fun `ensureCategoryWorkspace returns null when voice directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("voice") } throws IllegalArgumentException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.VOICE)

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setRoot(StorageRootType.VOICE, any()) }
        }
}
