package com.lomo.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.data.security.LanShareCredentialStore
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.OkHttpWebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: sync configuration credential status repositories
 * - Owning layer: data/repository
 * - Priority tier: P0
 * - Capability: expose typed credential status and provider readiness without flattening secure-store read failures.
 *
 * Scenarios:
 * - Given Git token storage is unreadable, when settings reads token status, then Unreadable is observable and legacy configured is false.
 * - Given Git settings writes a token, when persistence is inspected, then the write is recorded by CredentialRepository instead of the raw provider store.
 * - Given WebDAV username exists in DataStore and password is readable, when provider credential state is requested, then effective provider readiness is present even without a secure username copy.
 * - Given S3 required access and secret keys are present but an optional session token is unreadable, when provider credential state is requested, then readiness is present and health is unreadable.
 * - Given S3 settings writes secrets and reads status, when persistence is inspected, then CredentialRepository owns writes and typed state.
 *
 * Observable outcomes:
 * - repository StoredCredentialStatus values, CredentialState readiness/health status, and legacy configured booleans.
 *
 * TDD proof:
 * - RED: targeted test compile fails before repositories expose typed credential status/readiness APIs.
 *
 * Excludes:
 * - Android Keystore cryptography, sync transport clients, app rendering, and migration restore.
 *
 * Test Change Justification:
 * - Reason category: Credential read/write ownership moved from per-provider credential stores to a
 *   unified CredentialRepository with security-session-gated authorization.
 * - Old behavior/assertion being replaced: tests asserted credential status via per-provider stores
 *   (GitCredentialStore, S3CredentialStore, WebDavCredentialStore) reading directly from secure storage.
 * - Why old assertion is no longer correct: all credential reads now flow through CredentialRepository
 *   with CredentialReadAuthorization, making per-store direct reads an invalid path.
 * - Coverage preserved by: all provider credential status scenarios retained (Git, WebDAV, S3) with
 *   assertions updated to observe typed credential state through the unified repository interface.
 * - Why this is not fitting the test to the implementation: tests verify externally observable
 *   credential status outcomes (Missing, Present, Unreadable), not internal secure store mechanics.
 */
