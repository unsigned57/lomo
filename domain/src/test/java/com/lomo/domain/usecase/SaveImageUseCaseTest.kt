package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveImageUseCaseTest {
    private val mediaRepository: MediaRepository = mockk()
    private val useCase = SaveImageUseCase(mediaRepository)

    @Test
    fun `saveWithCacheSyncStatus returns success when both save and cache sync succeed`() =
        runTest {
            val source = StorageLocation("uri")
            val saved = StorageLocation("/images/a.jpg")
            coEvery { mediaRepository.importImage(source) } returns saved
            coEvery { mediaRepository.refreshImageLocations() } returns Unit

            val result = useCase.saveWithCacheSyncStatus(source)

            assertEquals(SaveImageResult.SavedAndCacheSynced(saved), result)
        }

    @Test
    fun `saveWithCacheSyncStatus returns partial result when cache sync fails`() =
        runTest {
            val source = StorageLocation("uri")
            val saved = StorageLocation("/images/a.jpg")
            val failure = IllegalStateException("cache sync failed")
            coEvery { mediaRepository.importImage(source) } returns saved
            coEvery { mediaRepository.refreshImageLocations() } throws failure

            val result = useCase.saveWithCacheSyncStatus(source)

            assertTrue(result is SaveImageResult.SavedButCacheSyncFailed)
            val partial = result as SaveImageResult.SavedButCacheSyncFailed
            assertEquals(saved, partial.location)
            assertSame(failure, partial.cause)
        }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy invoke rethrows cache sync failure for compatibility`() =
        runTest {
            val source = StorageLocation("uri")
            val saved = StorageLocation("/images/a.jpg")
            val failure = IllegalStateException("cache sync failed")
            coEvery { mediaRepository.importImage(source) } returns saved
            coEvery { mediaRepository.refreshImageLocations() } throws failure

            val thrown = runCatching { useCase("uri") }.exceptionOrNull()

            assertSame(failure, thrown)
        }

    @Test
    fun `saveWithCacheSyncStatus rethrows save failure and skips cache sync`() =
        runTest {
            val source = StorageLocation("uri")
            val failure = IllegalArgumentException("invalid source")
            coEvery { mediaRepository.importImage(source) } throws failure

            val thrown = runCatching { useCase.saveWithCacheSyncStatus(source) }.exceptionOrNull()

            assertSame(failure, thrown)
            coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
        }

    @Test
    fun `saveWithCacheSyncStatus rethrows cancellation from cache sync`() =
        runTest {
            val source = StorageLocation("uri")
            val saved = StorageLocation("/images/a.jpg")
            val cancellation = CancellationException("cancelled")
            coEvery { mediaRepository.importImage(source) } returns saved
            coEvery { mediaRepository.refreshImageLocations() } throws cancellation

            val thrown = runCatching { useCase.saveWithCacheSyncStatus(source) }.exceptionOrNull()

            assertSame(cancellation, thrown)
        }
}
