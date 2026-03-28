package com.lomo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareTransferErrorPolicy
 * - Behavior focus: canonical error construction for pairing, attachment, connection, transfer, and unknown failure paths.
 * - Observable outcomes: emitted ShareTransferError code/detail/device fields for each policy entrypoint.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: UI string presentation and LAN transport behavior.
 */
class ShareTransferErrorPolicyTest {
    @Test
    fun `policy builders preserve expected codes and payload fields`() {
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.PAIRING_REQUIRED),
            ShareTransferErrorPolicy.pairingRequiredBeforeSend(),
        )
        assertEquals(
            ShareTransferError(
                code = ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED,
                missingAttachmentCount = 2,
            ),
            ShareTransferErrorPolicy.missingAttachments(2),
        )
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.TOO_MANY_ATTACHMENTS),
            ShareTransferErrorPolicy.tooManyAttachments(),
        )
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.ATTACHMENT_TOO_LARGE),
            ShareTransferErrorPolicy.attachmentTooLarge(),
        )
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.ATTACHMENTS_TOO_LARGE),
            ShareTransferErrorPolicy.attachmentsTooLarge(),
        )
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE),
            ShareTransferErrorPolicy.unsupportedAttachmentType(),
        )
        assertEquals(
            ShareTransferError(
                code = ShareTransferErrorCode.TRANSFER_REJECTED,
                deviceName = "Pixel",
            ),
            ShareTransferErrorPolicy.transferRejected("Pixel"),
        )
        assertEquals(
            ShareTransferError(code = ShareTransferErrorCode.TRANSFER_FAILED),
            ShareTransferErrorPolicy.transferFailed(),
        )
    }

    @Test
    fun `connection and unknown policies trim detail while preserving null`() {
        assertEquals(
            ShareTransferError(
                code = ShareTransferErrorCode.CONNECTION_FAILED,
                detail = "network timeout",
            ),
            ShareTransferErrorPolicy.connectionFailed("  network timeout  "),
        )
        assertEquals(
            ShareTransferError(
                code = ShareTransferErrorCode.UNKNOWN,
                detail = "opaque",
            ),
            ShareTransferErrorPolicy.unknown("  opaque "),
        )
        assertNull(ShareTransferErrorPolicy.connectionFailed(null).detail)
        assertNull(ShareTransferErrorPolicy.unknown(null).detail)
    }
}
