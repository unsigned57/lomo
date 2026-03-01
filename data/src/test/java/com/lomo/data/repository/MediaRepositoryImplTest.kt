package com.lomo.data.repository

import com.lomo.data.source.FileDataSource
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
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
        repository = MediaRepositoryImpl(dataSource)
    }

    @Test
    fun `refreshImageLocations emits file-backed image map`() =
        runTest {
            coEvery { dataSource.getImageRootFlow() } returns flowOf("content://images")
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
            coEvery { dataSource.getImageRootFlow() } returns flowOf(null)

            repository.refreshImageLocations()

            assertEquals(emptyMap<MediaEntryId, StorageLocation>(), repository.observeImageLocations().first())
        }

    @Test
    fun `ensureCategoryWorkspace returns null when image directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("images") } throws IllegalStateException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.IMAGE)

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setImageRoot(any()) }
        }

    @Test
    fun `ensureCategoryWorkspace returns null when voice directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("voice") } throws IllegalArgumentException("boom")

            val result = repository.ensureCategoryWorkspace(MediaCategory.VOICE)

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setVoiceRoot(any()) }
        }
}
