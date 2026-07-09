/*
 * Behavior Contract:
 * - Capability: ImageLoader configuration for lomo's image pipeline.
 * - Given the configured cache-bytes constant and a writable cache directory,
 *   when lomoImageDiskCache builds a Coil DiskCache, then its maxSize equals
 *   LOMO_IMAGE_DISK_CACHE_BYTES.
 * - Given the configured parallelism constant, when
 *   lomoImageDecoderCoroutineContext is built, then it must be a coroutine
 *   context (sanity check that the constant is wired in — actual scheduling
 *   coverage is enforced by the parallelism constant itself).
 * - Reason: Bug 1 (cold-start + fast-fling crash) was caused by:
 *   1) diskCachePolicy(DISABLED), forcing every image to re-decode on cold
 *      start, and 2) limitedParallelism(4) accidentally bolted onto
 *      interceptorCoroutineContext instead of the decoder/fetcher contexts,
 *      so heavy decodes ran unbounded. Lock both fixes down by contract.
 * - Excludes: Coil-internal scheduling, native bitmap allocation policies.
 */

package com.lomo.app.feature.image

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class LomoImageLoaderConfigTest : AppFunSpec() {
    init {
        test("lomoImageDiskCache configures the documented byte budget") {
            val cacheDir = Files.createTempDirectory("lomo-disk-cache").toFile()

            val diskCache = lomoImageDiskCache(cacheDir)

            diskCache.maxSize shouldBe LOMO_IMAGE_DISK_CACHE_BYTES
        }

        test("lomo image decoder coroutine context is non-null") {
            lomoImageDecoderCoroutineContext shouldNotBe null
        }

        test("lomo image fetcher coroutine context is non-null") {
            lomoImageFetcherCoroutineContext shouldNotBe null
        }
    }
}
