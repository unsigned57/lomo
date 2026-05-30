package com.lomo.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.Dav4jvmWebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.StoredCredentialStatus
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
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
 * - Given WebDAV username exists in DataStore and password is readable, when provider credential state is requested, then effective provider readiness is present even without a secure username copy.
 * - Given S3 required access and secret keys are present but an optional session token is unreadable, when provider credential state is requested, then readiness is present and health is unreadable.
 *
 * Observable outcomes:
 * - repository StoredCredentialStatus values, CredentialState readiness/health status, and legacy configured booleans.
 *
 * TDD proof:
 * - RED: targeted test compile fails before repositories expose typed credential status/readiness APIs.
 *
 * Excludes:
 * - Android Keystore cryptography, sync transport clients, app rendering, and migration restore.
 */
class CredentialStatusRepositoryTest : DataFunSpec() {
    init {
        test("given git token is unreadable when settings status is requested then unreadable is observable") {
            runTest {
                val repository =
                    GitSyncConfigurationMutationRepositoryImpl(
                        dataStore = createLomoDataStore(backgroundScope),
                        credentialStore =
                            GitCredentialStore(
                                FakeSecureStringStore(
                                    mapOf(
                                        "github_pat" to
                                            SecureStringReadResult.Unreadable(IllegalStateException("locked")),
                                    ),
                                ),
                            ),
                    )

                repository.getTokenStatus() shouldBe StoredCredentialStatus.Unreadable
                repository.isTokenConfigured() shouldBe false
            }
        }

        test("given webdav datastore username and readable password when provider state is requested then readiness is present") {
            runTest {
                val dataStore = createLomoDataStore(backgroundScope)
                dataStore.updateWebDavUsername("alice")
                val repository =
                    WebDavSyncConfigurationMutationRepositoryImpl(
                        dataStore = dataStore,
                        credentialStore =
                            WebDavCredentialStore(
                                FakeSecureStringStore(
                                    mapOf(
                                        "webdav_username" to SecureStringReadResult.Missing,
                                        "webdav_password" to SecureStringReadResult.Present("secret"),
                                    ),
                                ),
                            ),
                        clientFactory =
                            Dav4jvmWebDavClientFactory(
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
                        credentialStore =
                            S3CredentialStore(
                                FakeSecureStringStore(
                                    mapOf(
                                        "s3_access_key_id" to SecureStringReadResult.Present("access"),
                                        "s3_secret_access_key" to SecureStringReadResult.Present("secret"),
                                        "s3_session_token" to
                                            SecureStringReadResult.Unreadable(IllegalStateException("locked")),
                                        "s3_encryption_password" to SecureStringReadResult.Missing,
                                        "s3_encryption_password2" to SecureStringReadResult.Missing,
                                    ),
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
    override fun readString(key: String): SecureStringReadResult = reads[key] ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) = Unit
}
