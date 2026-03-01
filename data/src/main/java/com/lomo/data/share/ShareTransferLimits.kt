package com.lomo.data.share

internal object ShareTransferLimits {
    const val MAX_ATTACHMENTS = 20
    const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
    const val MAX_TOTAL_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
    const val GCM_TAG_BYTES = 16L
    const val MAX_MEMO_CHARS = 200_000
    const val MAX_ENCRYPTED_MEMO_CHARS = 600_000
    const val MAX_PREPARE_BODY_CHARS = 64 * 1024
    const val MAX_TRANSFER_BODY_BYTES = 120L * 1024L * 1024L

    const val MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES = MAX_ATTACHMENT_SIZE_BYTES + GCM_TAG_BYTES
    const val MAX_TOTAL_ENCRYPTED_ATTACHMENT_BYTES =
        MAX_TOTAL_ATTACHMENT_SIZE_BYTES + (MAX_ATTACHMENTS * GCM_TAG_BYTES)

    fun maxMemoChars(e2eEnabled: Boolean): Int =
        if (e2eEnabled) {
            MAX_ENCRYPTED_MEMO_CHARS
        } else {
            MAX_MEMO_CHARS
        }

    fun maxAttachmentPayloadBytes(e2eEnabled: Boolean): Long =
        if (e2eEnabled) {
            MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES
        } else {
            MAX_ATTACHMENT_SIZE_BYTES
        }

    fun maxTotalAttachmentPayloadBytes(e2eEnabled: Boolean): Long =
        if (e2eEnabled) {
            MAX_TOTAL_ENCRYPTED_ATTACHMENT_BYTES
        } else {
            MAX_TOTAL_ATTACHMENT_SIZE_BYTES
        }

    fun isAttachmentCountValid(count: Int): Boolean = count <= MAX_ATTACHMENTS
}
