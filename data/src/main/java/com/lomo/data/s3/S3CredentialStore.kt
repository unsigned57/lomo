package com.lomo.data.s3

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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_ACCESS_KEY_ID = "s3_access_key_id"
private const val KEY_SECRET_ACCESS_KEY = "s3_secret_access_key"
private const val KEY_SESSION_TOKEN = "s3_session_token"
private const val KEY_ENCRYPTION_PASSWORD = "s3_encryption_password"
private const val KEY_ENCRYPTION_PASSWORD2 = "s3_encryption_password2"

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

    internal fun getSecret(field: CredentialField): String? =
        prefs.getString(field.preferenceKey())

    internal fun readSecret(field: CredentialField): SecureStringReadResult =
        prefs.readString(field.preferenceKey())

    internal fun secretStatus(field: CredentialField): StoredCredentialStatus =
        prefs.credentialStatus(field.preferenceKey())

    internal fun setSecret(
        field: CredentialField,
        value: String?,
    ) {
        prefs.putString(field.preferenceKey(), value)
    }

    internal val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.S3,
                fields =
                    S3_CREDENTIAL_FIELDS.map { field ->
                        CredentialFieldState(field, secretStatus(field))
                    },
            )

    private companion object {
        val S3_CREDENTIAL_FIELDS =
            listOf(
                CredentialField.S3_ACCESS_KEY_ID,
                CredentialField.S3_SECRET_ACCESS_KEY,
                CredentialField.S3_SESSION_TOKEN,
                CredentialField.S3_ENCRYPTION_PASSWORD,
                CredentialField.S3_ENCRYPTION_PASSWORD2,
            )
    }
}

private fun CredentialField.preferenceKey(): String =
    when (this) {
        CredentialField.S3_ACCESS_KEY_ID -> KEY_ACCESS_KEY_ID
        CredentialField.S3_SECRET_ACCESS_KEY -> KEY_SECRET_ACCESS_KEY
        CredentialField.S3_SESSION_TOKEN -> KEY_SESSION_TOKEN
        CredentialField.S3_ENCRYPTION_PASSWORD -> KEY_ENCRYPTION_PASSWORD
        CredentialField.S3_ENCRYPTION_PASSWORD2 -> KEY_ENCRYPTION_PASSWORD2
        else -> error("Credential field $this is not owned by the S3 credential store")
    }
