package com.lomo.app.feature.lanshare

object LanSharePairingCodePolicy {
    enum class UserMessageKey {
        INVALID_PAIRING_CODE,
        UNKNOWN,
    }

    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 64
    const val INVALID_LENGTH_MESSAGE = "Pairing code must be 6-64 characters"
    const val INVALID_PASSWORD_MESSAGE = "Invalid password"

    fun hasValidLength(code: String): Boolean = code.trim().length in MIN_LENGTH..MAX_LENGTH

    fun shouldDismissDialogAfterSave(code: String): Boolean = hasValidLength(code)

    fun saveFailureMessage(throwable: Throwable): String =
        throwable.message
            ?.takeIf(::isInvalidPairingCodeMessage)
            ?: INVALID_LENGTH_MESSAGE

    fun userMessageKey(raw: String?): UserMessageKey {
        val detail = raw?.trim().orEmpty()
        return if (isInvalidPairingCodeMessage(detail)) {
            UserMessageKey.INVALID_PAIRING_CODE
        } else {
            UserMessageKey.UNKNOWN
        }
    }

    fun isInvalidPairingCodeMessage(raw: String): Boolean {
        val detail = raw.trim()
        return detail.equals(INVALID_LENGTH_MESSAGE, ignoreCase = true) ||
            detail.equals(INVALID_PASSWORD_MESSAGE, ignoreCase = true)
    }
}
