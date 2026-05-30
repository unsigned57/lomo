package com.lomo.data.webdav

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.credentialStatus
import com.lomo.domain.model.StoredCredentialStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavCredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
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

    fun getUsername(): String? = prefs.getString(KEY_USERNAME)

    val usernameStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_USERNAME)

    fun setUsername(username: String?) {
        prefs.putString(KEY_USERNAME, username)
    }

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD)

    val passwordStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_PASSWORD)

    fun setPassword(password: String?) {
        prefs.putString(KEY_PASSWORD, password)
    }

    companion object {
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
    }
}
