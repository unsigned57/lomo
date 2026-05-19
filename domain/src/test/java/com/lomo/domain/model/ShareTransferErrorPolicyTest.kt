package com.lomo.domain.model

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


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: ShareTransferErrorPolicy
 * - Behavior focus: canonical error construction for pairing, attachment, connection, transfer, and unknown failure paths.
 * - Observable outcomes: emitted ShareTransferError code/detail/device fields for each policy entrypoint.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: UI string presentation and LAN transport behavior.
 */
class ShareTransferErrorPolicyTest : DomainFunSpec() {
    init {
        test("policy builders preserve expected codes and payload fields") {
            ShareTransferErrorPolicy.pairingRequiredBeforeSend() shouldBe ShareTransferError(code = ShareTransferErrorCode.PAIRING_REQUIRED)
            ShareTransferErrorPolicy.missingAttachments(2) shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED,
                    missingAttachmentCount = 2,
                )
            ShareTransferErrorPolicy.tooManyAttachments() shouldBe ShareTransferError(code = ShareTransferErrorCode.TOO_MANY_ATTACHMENTS)
            ShareTransferErrorPolicy.attachmentTooLarge() shouldBe ShareTransferError(code = ShareTransferErrorCode.ATTACHMENT_TOO_LARGE)
            ShareTransferErrorPolicy.attachmentsTooLarge() shouldBe ShareTransferError(code = ShareTransferErrorCode.ATTACHMENTS_TOO_LARGE)
            ShareTransferErrorPolicy.unsupportedAttachmentType() shouldBe ShareTransferError(code = ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE)
            ShareTransferErrorPolicy.transferRejected("Pixel") shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.TRANSFER_REJECTED,
                    deviceName = "Pixel",
                )
            ShareTransferErrorPolicy.transferFailed() shouldBe ShareTransferError(code = ShareTransferErrorCode.TRANSFER_FAILED)
        }

        test("connection and unknown policies trim detail while preserving null") {
            ShareTransferErrorPolicy.connectionFailed("  network timeout  ") shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.CONNECTION_FAILED,
                    detail = "network timeout",
                )
            ShareTransferErrorPolicy.unknown("  opaque ") shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.UNKNOWN,
                    detail = "opaque",
                )
            ShareTransferErrorPolicy.connectionFailed(null).detail shouldBe null
            ShareTransferErrorPolicy.unknown(null).detail shouldBe null
        }
    }
}
