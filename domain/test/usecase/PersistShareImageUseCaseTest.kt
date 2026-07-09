/*
 * Behavior Contract:
 * - Unit under test: PersistShareImageUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: persist rendered share images through a streaming writer contract.
 *
 * Scenarios:
 * - Given a share image writer, when persistence runs with the default prefix, then the writer is
 *   invoked and the bytes it emits are stored by the repository.
 * - Given a custom prefix, when persistence runs, then the same prefix is forwarded with the
 *   streamed bytes.
 * - Given the repository returns a storage path, when persistence completes, then that path is
 *   returned to the caller.
 * - Given callers should not need to materialize PNG bytes first, when the use case contract is
 *   exercised, then the observable path is a writer rather than a ByteArray payload.
 *
 * Observable outcomes:
 * - Returned storage path, repository invocation prefix, writer invocation count, and stored bytes.
 *
 * TDD proof:
 * - Fails before the fix because PersistShareImageUseCase only accepts a ByteArray payload and has
 *   no writer-based invoke contract.
 *
 * Excludes:
 * - Bitmap encoding details, filesystem persistence, FileProvider URI exposure, and cache cleanup.
 */

package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeShareImageRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class PersistShareImageUseCaseTest : DomainFunSpec() {
    private val repository = FakeShareImageRepository()
    private val useCase = PersistShareImageUseCase(repository)

    init {
        test("given share image writer when invoked with default prefix then streamed bytes are stored") {
            runTest {
                val bytes = byteArrayOf(1, 2, 3)
                var writerCallCount = 0
                repository.nextPath = "/tmp/memo_share.png"

                val result =
                    useCase {
                        writerCallCount += 1
                        it.write(bytes)
                    }

                result shouldBe "/tmp/memo_share.png"
                writerCallCount shouldBe 1
                repository.storedImages.single().pngBytes.toList() shouldBe bytes.toList()
                repository.storedImages.single().fileNamePrefix shouldBe "memo_share"
            }
        }

        test("given custom prefix when invoked then prefix is forwarded with streamed bytes") {
            runTest {
                val bytes = byteArrayOf(9, 8, 7)
                var writerCallCount = 0
                repository.nextPath = "/tmp/daily_review.png"

                val result =
                    useCase(fileNamePrefix = "daily_review") {
                        writerCallCount += 1
                        it.write(bytes)
                    }

                result shouldBe "/tmp/daily_review.png"
                writerCallCount shouldBe 1
                repository.storedImages.single().pngBytes.toList() shouldBe bytes.toList()
                repository.storedImages.single().fileNamePrefix shouldBe "daily_review"
            }
        }
    }
}
