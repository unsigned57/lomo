package com.lomo.data.repository

import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `syncImageCache emits file-backed image map`() =
        runTest {
            coEvery { dataSource.getImageRootFlow() } returns flowOf("content://images")
            coEvery { dataSource.listImageFiles() } returns
                listOf(
                    "keep.jpg" to "uri://keep",
                    "new.jpg" to "uri://new",
                )

            repository.syncImageCache()

            val map = repository.getImageUriMap().first()
            assertEquals(mapOf("keep.jpg" to "uri://keep", "new.jpg" to "uri://new"), map)
        }

    @Test
    fun `syncImageCache clears map when image root is missing`() =
        runTest {
            coEvery { dataSource.getImageRootFlow() } returns flowOf(null)

            repository.syncImageCache()

            assertEquals(emptyMap<String, String>(), repository.getImageUriMap().first())
        }
}
