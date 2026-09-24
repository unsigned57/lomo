package com.lomo.app.feature.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorCode

object ShareErrorPresenter {
    @Composable
    fun message(
        error: ShareTransferError,
        isTechnicalMessage: (String) -> Boolean,
    ): String =
        when (error.code) {
            ShareTransferErrorCode.PAIRING_REQUIRED -> {
                stringResource(R.string.lan_pairing_required)
            }

            ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED -> {
                stringResource(R.string.share_error_attachment_resolve_failed)
            }

            ShareTransferErrorCode.TOO_MANY_ATTACHMENTS,
            ShareTransferErrorCode.ATTACHMENT_TOO_LARGE,
            ShareTransferErrorCode.ATTACHMENTS_TOO_LARGE,
            -> {
                stringResource(R.string.share_error_attachment_too_large)
            }

            ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE -> {
                stringResource(R.string.share_error_unsupported_attachment_type)
            }

            ShareTransferErrorCode.CONNECTION_FAILED -> {
                stringResource(
                    R.string.share_error_connection_failed,
                    detail(error.detail.orEmpty(), isTechnicalMessage),
                )
            }

            ShareTransferErrorCode.TRANSFER_REJECTED -> {
                val deviceName = error.deviceName?.trim().orEmpty()
                if (deviceName.isNotBlank()) {
                    stringResource(R.string.share_error_transfer_rejected_by, deviceName)
                } else {
                    stringResource(R.string.share_error_transfer_rejected)
                }
            }

            ShareTransferErrorCode.PEER_REVOKED -> {
                stringResource(R.string.share_error_peer_revoked)
            }

            ShareTransferErrorCode.AUTHENTICATION_FAILED -> {
                stringResource(R.string.share_error_authentication_failed)
            }

            ShareTransferErrorCode.APPROVAL_EXPIRED -> {
                stringResource(R.string.share_error_approval_expired)
            }

            ShareTransferErrorCode.STORAGE_FAILED -> {
                stringResource(R.string.share_error_storage_failed)
            }

            ShareTransferErrorCode.TRANSFER_CANCELLED -> {
                stringResource(R.string.share_error_transfer_cancelled)
            }

            ShareTransferErrorCode.PROTOCOL_FAILED -> {
                stringResource(R.string.share_error_transfer_failed)
            }
        }

    @Composable
    fun detail(
        detailRaw: String,
        isTechnicalMessage: (String) -> Boolean,
    ): String {
        val detail = detailRaw.trim()
        return if (detail.isBlank() || isTechnicalMessage(detail)) {
            stringResource(R.string.share_error_unknown)
        } else {
            detail
        }
    }
}
