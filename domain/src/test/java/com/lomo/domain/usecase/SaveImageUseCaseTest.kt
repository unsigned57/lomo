package com.lomo.domain.usecase

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
            coEvery { mediaRepository.saveImage("uri") } returns "/images/a.jpg"
            coEvery { mediaRepository.syncImageCache() } returns Unit

            val result = useCase.saveWithCacheSyncStatus("uri")

            assertEquals(SaveImageResult.SavedAndCacheSynced("/images/a.jpg"), result)
        }

    @Test
    fun `saveWithCacheSyncStatus returns partial result when cache sync fails`() =
        runTest {
            val failure = IllegalStateException("cache sync failed")
            coEvery { mediaRepository.saveImage("uri") } returns "/images/a.jpg"
            coEvery { mediaRepository.syncImageCache() } throws failure

            val result = useCase.saveWithCacheSyncStatus("uri")

            assertTrue(result is SaveImageResult.SavedButCacheSyncFailed)
            val partial = result as SaveImageResult.SavedButCacheSyncFailed
            assertEquals("/images/a.jpg", partial.path)
            assertSame(failure, partial.cause)
        }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy invoke rethrows cache sync failure for compatibility`() =
        runTest {
            val failure = IllegalStateException("cache sync failed")
            coEvery { mediaRepository.saveImage("uri") } returns "/images/a.jpg"
            coEvery { mediaRepository.syncImageCache() } throws failure

            val thrown = runCatching { useCase("uri") }.exceptionOrNull()

            assertSame(failure, thrown)
        }

    @Test
    fun `saveWithCacheSyncStatus rethrows save failure and skips cache sync`() =
        runTest {
            val failure = IllegalArgumentException("invalid source")
            coEvery { mediaRepository.saveImage("uri") } throws failure

            val thrown = runCatching { useCase.saveWithCacheSyncStatus("uri") }.exceptionOrNull()

            assertSame(failure, thrown)
            coVerify(exactly = 0) { mediaRepository.syncImageCache() }
        }

    @Test
    fun `saveWithCacheSyncStatus rethrows cancellation from cache sync`() =
        runTest {
            val cancellation = CancellationException("cancelled")
            coEvery { mediaRepository.saveImage("uri") } returns "/images/a.jpg"
            coEvery { mediaRepository.syncImageCache() } throws cancellation

            val thrown = runCatching { useCase.saveWithCacheSyncStatus("uri") }.exceptionOrNull()

            assertSame(cancellation, thrown)
        }
}
