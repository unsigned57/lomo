package com.lomo.data.share

import android.os.Build
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SharePairingConfig
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) {
        private val pairingCodeInputState = MutableStateFlow("")

        val lanShareE2eEnabled: Flow<Boolean> = dataStore.lanShareE2eEnabled
        val lanSharePairingConfigured: Flow<Boolean> =
            dataStore.lanSharePairingKeyHex.map(::isValidKeyHex)
        val lanSharePairingCode: StateFlow<String> = pairingCodeInputState.asStateFlow()
        val lanShareDeviceName: Flow<String> =
            dataStore.lanShareDeviceName.map {
                sanitizeDeviceName(it) ?: getFallbackDeviceName()
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
            dataStore.updateLanSharePairingKeyHex(keyMaterial)
            pairingCodeInputState.value = normalized
        }

        suspend fun clearLanSharePairingCode() {
            dataStore.updateLanSharePairingKeyHex(null)
            pairingCodeInputState.value = ""
        }

        suspend fun setLanShareDeviceName(deviceName: String) {
            val sanitized = sanitizeDeviceName(deviceName)
            dataStore.updateLanShareDeviceName(sanitized)
        }

        suspend fun requiresPairingBeforeSend(): Boolean {
            val e2eEnabled = dataStore.lanShareE2eEnabled.first()
            if (!e2eEnabled) return false
            return !isValidKeyHex(dataStore.lanSharePairingKeyHex.first())
        }

        suspend fun getEffectivePairingKeyHex(): String? = dataStore.lanSharePairingKeyHex.first()

        suspend fun resolveDeviceName(): String {
            val custom = dataStore.lanShareDeviceName.first()
            return sanitizeDeviceName(custom) ?: getFallbackDeviceName()
        }

        suspend fun isE2eEnabled(): Boolean = dataStore.lanShareE2eEnabled.first()

        private fun getFallbackDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return sanitizeDeviceName(model) ?: DEFAULT_DEVICE_NAME
        }

        private fun sanitizeDeviceName(name: String?): String? {
            val normalized =
                name
                    ?.trim()
                    ?.replace(Regex("""[\u0000-\u001F\u007F]"""), "")
                    ?.replace(Regex("""\s+"""), " ")
            return normalized
                ?.takeUnless(String::isBlank)
                ?.take(MAX_DEVICE_NAME_CHARS)
        }

        companion object {
            private const val MAX_DEVICE_NAME_CHARS = 32
            private const val DEFAULT_DEVICE_NAME = "Android Device"
        }
    }
