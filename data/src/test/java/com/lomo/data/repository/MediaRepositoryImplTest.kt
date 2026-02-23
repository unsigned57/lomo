package com.lomo.data.repository

import com.lomo.data.local.dao.ImageCacheDao
import com.lomo.data.local.entity.ImageCacheEntity
import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class MediaRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var imageCacheDao: ImageCacheDao

    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    private lateinit var repository: MediaRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = MediaRepositoryImpl(imageCacheDao, dataSource)
    }

    @Test
    fun `syncImageCache applies differential insert and delete`() =
        runTest {
            coEvery { dataSource.getImageRootFlow() } returns flowOf("content://images")
            coEvery { imageCacheDao.getAllImagesSync() } returns
                listOf(
                    ImageCacheEntity(filename = "keep.jpg", uriString = "uri://keep", lastModified = 1L),
                    ImageCacheEntity(filename = "old.jpg", uriString = "uri://old", lastModified = 2L),
                )
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "uri://keep-new",
                    "new.jpg" to "uri://new",
                )

            repository.syncImageCache()

            coVerify(exactly = 1) { imageCacheDao.deleteByFilenames(listOf("old.jpg")) }
            coVerify(exactly = 1) {
                imageCacheDao.insertImages(
                    match { images ->
                        images.size == 1 &&
                            images.first().filename == "new.jpg" &&
                            images.first().uriString == "uri://new"
                    },
                )
            }
        }
}
