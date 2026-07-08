package com.lomo.data.security

import android.content.Context
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus


class LanShareCredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    constructor(
        context: Context,
    ) : this(
        {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "lan_share_credentials",
                keyAlias = "lan_share_credentials",
            )
        },
    )

    internal constructor(prefs: SecureStringStore) : this({ prefs })

    private val prefs: SecureStringStore by lazy(secureStringStoreFactory)

    internal fun getPairingKeyHex(): String? = prefs.getString(KEY_PAIRING_KEY_HEX)

    internal fun readPairingKeyHex(): SecureStringReadResult = prefs.readString(KEY_PAIRING_KEY_HEX)

    val pairingKeyHexStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_PAIRING_KEY_HEX)

    val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.LAN_SHARE,
                fields =
                    listOf(
                        CredentialFieldState(
                            field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                            status = pairingKeyHexStatus,
                        ),
                    ),
            )

    fun setPairingKeyHex(keyHex: String?) {
        prefs.putString(KEY_PAIRING_KEY_HEX, keyHex)
    }

    private companion object {
        const val KEY_PAIRING_KEY_HEX = "lan_share_pairing_key_hex"
    }
}
