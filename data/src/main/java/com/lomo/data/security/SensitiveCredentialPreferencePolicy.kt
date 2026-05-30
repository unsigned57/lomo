package com.lomo.data.security

import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider

internal enum class SensitiveCredentialStorage {
    SECURE_STRING_STORE,
    PLAIN_DATASTORE_PENDING_SECURE_STORE_MIGRATION,
}

internal data class SensitiveCredentialPreferencePolicy(
    val keyName: String,
    val provider: CredentialProvider,
    val field: CredentialField,
    val storage: SensitiveCredentialStorage,
    val exportRequiresSensitiveChannel: Boolean,
) {
    companion object {
        private val policies =
            listOf(
                SensitiveCredentialPreferencePolicy(
                    keyName = PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX,
                    provider = CredentialProvider.LAN_SHARE,
                    field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                    storage = SensitiveCredentialStorage.PLAIN_DATASTORE_PENDING_SECURE_STORE_MIGRATION,
                    exportRequiresSensitiveChannel = true,
                ),
            ).associateBy(SensitiveCredentialPreferencePolicy::keyName)

        fun requirePolicy(keyName: String): SensitiveCredentialPreferencePolicy =
            requireNotNull(policies[keyName]) { "No sensitive credential preference policy for key=$keyName" }

        val dataStoreResidentSensitivePreferenceKeys: Set<String> =
            policies.values
                .filter { policy ->
                    policy.storage == SensitiveCredentialStorage.PLAIN_DATASTORE_PENDING_SECURE_STORE_MIGRATION &&
                        policy.exportRequiresSensitiveChannel
                }
                .map(SensitiveCredentialPreferencePolicy::keyName)
                .toSet()
    }
}
