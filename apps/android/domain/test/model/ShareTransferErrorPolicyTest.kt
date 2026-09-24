package com.lomo.domain.model

/**
 * Behavior Contract:
 * Capability: ShareTransferErrorPolicy
 * Scenarios: Given canonical error construction and structured engine rejections, when the policy
 * maps them, then each cause lands on a distinct presentation bucket with the wire facts kept.
 * Observable outcomes: emitted ShareTransferError code/detail/device/engineCode/retryDisposition
 * fields for each policy entrypoint.
 * TDD proof: Fails before the structured mapping exists.
 * Excludes: UI string presentation and LAN transport behavior.
 *
 * Test Change Justification:
 * Reason category: Behavior change (T44/B14 contract shrink)
 * Old behavior/assertion being replaced: TRANSFER_FAILED/UNKNOWN buckets collapsed every engine
 * rejection into one undifferentiated presentation with only a free-text detail.
 * Why old assertion is no longer correct: authenticated category/code/disposition must survive to
 * the domain error so the presenter can localize distinct causes.
 * Coverage preserved by: per-category mapping assertions plus wire-refusal classification.
 * Why this is not fitting the test to the implementation: the buckets are the audit-named causes
 * (peer revoked, approval expired, authentication failure, storage failure), not impl leaks.
 */

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

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
        }

        test("connection and generic policies trim detail while preserving null") {
            ShareTransferErrorPolicy.connectionFailed("  network timeout  ") shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.CONNECTION_FAILED,
                    detail = "network timeout",
                )
            ShareTransferErrorPolicy.protocolFailed("Pixel", "  opaque ") shouldBe ShareTransferError(
                    code = ShareTransferErrorCode.PROTOCOL_FAILED,
                    detail = "opaque",
                    deviceName = "Pixel",
                )
            ShareTransferErrorPolicy.connectionFailed(null).detail shouldBe null
            ShareTransferErrorPolicy.protocolFailed(null, null).detail shouldBe null
        }

        test("engine failures keep wire code and disposition while mapping to distinct buckets") {
            fun failure(
                category: EngineFailureCategory,
                code: String,
                diagnostic: String = "diag",
            ) = EngineCommandFailure(
                category = category,
                code = code,
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                operationId = null,
                jobId = null,
                diagnostic = diagnostic,
            )

            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.AUTHENTICATION, "lan_peer_revoked"),
                "Pixel",
            ) shouldBe ShareTransferError(
                code = ShareTransferErrorCode.PEER_REVOKED,
                detail = "diag",
                deviceName = "Pixel",
                engineCode = "lan_peer_revoked",
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            )
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.PERMISSION, "lan_approval_expired"),
                null,
            ).code shouldBe ShareTransferErrorCode.APPROVAL_EXPIRED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.AUTHENTICATION, "lan_session_signature_invalid"),
                null,
            ).code shouldBe ShareTransferErrorCode.AUTHENTICATION_FAILED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.STORAGE, "lan_journal_write_failed"),
                null,
            ).code shouldBe ShareTransferErrorCode.STORAGE_FAILED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.CORRUPTION, "lan_record_checksum_mismatch"),
                null,
            ).code shouldBe ShareTransferErrorCode.STORAGE_FAILED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.NETWORK, "lan_connect_failed"),
                null,
            ).code shouldBe ShareTransferErrorCode.CONNECTION_FAILED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.CANCELLED, "lan_session"),
                null,
            ).code shouldBe ShareTransferErrorCode.TRANSFER_CANCELLED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.PERMISSION, "lan_batch_rejected"),
                null,
            ).code shouldBe ShareTransferErrorCode.TRANSFER_REJECTED
            ShareTransferErrorPolicy.fromEngineFailure(
                failure(EngineFailureCategory.INTERNAL, "lan_unknown_future_code"),
                null,
            ) shouldBe ShareTransferError(
                code = ShareTransferErrorCode.PROTOCOL_FAILED,
                detail = "diag",
                engineCode = "lan_unknown_future_code",
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            )
        }

        test("wire refusal codes classify into buckets without losing the raw code") {
            ShareTransferErrorPolicy.refusal("lan_batch_rejected", "Pixel") shouldBe ShareTransferError(
                code = ShareTransferErrorCode.TRANSFER_REJECTED,
                detail = "lan_batch_rejected",
                deviceName = "Pixel",
                engineCode = "lan_batch_rejected",
            )
            ShareTransferErrorPolicy.refusal("lan_peer_revoked", null).code shouldBe
                ShareTransferErrorCode.PEER_REVOKED
            ShareTransferErrorPolicy.refusal("lan_approval_ttl_invalid", null).code shouldBe
                ShareTransferErrorCode.APPROVAL_EXPIRED
            ShareTransferErrorPolicy.refusal("lan_pairing_expired", null).code shouldBe
                ShareTransferErrorCode.PAIRING_REQUIRED
            ShareTransferErrorPolicy.refusal("lan_item_body_incomplete", null) shouldBe ShareTransferError(
                code = ShareTransferErrorCode.PROTOCOL_FAILED,
                detail = "lan_item_body_incomplete",
                engineCode = "lan_item_body_incomplete",
            )
        }
    }
}
