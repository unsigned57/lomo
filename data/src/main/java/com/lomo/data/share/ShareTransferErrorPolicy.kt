package com.lomo.data.share

import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorCode

internal object ShareTransferErrorPolicy {
    fun pairingRequiredBeforeSend(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.PAIRING_REQUIRED)

    fun missingAttachments(missingCount: Int): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED,
            missingAttachmentCount = missingCount,
        )

    fun tooManyAttachments(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.TOO_MANY_ATTACHMENTS)

    fun attachmentTooLarge(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.ATTACHMENT_TOO_LARGE)

    fun attachmentsTooLarge(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.ATTACHMENTS_TOO_LARGE)

    fun unsupportedAttachmentType(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE)

    fun connectionFailed(detail: String?): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.CONNECTION_FAILED, detail = detail?.trim())

    fun transferRejected(deviceName: String): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.TRANSFER_REJECTED, deviceName = deviceName)

    fun transferFailed(): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.TRANSFER_FAILED)

    fun unknown(detail: String?): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.UNKNOWN, detail = detail?.trim())
}
