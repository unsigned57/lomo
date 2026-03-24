package com.lomo.domain.usecase

import com.lomo.domain.repository.ShareImageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: PersistShareImageUseCase
 * - Behavior focus: share-image persistence keeps the byte payload and caller-selected filename prefix.
 * - Observable outcomes: returned storage path and repository invocation parameters.
 * - Excludes: image encoding, filesystem persistence, and URI exposure details.
 */
class PersistShareImageUseCaseTest {
    private val repository: ShareImageRepository = mockk()
    private val useCase = PersistShareImageUseCase(repository)

    @Test
    fun `invoke forwards bytes with default prefix`() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3)
            coEvery {
                repository.storeShareImage(bytes, "memo_share")
            } returns "/tmp/memo_share.png"

            val result = useCase(bytes)

            assertEquals("/tmp/memo_share.png", result)
            coVerify(exactly = 1) { repository.storeShareImage(bytes, "memo_share") }
        }

    @Test
    fun `invoke forwards custom prefix to repository`() =
        runTest {
            val bytes = byteArrayOf(9, 8, 7)
            coEvery {
                repository.storeShareImage(bytes, "daily_review")
            } returns "/tmp/daily_review.png"

            val result = useCase(bytes, fileNamePrefix = "daily_review")

            assertEquals("/tmp/daily_review.png", result)
            coVerify(exactly = 1) { repository.storeShareImage(bytes, "daily_review") }
        }
}
