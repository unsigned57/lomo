package com.lomo.app.feature.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorCode

object ShareErrorPresenter {
    @Composable
    fun message(
        error: ShareTransferError,
        isTechnicalMessage: (String) -> Boolean,
    ): String {
        return when (error.code) {
            ShareTransferErrorCode.PAIRING_REQUIRED -> {
                stringResource(R.string.share_error_set_password_first)
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

            ShareTransferErrorCode.TRANSFER_FAILED -> {
                stringResource(R.string.share_error_transfer_failed)
            }

            ShareTransferErrorCode.UNKNOWN -> {
                val detail = error.detail.orEmpty()
                if (detail.isBlank()) {
                    stringResource(R.string.share_error_unknown)
                } else {
                    detail(detail, isTechnicalMessage)
                }
            }
        }
    }

    @Composable
    fun detail(
        detailRaw: String,
        isTechnicalMessage: (String) -> Boolean,
    ): String {
        val detail = detailRaw.trim()
        if (detail.isBlank()) {
            return stringResource(R.string.share_error_unknown)
        }

        return when {
            detail.equals("Please set an end-to-end encryption password first", ignoreCase = true) -> {
                stringResource(R.string.share_error_set_password_first)
            }

            detail.equals("Please set a LAN share pairing code first", ignoreCase = true) -> {
                stringResource(R.string.share_error_set_password_first)
            }

            detail.contains("pairing code is not configured on receiver", ignoreCase = true) -> {
                stringResource(R.string.share_error_receiver_password_not_set)
            }

            detail.equals("Invalid attachment size", ignoreCase = true) -> {
                stringResource(R.string.share_error_invalid_attachment_size)
            }

            detail.equals("Attachment too large", ignoreCase = true) -> {
                stringResource(R.string.share_error_attachment_too_large)
            }

            detail.startsWith("Failed to resolve", ignoreCase = true) -> {
                stringResource(R.string.share_error_attachment_resolve_failed)
            }

            detail.startsWith("Unsupported attachment type", ignoreCase = true) -> {
                stringResource(R.string.share_error_unsupported_attachment_type)
            }

            detail.equals("Device unreachable", ignoreCase = true) -> {
                stringResource(R.string.share_error_device_unreachable)
            }

            LanSharePairingCodePolicy.userMessageKey(detail) ==
                LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE -> {
                stringResource(R.string.share_error_invalid_pairing_code)
            }

            detail.equals("Transfer failed", ignoreCase = true) -> {
                stringResource(R.string.share_error_transfer_failed)
            }

            detail.equals("Unknown error", ignoreCase = true) -> {
                stringResource(R.string.share_error_unknown)
            }

            detail.contains("pairing code is not configured", ignoreCase = true) -> {
                stringResource(R.string.share_error_set_password_first)
            }

            else -> {
                if (isTechnicalMessage(detail)) {
                    stringResource(R.string.share_error_unknown)
                } else {
                    detail
                }
            }
        }
    }
}
