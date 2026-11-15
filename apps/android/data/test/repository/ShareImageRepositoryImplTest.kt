/*
 * Behavior Contract:
 * - Unit under test: ShareImageRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: persist rendered share images by streaming writer output into cache files.
 *
 * Scenarios:
 * - Given a share image writer, when the repository stores an image, then the writer is invoked
 *   once with the cache file output stream and the bytes it emits are persisted.
 * - Given a caller-selected prefix, when the image is stored, then the returned cache file uses
 *   that prefix.
 * - Given stale cache entries exist, when a new image is stored, then existing cleanup policy still
 *   runs around the write path.
 * - Given a writer fails after emitting partial bytes, when persistence aborts, then no partial
 *   cache image remains.
 * - Given the persistence contract is streaming, when repository tests compile, then no repository
 *   ByteArray input is required for the primary write path.
 *
 * Observable outcomes:
 * - Writer call count, returned path prefix, bytes read back from the persisted cache file, and
 *   absence of failed partial cache files.
 *
 * TDD proof:
 * - Fails before the fix because ShareImageRepositoryImpl still implements the old ByteArray
 *   contract instead of accepting a writer.
 * - RED: writer-failure cleanup fails before the follow-up because partial final files remain in
 *   the shared image cache directory.
 *
 * Excludes:
 * - Android FileProvider URI creation, bitmap compression, and DI binding.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for ShareImageRepositoryImpl.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including given share image writer when stored then writer
 * bytes are persisted in cache file; given writer fails after partial output when storing image then partial
 * cache file is removed.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */

package com.lomo.data.repository

import android.content.Context
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException

class ShareImageRepositoryImplTest : DataFunSpec() {
    private lateinit var tempFolder: KotestTemporaryFolder
    private lateinit var cacheDir: java.io.File
    private lateinit var repository: ShareImageRepositoryImpl

    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            cacheDir = tempFolder.newFolder("cache")
            val context = mockk<Context>()
            every { context.cacheDir } returns cacheDir
            repository = ShareImageRepositoryImpl(context)
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("given share image writer when stored then writer bytes are persisted in cache file") {
            runTest {
                val expectedBytes = byteArrayOf(4, 5, 6, 7)
                var writerCallCount = 0

                val filePath =
                    repository.storeShareImage(fileNamePrefix = "stream_share") { output ->
                        writerCallCount += 1
                        output.write(expectedBytes)
                    }

                writerCallCount shouldBe 1
                filePath shouldContain "stream_share_"
                java.io.File(filePath).readBytes().toList() shouldContainExactly expectedBytes.toList()
            }
        }

        test("given writer fails after partial output when storing image then partial cache file is removed") {
            runTest {
                val error =
                    shouldThrow<IOException> {
                        repository.storeShareImage(fileNamePrefix = "stream_share") { output ->
                            output.write(byteArrayOf(1, 2, 3))
                            throw IOException("encode failed")
                        }
                    }

                error.message shouldBe "encode failed"
                val sharedCacheFiles =
                    cacheDir
                        .resolve("shared_memos")
                        .listFiles()
                        ?.filter { file -> file.isFile }
                        .orEmpty()
                sharedCacheFiles shouldBe emptyList()
            }
        }
    }
}
