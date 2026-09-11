package com.lomo.data.webdav

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.security.credentialStatus
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
class WebDavCredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    constructor(
        context: Context,
    ) : this(
        {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "webdav_credentials",
                keyAlias = "webdav_credentials",
            )
        },
    )

    internal constructor(prefs: SecureStringStore) : this({ prefs })

    private val prefs: SecureStringStore by lazy(secureStringStoreFactory)

    internal fun getUsername(): String? = prefs.getString(KEY_USERNAME)

    internal fun readUsername(): SecureStringReadResult = prefs.readString(KEY_USERNAME)

    val usernameStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_USERNAME)

    fun setUsername(username: String?) {
        prefs.putString(KEY_USERNAME, username)
    }

    internal fun getPassword(): String? = prefs.getString(KEY_PASSWORD)

    internal fun readPassword(): SecureStringReadResult = prefs.readString(KEY_PASSWORD)

    val passwordStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_PASSWORD)

    fun setPassword(password: String?) {
        prefs.putString(KEY_PASSWORD, password)
    }

    val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.WEBDAV,
                fields =
                    listOf(
                        CredentialFieldState(CredentialField.WEBDAV_USERNAME, usernameStatus),
                        CredentialFieldState(CredentialField.WEBDAV_PASSWORD, passwordStatus),
                    ),
            )

    companion object {
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
    }
}
