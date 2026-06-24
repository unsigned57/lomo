package com.lomo.data.security

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCredentialRepository
    @Inject
    constructor(
        private val gitCredentialStore: GitCredentialStore,
        private val webDavCredentialStore: WebDavCredentialStore,
        private val s3CredentialStore: S3CredentialStore,
        private val lanCredentialStore: LanShareCredentialStore,
    ) : CredentialRepository {
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
            changes
                .onStart { emit(Unit) }
                .map { credentialState(provider) }

        override suspend fun credentialState(provider: CredentialProvider): CredentialState =
            when (provider) {
                CredentialProvider.GIT -> gitCredentialStore.credentialState
                CredentialProvider.WEBDAV -> webDavCredentialStore.credentialState
                CredentialProvider.S3 -> s3CredentialStore.credentialState
                CredentialProvider.LAN_SHARE -> lanCredentialStore.credentialState
            }

        override suspend fun readSecret(
            field: CredentialField,
            authorization: CredentialReadAuthorization,
        ): CredentialSecretReadResult {
            if (authorization is CredentialReadAuthorization.Denied) {
                return CredentialSecretReadResult.Unauthorized(authorization.reason)
            }
            return when (field) {
                CredentialField.GIT_TOKEN -> gitCredentialStore.readToken()
                CredentialField.WEBDAV_USERNAME -> webDavCredentialStore.readUsername()
                CredentialField.WEBDAV_PASSWORD -> webDavCredentialStore.readPassword()
                CredentialField.S3_ACCESS_KEY_ID,
                CredentialField.S3_SECRET_ACCESS_KEY,
                CredentialField.S3_SESSION_TOKEN,
                CredentialField.S3_ENCRYPTION_PASSWORD,
                CredentialField.S3_ENCRYPTION_PASSWORD2,
                -> s3CredentialStore.readSecret(field)
                CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> lanCredentialStore.readPairingKeyHex()
            }.toCredentialSecretReadResult()
        }

        override suspend fun writeSecret(
            field: CredentialField,
            value: String?,
        ) {
            when (field) {
                CredentialField.GIT_TOKEN -> gitCredentialStore.setToken(value)
                CredentialField.WEBDAV_USERNAME -> webDavCredentialStore.setUsername(value)
                CredentialField.WEBDAV_PASSWORD -> webDavCredentialStore.setPassword(value)
                CredentialField.S3_ACCESS_KEY_ID,
                CredentialField.S3_SECRET_ACCESS_KEY,
                CredentialField.S3_SESSION_TOKEN,
                CredentialField.S3_ENCRYPTION_PASSWORD,
                CredentialField.S3_ENCRYPTION_PASSWORD2,
                -> s3CredentialStore.setSecret(field, value)
                CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> lanCredentialStore.setPairingKeyHex(value)
            }
            changes.tryEmit(Unit)
        }
    }

internal fun SecureStringReadResult.toCredentialSecretReadResult(): CredentialSecretReadResult =
    when (this) {
        SecureStringReadResult.Missing -> CredentialSecretReadResult.Missing
        is SecureStringReadResult.Present -> CredentialSecretReadResult.Present(value)
        is SecureStringReadResult.Unreadable -> CredentialSecretReadResult.Unreadable
    }
