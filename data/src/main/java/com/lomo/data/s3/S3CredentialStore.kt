package com.lomo.data.s3

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3CredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: KeystoreBackedPreferences by lazy {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "s3_credentials",
                keyAlias = "s3_credentials",
            )
        }

        fun getAccessKeyId(): String? = prefs.getString(KEY_ACCESS_KEY_ID)

        fun setAccessKeyId(accessKeyId: String?) {
            prefs.putString(KEY_ACCESS_KEY_ID, accessKeyId)
        }

        fun getSecretAccessKey(): String? = prefs.getString(KEY_SECRET_ACCESS_KEY)

        fun setSecretAccessKey(secretAccessKey: String?) {
            prefs.putString(KEY_SECRET_ACCESS_KEY, secretAccessKey)
        }

        fun getSessionToken(): String? = prefs.getString(KEY_SESSION_TOKEN)

        fun setSessionToken(sessionToken: String?) {
            prefs.putString(KEY_SESSION_TOKEN, sessionToken)
        }

        fun getEncryptionPassword(): String? = prefs.getString(KEY_ENCRYPTION_PASSWORD)

        fun setEncryptionPassword(password: String?) {
            prefs.putString(KEY_ENCRYPTION_PASSWORD, password)
        }

        private companion object {
            const val KEY_ACCESS_KEY_ID = "s3_access_key_id"
            const val KEY_SECRET_ACCESS_KEY = "s3_secret_access_key"
            const val KEY_SESSION_TOKEN = "s3_session_token"
            const val KEY_ENCRYPTION_PASSWORD = "s3_encryption_password"
        }
    }
