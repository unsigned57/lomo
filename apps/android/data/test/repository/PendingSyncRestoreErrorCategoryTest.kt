package com.lomo.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import java.io.IOException

/*
 * Behavior Contract:
 * - Unit under test: toPendingSyncRestoreError (Throwable classification)
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: classify pending-sync restore throwables deterministically into
 *   PendingSyncRestoreErrorCategory names and formatted diagnostics without losing causal chains.
 * - Production scope: apps/android/data/src/repository/PendingSyncRestoreErrorCategory.kt
 * - Scenarios:
 *   - Given a SerializationException, when classified even if its message mentions HTTP, then the
 *     category is SERIALIZATION_FAILED.
 *   - Given throwables whose primary or cause messages indicate 403/auth credentials, corrupt
 *     metadata/database, or remote network/timeout IO, when classified, then credential beats
 *     metadata which beats remote IO.
 *   - Given a plain IOException or IllegalStateException/IllegalArgumentException, when classified,
 *     then the category is LOCAL_IO_FAILED or CONTRACT_VIOLATION respectively.
 *   - Given an unknown throwable with blank messages across the causal chain, when classified,
 *     then the category is UNKNOWN and the message falls back to "UNKNOWN: <className>".
 * - Observable outcomes: PendingSyncRestoreError.category, formatted message, and preserved cause.
 * - TDD proof: locks PendingSyncRestoreErrorCategory classification precedence that previously had
 *   zero host test coverage.
 * - Excludes: upstream sync executor throwables (covered by sync contract tests).
 */
class PendingSyncRestoreErrorCategoryTest :
    FunSpec({
        test("given serialization exception mentioning http when classified then serialization wins") {
            val error = SerializationException("http body trailing garbage")

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.SERIALIZATION_FAILED.name
            result.message shouldBe "http body trailing garbage"
            result.cause shouldBe error
        }

        test("given credential text in nested cause when classified then credential category wins") {
            val error = IOException(
                "network metadata sync aborted",
                IllegalStateException("HTTP 403 Forbidden credential denied"),
            )

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED.name
            result.message shouldBe "network metadata sync aborted"
        }

        test("given metadata failure mentioning connection when classified then metadata beats remote io") {
            val error = IOException("database metadata header truncated after connection drop")

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.METADATA_FAILED.name
        }

        test("given remote timeout io exception when classified then category is remote io failed") {
            val error = IOException("socket timed out while reading endpoint")

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.REMOTE_IO_FAILED.name
        }

        test("given local disk io exception without remote cues when classified then category is local io") {
            val error = IOException("ENOENT on scratch journal")

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.LOCAL_IO_FAILED.name
        }

        test("given contract violation exception when classified then category is contract violation") {
            val argumentResult = IllegalArgumentException("missing workspace key").toPendingSyncRestoreError()
            val stateResult = IllegalStateException("corrupt lease transition").toPendingSyncRestoreError()

            argumentResult.category shouldBe PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION.name
            stateResult.category shouldBe PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION.name
        }

        test("given unknown exception with blank message when classified then category and fallback format apply") {
            val error = UnsupportedOperationException("   ")

            val result = error.toPendingSyncRestoreError()

            result.category shouldBe PendingSyncRestoreErrorCategory.UNKNOWN.name
            result.message shouldBe "UNKNOWN: java.lang.UnsupportedOperationException"
        }
    })
