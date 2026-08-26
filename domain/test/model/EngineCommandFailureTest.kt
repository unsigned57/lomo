/*
 * Behavior Contract:
 * - Unit under test: EngineCommandFailure / EngineCommandFailureException / engine failure vocabulary.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: one structured engine rejection survives every boundary with its code, category,
 *   retry disposition and diagnostic intact, so no layer has to fall back to a blank message.
 *
 * Scenarios:
 * - Given a Rust rejection with a code and diagnostic, when it is carried as an exception, then the
 *   exception message is non-blank and names both the code and the diagnostic.
 * - Given a blank code, when a failure is constructed, then construction is rejected.
 * - Given each stable wire category/retry token, when parsed, then it maps to the typed value and
 *   round-trips back to the same token.
 * - Given an unrecognized wire token, when parsed leniently, then the result is null so the caller
 *   can preserve the original failure instead of losing it.
 *
 * Observable outcomes: constructor rejection, exception message text, parsed enum values.
 * TDD proof: fails before EngineCommandFailure exists; previously a Rust rejection reached the UI as
 *   an exception with a null message and every code was erased.
 * Excludes: FFI conversion, presentation strings, Rust-side error construction.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EngineCommandFailureTest : DomainFunSpec() {
    init {
        test("given a rust rejection when carried as an exception then the message names code and diagnostic") {
            val failure =
                EngineCommandFailure(
                    category = EngineFailureCategory.CONFLICT,
                    code = "stale_snapshot",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    operationId = "op-1",
                    jobId = null,
                    diagnostic = "Memo projection changed before the workspace mutation began",
                )

            val exception = EngineCommandFailureException(failure)

            exception.failure shouldBe failure
            val message = exception.message.orEmpty()
            message.shouldContain("stale_snapshot")
            message.shouldContain("Memo projection changed before the workspace mutation began")
        }

        test("given a rejection without a diagnostic when carried as an exception then the code still surfaces") {
            val exception =
                EngineCommandFailureException(
                    EngineCommandFailure(
                        category = EngineFailureCategory.INTERNAL,
                        code = "trash_marker_missing",
                        retryDisposition = EngineRetryDisposition.NEVER,
                        operationId = null,
                        jobId = null,
                        diagnostic = "",
                    ),
                )

            exception.message.orEmpty().shouldContain("trash_marker_missing")
        }

        test("given a blank code when a failure is constructed then it is rejected") {
            shouldThrow<IllegalArgumentException> {
                EngineCommandFailure(
                    category = EngineFailureCategory.VALIDATION,
                    code = "  ",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    operationId = null,
                    jobId = null,
                    diagnostic = "anything",
                )
            }
        }

        test("given each stable wire token when parsed then it round-trips") {
            EngineFailureCategory.entries.forEach { category ->
                EngineFailureCategory.fromWireOrNull(category.wireValue) shouldBe category
            }
            EngineRetryDisposition.entries.forEach { disposition ->
                EngineRetryDisposition.fromWireOrNull(disposition.wireValue) shouldBe disposition
            }
            EngineFailureCategory.fromWireOrNull("corruption") shouldBe EngineFailureCategory.CORRUPTION
            EngineRetryDisposition.fromWireOrNull("after_user_action") shouldBe
                EngineRetryDisposition.AFTER_USER_ACTION
        }

        test("given an unrecognized wire token when parsed leniently then the result is null") {
            EngineFailureCategory.fromWireOrNull("teapot").shouldBeNull()
            EngineRetryDisposition.fromWireOrNull("maybe").shouldBeNull()
        }
    }
}