class CredentialStatusRepositoryTest : DataFunSpec() {
    init {
        test("given git token is unreadable when settings status is requested then unreadable is observable") {
            runTest {
                val repository =
                    GitSyncConfigurationMutationRepositoryImpl(
                        dataStore = createLomoDataStore(backgroundScope),
                        credentialRepository =
                            recordingCredentialRepository(
                                mapOf(CredentialField.GIT_TOKEN to StoredCredentialStatus.Unreadable),
                            ),
                    )

                repository.getTokenStatus() shouldBe StoredCredentialStatus.Unreadable
                repository.isTokenConfigured() shouldBe false
            }
        }

        test("given git token is updated when settings mutates credentials then credential repository owns the write") {
            runTest {
                val credentialRepository =
                    recordingCredentialRepository(
                        statuses = mapOf(CredentialField.GIT_TOKEN to StoredCredentialStatus.Missing),
                    )
                val repository =
                    GitSyncConfigurationMutationRepositoryImpl(
                        dataStore = createLomoDataStore(backgroundScope),
                        credentialRepository = credentialRepository,
                    )

                repository.setToken("  ghp_token  ")

                credentialRepository.writes.shouldContainExactly(
                    listOf(CredentialField.GIT_TOKEN to "  ghp_token  "),
                )
            }
        }

        test("given webdav datastore username and readable password when provider state is requested then readiness is present") {
            runTest {
                val dataStore = createLomoDataStore(backgroundScope)
                dataStore.updateWebDavUsername("alice")
                val webDavCredentialStore =
                    WebDavCredentialStore(
                        FakeSecureStringStore(
                            mapOf(
                                "webdav_username" to SecureStringReadResult.Missing,
                                "webdav_password" to SecureStringReadResult.Present("secret"),
                            ),
                        ),
                    )
                val credentialRepository =
                    DefaultCredentialRepository(
                        gitCredentialStore = GitCredentialStore(FakeSecureStringStore(emptyMap())),
                        webDavCredentialStore = webDavCredentialStore,
                        s3CredentialStore = S3CredentialStore(FakeSecureStringStore(emptyMap())),
                        lanCredentialStore = LanShareCredentialStore(FakeSecureStringStore(emptyMap())),
                    )
                val repository =
                    WebDavSyncConfigurationMutationRepositoryImpl(
                        dataStore = dataStore,
                        credentialStore = webDavCredentialStore,
                        credentialRepository = credentialRepository,
                        securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
                        clientFactory =
                            OkHttpWebDavClientFactory(
                                httpClientProvider = SyncHttpClientProvider(),
                                performanceTuner = DisabledSyncPerformanceTuner,
                            ),
                    )

                val state = repository.getCredentialState()

                state.readinessStatus shouldBe StoredCredentialStatus.Present
                state.healthStatus shouldBe StoredCredentialStatus.Present
                state.fields.shouldContain(
                    com.lomo.domain.model.CredentialFieldState(
                        CredentialField.WEBDAV_USERNAME,
                        StoredCredentialStatus.Present,
                    ),
                )
                repository.isPasswordConfigured() shouldBe true
            }
        }

        test("given s3 optional stored secret is unreadable when provider state is requested then readiness and health diverge") {
            runTest {
                val repository =
                    S3SyncConfigurationMutationRepositoryImpl(
                        dataStore = createLomoDataStore(backgroundScope),
                        credentialRepository =
                            recordingCredentialRepository(
                                statuses =
                                    mapOf(
                                        CredentialField.S3_ACCESS_KEY_ID to StoredCredentialStatus.Present,
                                        CredentialField.S3_SECRET_ACCESS_KEY to StoredCredentialStatus.Present,
                                        CredentialField.S3_SESSION_TOKEN to StoredCredentialStatus.Unreadable,
                                        CredentialField.S3_ENCRYPTION_PASSWORD to StoredCredentialStatus.Missing,
                                        CredentialField.S3_ENCRYPTION_PASSWORD2 to StoredCredentialStatus.Missing,
                                    ),
                            ),
                    )

                val state = repository.getCredentialState()

                state.readinessStatus shouldBe StoredCredentialStatus.Present
                state.healthStatus shouldBe StoredCredentialStatus.Unreadable
                state.status shouldBe StoredCredentialStatus.Unreadable
                repository.getSessionTokenStatus() shouldBe StoredCredentialStatus.Unreadable
                repository.isSessionTokenConfigured() shouldBe false
            }
        }

        test("given s3 secrets are updated and status is requested then credential repository owns writes and state") {
            runTest {
                val credentialRepository =
                    recordingCredentialRepository(
                        statuses =
                            mapOf(
                                CredentialField.S3_ACCESS_KEY_ID to StoredCredentialStatus.Present,
                                CredentialField.S3_SECRET_ACCESS_KEY to StoredCredentialStatus.Unreadable,
                                CredentialField.S3_SESSION_TOKEN to StoredCredentialStatus.Missing,
                                CredentialField.S3_ENCRYPTION_PASSWORD to StoredCredentialStatus.Present,
                                CredentialField.S3_ENCRYPTION_PASSWORD2 to StoredCredentialStatus.Missing,
                            ),
                    )
                val repository =
                    S3SyncConfigurationMutationRepositoryImpl(
                        dataStore = createLomoDataStore(backgroundScope),
                        credentialRepository = credentialRepository,
                    )

                repository.setAccessKeyId(" access ")
                repository.setSecretAccessKey(" secret ")
                repository.setSessionToken(" token ")
                repository.setEncryptionPassword(" password ")
                repository.setEncryptionPassword2(" password2 ")

                credentialRepository.writes.shouldContainExactly(
                    listOf(
                        CredentialField.S3_ACCESS_KEY_ID to "access",
                        CredentialField.S3_SECRET_ACCESS_KEY to "secret",
                        CredentialField.S3_SESSION_TOKEN to "token",
                        CredentialField.S3_ENCRYPTION_PASSWORD to "password",
                        CredentialField.S3_ENCRYPTION_PASSWORD2 to "password2",
                    ),
                )
                repository.getSecretAccessKeyStatus() shouldBe StoredCredentialStatus.Unreadable
                repository.getCredentialState().fields.shouldContain(
                    CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, StoredCredentialStatus.Unreadable),
                )
            }
        }
    }

    private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val backingFile =
            Files.createTempFile("lomo-credential-status", ".preferences_pb").toFile().apply {
                deleteOnExit()
            }
        val realDataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { backingFile },
            )
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }
}

private class FakeSecureStringStore(
    private val reads: Map<String, SecureStringReadResult>,
) : SecureStringStore {
    private val writes = mutableMapOf<String, String?>()

    override fun readString(key: String): SecureStringReadResult =
        if (key in writes) {
            writes[key]?.let(SecureStringReadResult::Present) ?: SecureStringReadResult.Missing
        } else {
            reads[key] ?: SecureStringReadResult.Missing
        }

    override fun putString(
        key: String,
        value: String?,
    ) {
        writes[key] = value
    }

}

private fun recordingCredentialRepository(
    statuses: Map<CredentialField, StoredCredentialStatus>,
): RecordingCredentialRepository = RecordingCredentialRepository(statuses)

private class RecordingCredentialRepository(
    private val statuses: Map<CredentialField, StoredCredentialStatus>,
) : CredentialRepository {
    val writes = mutableListOf<Pair<CredentialField, String?>>()

    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(stateFor(provider))

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        stateFor(provider)

    private fun stateFor(provider: CredentialProvider): CredentialState =
        CredentialState(
            provider = provider,
            fields =
                statuses.map { (field, status) ->
                    CredentialFieldState(field, status)
                },
        )

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult = CredentialSecretReadResult.Missing

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) {
        writes += field to value
    }
}
