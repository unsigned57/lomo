package com.lomo.data.s3

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.credentialStatus
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3CredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "s3_credentials",
                keyAlias = "s3_credentials",
            )
        },
    )

    internal constructor(prefs: SecureStringStore) : this({ prefs })

    private val prefs: SecureStringStore by lazy(secureStringStoreFactory)

    fun getAccessKeyId(): String? = prefs.getString(KEY_ACCESS_KEY_ID)

    val accessKeyIdStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_ACCESS_KEY_ID)

    fun setAccessKeyId(accessKeyId: String?) {
        prefs.putString(KEY_ACCESS_KEY_ID, accessKeyId)
    }

    fun getSecretAccessKey(): String? = prefs.getString(KEY_SECRET_ACCESS_KEY)

    val secretAccessKeyStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_SECRET_ACCESS_KEY)

    fun setSecretAccessKey(secretAccessKey: String?) {
        prefs.putString(KEY_SECRET_ACCESS_KEY, secretAccessKey)
    }

    fun getSessionToken(): String? = prefs.getString(KEY_SESSION_TOKEN)

    val sessionTokenStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_SESSION_TOKEN)

    fun setSessionToken(sessionToken: String?) {
        prefs.putString(KEY_SESSION_TOKEN, sessionToken)
    }

    fun getEncryptionPassword(): String? = prefs.getString(KEY_ENCRYPTION_PASSWORD)

    val encryptionPasswordStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_ENCRYPTION_PASSWORD)

    fun setEncryptionPassword(password: String?) {
        prefs.putString(KEY_ENCRYPTION_PASSWORD, password)
    }

    fun getEncryptionPassword2(): String? = prefs.getString(KEY_ENCRYPTION_PASSWORD2)

    val encryptionPassword2Status: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_ENCRYPTION_PASSWORD2)

    val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.S3,
                fields =
                    listOf(
                        CredentialFieldState(CredentialField.S3_ACCESS_KEY_ID, accessKeyIdStatus),
                        CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, secretAccessKeyStatus),
                        CredentialFieldState(CredentialField.S3_SESSION_TOKEN, sessionTokenStatus),
                        CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD, encryptionPasswordStatus),
                        CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD2, encryptionPassword2Status),
                    ),
            )

    fun setEncryptionPassword2(password: String?) {
        prefs.putString(KEY_ENCRYPTION_PASSWORD2, password)
    }

    private companion object {
        const val KEY_ACCESS_KEY_ID = "s3_access_key_id"
        const val KEY_SECRET_ACCESS_KEY = "s3_secret_access_key"
        const val KEY_SESSION_TOKEN = "s3_session_token"
        const val KEY_ENCRYPTION_PASSWORD = "s3_encryption_password"
        const val KEY_ENCRYPTION_PASSWORD2 = "s3_encryption_password2"
    }
}
