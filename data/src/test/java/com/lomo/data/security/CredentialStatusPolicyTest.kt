package com.lomo.data.security

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.PreferenceKeys
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.isConfigured
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: data credential status policy and provider credential stores
 * - Owning layer: data/security
 * - Priority tier: P0
 * - Capability: expose credential Missing/Present/Unreadable/Invalid state without reading paths silently deleting or flattening failures.
 *
 * Scenarios:
 * - Given missing, blank, present, unreadable, and invalid secure values, when status is requested, then each has an explicit status.
 * - Given an unreadable Git/WebDAV/S3 secret, when provider stores report credential status, then configured is false and unreadable is observable.
 * - Given a secure read failure, when callers request the raw value, then the failure is thrown instead of returning null.
 * - Given the LAN pairing key still lives in DataStore, when sensitive preference policy is queried, then it is classified as a credential pending secure-store migration.
 *
 * Observable outcomes:
 * - StoredCredentialStatus results, provider CredentialFieldState values, and thrown SecureStringReadException.
 *
 * TDD proof:
 * - Fails before the fix because SecureStringReadResult, credentialStatus, and provider status APIs do not exist.
 *
 * Excludes:
 * - Android Keystore cryptography, Hilt binding, settings UI coordinator state, and migration restore.
 */
class CredentialStatusPolicyTest : DataFunSpec() {
    init {
        test("given secure read outcomes when status is requested then missing present unreadable and invalid are explicit") {
            val store =
                FakeSecureStringStore(
                    mapOf(
                        "missing" to SecureStringReadResult.Missing,
                        "blank" to SecureStringReadResult.Present(" "),
                        "present" to SecureStringReadResult.Present("token"),
                        "unreadable" to SecureStringReadResult.Unreadable(IllegalStateException("keystore failed")),
                    ),
                )

            store.credentialStatus("missing") shouldBe StoredCredentialStatus.Missing
            store.credentialStatus("blank") shouldBe StoredCredentialStatus.Invalid
            store.credentialStatus("present") shouldBe StoredCredentialStatus.Present
            store.credentialStatus("unreadable") shouldBe StoredCredentialStatus.Unreadable
        }

        test("given secure read failure when value is requested then failure is observable") {
            val store =
                FakeSecureStringStore(
                    mapOf("token" to SecureStringReadResult.Unreadable(IllegalArgumentException("bad payload"))),
                )

            val failure =
                runCatching { store.getString("token") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<SecureStringReadException>()
            failure.key shouldBe "token"
        }

        test("given provider credentials when status is requested then provider fields expose unreadable and invalid separately") {
            val gitStore =
                GitCredentialStore(
                    FakeSecureStringStore(
                        mapOf("github_pat" to SecureStringReadResult.Unreadable(IllegalStateException("locked"))),
                    ),
                )
            val webDavStore =
                WebDavCredentialStore(
                    FakeSecureStringStore(
                        mapOf(
                            "webdav_username" to SecureStringReadResult.Present("alice"),
                            "webdav_password" to SecureStringReadResult.Present(" "),
                        ),
                    ),
                )
            val s3Store =
                S3CredentialStore(
                    FakeSecureStringStore(
                        mapOf(
                            "s3_access_key_id" to SecureStringReadResult.Present("access"),
                            "s3_secret_access_key" to SecureStringReadResult.Unreadable(IllegalStateException("locked")),
                            "s3_session_token" to SecureStringReadResult.Missing,
                            "s3_encryption_password" to SecureStringReadResult.Present("crypt"),
                            "s3_encryption_password2" to SecureStringReadResult.Present("crypt2"),
                        ),
                    ),
                )

            gitStore.tokenStatus shouldBe StoredCredentialStatus.Unreadable
            gitStore.tokenStatus.isConfigured shouldBe false
            webDavStore.usernameStatus shouldBe StoredCredentialStatus.Present
            webDavStore.passwordStatus shouldBe StoredCredentialStatus.Invalid
            s3Store.credentialState.fields.shouldContainExactly(
                listOf(
                    CredentialFieldStateForTest(CredentialField.S3_ACCESS_KEY_ID, StoredCredentialStatus.Present),
                    CredentialFieldStateForTest(CredentialField.S3_SECRET_ACCESS_KEY, StoredCredentialStatus.Unreadable),
                    CredentialFieldStateForTest(CredentialField.S3_SESSION_TOKEN, StoredCredentialStatus.Missing),
                    CredentialFieldStateForTest(CredentialField.S3_ENCRYPTION_PASSWORD, StoredCredentialStatus.Present),
                    CredentialFieldStateForTest(CredentialField.S3_ENCRYPTION_PASSWORD2, StoredCredentialStatus.Present),
                ).map { it.toDomain() },
            )
        }

        test("given lan pairing key remains in datastore when policy is queried then it is marked sensitive and pending migration") {
            val policy = SensitiveCredentialPreferencePolicy.requirePolicy(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)

            policy.keyName shouldBe PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX
            policy.provider shouldBe CredentialProvider.LAN_SHARE
            policy.field shouldBe CredentialField.LAN_SHARE_PAIRING_KEY_HEX
            policy.storage shouldBe SensitiveCredentialStorage.PLAIN_DATASTORE_PENDING_SECURE_STORE_MIGRATION
            policy.exportRequiresSensitiveChannel shouldBe true
        }
    }
}

private class FakeSecureStringStore(
    private val reads: Map<String, SecureStringReadResult>,
) : SecureStringStore {
    override fun readString(key: String): SecureStringReadResult = reads[key] ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) = Unit
}

private data class CredentialFieldStateForTest(
    val field: CredentialField,
    val status: StoredCredentialStatus,
) {
    fun toDomain(): com.lomo.domain.model.CredentialFieldState =
        com.lomo.domain.model.CredentialFieldState(field, status)
}
