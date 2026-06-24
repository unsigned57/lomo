package com.lomo.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: resolveLocalS3FilesForPaths
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: per-path local stat resolution for incremental sync candidates runs with bounded
 *   concurrency instead of serializing one storage round-trip at a time, while preserving the
 *   path-to-snapshot mapping and dropping missing files.
 *
 * Scenarios:
 * - Given two candidate paths whose stats each suspend until the other lookup has started, when stats
 *   are resolved, then both lookups overlap and the resolved map keeps every found file by path.
 * - Given a path whose stat resolves to null (missing file), when stats are resolved, then the path
 *   is absent from the result.
 *
 * Observable outcomes: resolved path-to-LocalS3File map and lookup overlap (a serial implementation
 * deadlocks under virtual time and fails the run).
 *
 * TDD proof:
 * - Fails (UncompletedCoroutinesError under runTest virtual time) when stats resolve serially, because
 *   the first lookup waits for the second to start; candidatePaths.associateWith { localFile(...) }
 *   was the prior serial shape.
 *
 * Excludes: SAF transport details, fingerprint computation, and planner semantics.
 */
class S3LocalStatResolutionTest : FunSpec({
    test("given per-path stats that await each other when resolution runs then lookups overlap and results keep found files") {
        runTest {
            val appleStarted = CompletableDeferred<Unit>()
            val bananaStarted = CompletableDeferred<Unit>()

            val resolved =
                resolveLocalS3FilesForPaths(setOf("memo/apple.md", "memo/banana.md", "memo/missing.md")) { path ->
                    when (path) {
                        "memo/apple.md" -> {
                            appleStarted.complete(Unit)
                            bananaStarted.await()
                            LocalS3File(path = path, lastModified = 1L, size = 1L)
                        }

                        "memo/banana.md" -> {
                            bananaStarted.complete(Unit)
                            appleStarted.await()
                            LocalS3File(path = path, lastModified = 2L, size = 2L)
                        }

                        else -> null
                    }
                }

            resolved shouldContainExactly
                mapOf(
                    "memo/apple.md" to LocalS3File(path = "memo/apple.md", lastModified = 1L, size = 1L),
                    "memo/banana.md" to LocalS3File(path = "memo/banana.md", lastModified = 2L, size = 2L),
                )
        }
    }
})
