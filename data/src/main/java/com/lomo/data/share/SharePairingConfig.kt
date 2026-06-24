package com.lomo.data.share

import android.os.Build
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadException
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SharePairingConfig
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
    ) {
        private val pairingCodeInputState = MutableStateFlow("")

        val lanShareEnabled: Flow<Boolean> = dataStore.lanShareEnabled
        val lanShareE2eEnabled: Flow<Boolean> = dataStore.lanShareE2eEnabled
        val lanSharePairingConfigured: Flow<Boolean> =
            flow {
                migrateLegacyPairingKeyIfPresent()
                emitAll(
                    credentialRepository
                        .observeCredentialState(CredentialProvider.LAN_SHARE)
                        .map { state -> state.isConfigured },
                )
            }
        val lanSharePairingCode: StateFlow<String> = pairingCodeInputState.asStateFlow()
        val lanShareDeviceName: Flow<String> =
            dataStore.lanShareDeviceName.map {
                sanitizeDeviceName(it) ?: getFallbackDeviceName()
            }

        suspend fun setLanShareEnabled(enabled: Boolean) {
            dataStore.updateLanShareEnabled(enabled)
        }

        suspend fun setLanShareE2eEnabled(enabled: Boolean) {
            dataStore.updateLanShareE2eEnabled(enabled)
        }

        suspend fun setLanSharePairingCode(pairingCode: String) {
            val normalized = LanSharePairingCodePolicy.normalize(pairingCode)
            LanSharePairingCodePolicy.requireValid(normalized)
            val keyMaterial =
                ShareAuthUtils.deriveKeyMaterialFromPairingCode(normalized)
                    ?: throw IllegalStateException("Failed to derive pairing key material")
            credentialRepository.writeSecret(CredentialField.LAN_SHARE_PAIRING_KEY_HEX, keyMaterial)
            pairingCodeInputState.value = normalized
        }

        suspend fun clearLanSharePairingCode() {
            credentialRepository.writeSecret(CredentialField.LAN_SHARE_PAIRING_KEY_HEX, null)
            pairingCodeInputState.value = ""
        }

        suspend fun setLanShareDeviceName(deviceName: String) {
            val sanitized = sanitizeDeviceName(deviceName)
            dataStore.updateLanShareDeviceName(sanitized)
        }

        suspend fun requiresPairingBeforeSend(): Boolean {
            val e2eEnabled = dataStore.lanShareE2eEnabled.first()
            if (!e2eEnabled) return false
            return !isValidKeyHex(getEffectivePairingKeyHex())
        }

        suspend fun getEffectivePairingKeyHex(): String? {
            migrateLegacyPairingKeyIfPresent()
            return when (
                val result =
                    credentialRepository.readSecret(
                        field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                        authorization = securitySessionPolicy.authorizeCredentialRead(),
                    )
            ) {
                CredentialSecretReadResult.Missing -> null
                is CredentialSecretReadResult.Present -> result.value
                CredentialSecretReadResult.Unreadable ->
                    throw CredentialSecretReadException(result)
                is CredentialSecretReadResult.Unauthorized ->
                    throw CredentialSecretReadException(result)
            }
        }

        suspend fun resolveDeviceName(): String {
            val custom = dataStore.lanShareDeviceName.first()
            return sanitizeDeviceName(custom) ?: getFallbackDeviceName()
        }

        suspend fun isLanShareEnabled(): Boolean = dataStore.lanShareEnabled.first()

        suspend fun isE2eEnabled(): Boolean = dataStore.lanShareE2eEnabled.first()

        private suspend fun migrateLegacyPairingKeyIfPresent() {
            val legacyKey = dataStore.drainLegacyLanSharePairingKeyHex()
            if (legacyKey != null) {
                credentialRepository.writeSecret(CredentialField.LAN_SHARE_PAIRING_KEY_HEX, legacyKey)
            }
        }

        companion object {
            private const val MAX_DEVICE_NAME_CHARS = 32
            private const val DEFAULT_DEVICE_NAME = "Android Device"
        }
    }

private fun getFallbackDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    return sanitizeDeviceName(model) ?: DEFAULT_DEVICE_NAME
}

private fun sanitizeDeviceName(name: String?): String? {
    val normalized =
        name
            ?.trim()
            ?.replace(Regex("""[\u0000-\u001F\u007F]"""), "")
            ?.replace(Regex("""[\u202A-\u202E\u2066-\u2069]"""), "")
            ?.replace(Regex("""\s+"""), " ")
    return normalized
        ?.takeUnless(String::isBlank)
        ?.take(MAX_DEVICE_NAME_CHARS)
}

private const val MAX_DEVICE_NAME_CHARS = 32
private const val DEFAULT_DEVICE_NAME = "Android Device"
