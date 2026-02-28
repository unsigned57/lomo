package com.lomo.data.repository

import com.lomo.data.source.FileDataSource
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

    @Test
    fun `createDefaultImageDirectory returns null when directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("images") } throws IllegalStateException("boom")

            val result = repository.createDefaultImageDirectory()

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setImageRoot(any()) }
        }

    @Test
    fun `createDefaultVoiceDirectory returns null when directory creation fails`() =
        runTest {
            coEvery { dataSource.createDirectory("voice") } throws IllegalArgumentException("boom")

            val result = repository.createDefaultVoiceDirectory()

            assertNull(result)
            coVerify(exactly = 0) { dataSource.setVoiceRoot(any()) }
        }
}
