package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavCredentialStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: DataStoreMigrationSettingsStore
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: validate and restore datastore-backed migration settings with nullable and sensitive cleanup boundaries.
 *
 * Scenarios:
 * - Given a settings snapshot contains unknown keys, when restore is validated, then restore fails before persisted settings are changed.
 * - Given boolean, integer, or float settings contain invalid serialized values, when restore is validated, then the typed failure is observable.
 * - Given nullable preferences or sensitive credentials exist locally but are omitted from a successful restore, when restore commits, then omitted values are cleared.
 * - Given restore fails after applying nullable and sensitive values, when rollback runs, then original missing nullable/sensitive values are cleared again.
 * - Given restore commit fails and rollback also fails, when restore reports failure, then the original commit failure remains primary and rollback failure is suppressed.
 * - Given both preferences rollback and sensitive rollback fail, when restore reports failure, then both rollback failures remain suppressed on the original restore failure.
 *
 * Observable outcomes:
 * - thrown validation messages, datastore flow values, credential store values after success and rollback, and propagated exception identity.
 *
 * TDD proof:
 * - RED: successful nullable and sensitive cleanup tests fail before restore calls cleanup helpers on the success path.
 * - RED: rollback cleanup test fails if rollback restores present values but does not clear values omitted from the rollback snapshot.
 * - RED: rollback failure boundary test fails when rollback replaces the original restore failure instead of being suppressed.
 * - RED: dual rollback suppression test fails with a temporary single-suppressed expectation, proving both rollback paths execute.
 *
 * Excludes:
 * - encrypted settings file format, Android keystore encryption, UI import flow, and repository archive parsing.
 */
class DataStoreMigrationSettingsStoreTest : DataFunSpec() {
    init {
        test("given unknown migration key when restore is validated then restore fails before datastore changes") {
            runTest {
                val fixture = setUpStore()

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf("unknownSetting" to "value"),
                            ),
                        )
                    }

                failure.message.orEmpty() shouldContain "Unsupported migration preferences"
                fixture.dataStore.themeMode.first() shouldBe "system"
            }
        }

        test("given invalid boolean migration value when restore is validated then typed failure is observable") {
            runTest {
                val fixture = setUpStore()

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf(SettingsKey.GIT_SYNC_ENABLED to "enabled"),
                            ),
                        )
                    }

                failure.message.orEmpty() shouldContain "must be a boolean"
                fixture.dataStore.gitSyncEnabled.first() shouldBe false
            }
        }

        test("given invalid integer migration value when restore is validated then typed failure is observable") {
            runTest {
                val fixture = setUpStore()

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf(SettingsKey.MEMO_SNAPSHOT_MAX_COUNT to "many"),
                            ),
                        )
                    }

                failure.message.orEmpty() shouldContain "must be an integer"
            }
        }

        test("given invalid float migration value when restore is validated then typed failure is observable") {
            runTest {
                val fixture = setUpStore()

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf(SettingsKey.TYPOGRAPHY_FONT_SIZE_SCALE to "large"),
                            ),
                        )
                    }

                failure.message.orEmpty() shouldContain "must be a float"
            }
        }

        test("given nullable settings are omitted when restore succeeds then existing nullable values are cleared") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateGitRemoteUrl("https://example.invalid/repo.git")
                fixture.dataStore.updateWebDavBaseUrl("https://dav.example.invalid")
                fixture.dataStore.updateS3EndpointUrl("https://s3.example.invalid")

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences = mapOf(SettingsKey.THEME_MODE to "dark"),
                    ),
                )

                fixture.dataStore.themeMode.first() shouldBe "dark"
                fixture.dataStore.gitRemoteUrl.first() shouldBe null
                fixture.dataStore.webDavBaseUrl.first() shouldBe null
                fixture.dataStore.s3EndpointUrl.first() shouldBe null
            }
        }

        test("given sensitive credentials are omitted when restore succeeds then existing credentials are cleared") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.gitToken = "old-git-token"
                fixture.credentials.webDavPassword = "old-dav-password"
                fixture.credentials.s3SessionToken = "old-s3-session"

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        sensitive = mapOf(SettingsKey.S3_ACCESS_KEY_ID to "new-access-key"),
                    ),
                )

                fixture.credentials.gitToken shouldBe null
                fixture.credentials.webDavPassword shouldBe null
                fixture.credentials.s3AccessKeyId shouldBe "new-access-key"
                fixture.credentials.s3SessionToken shouldBe null
            }
        }

        test("given restore fails after applying omitted rollback values then rollback cleanup clears transient values") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.webDavPasswordFailures += IllegalStateException("webdav password write failed")

                shouldThrow<IllegalStateException> {
                    fixture.store.restore(
                        MigrationSettingsSnapshot(
                            preferences =
                                mapOf(
                                    SettingsKey.GIT_REMOTE_URL to "https://example.invalid/transient.git",
                                ),
                            sensitive =
                                mapOf(
                                    SettingsKey.GIT_TOKEN to "transient-git-token",
                                    SettingsKey.WEBDAV_PASSWORD to "transient-dav-password",
                                ),
                        ),
                    )
                }

                fixture.dataStore.gitRemoteUrl.first() shouldBe null
                fixture.credentials.gitToken shouldBe null
                fixture.credentials.webDavPassword shouldBe null
            }
        }

        test("given restore commit and rollback both fail when restore reports failure then rollback is suppressed") {
            runTest {
                val fixture = setUpStore()
                val restoreFailure = IllegalArgumentException("restore password write failed")
                val rollbackFailure = UnsupportedOperationException("rollback password write failed")
                fixture.credentials.webDavPassword = "original-dav-password"
                fixture.credentials.webDavPasswordFailures += restoreFailure
                fixture.credentials.webDavPasswordFailures += rollbackFailure

                val failure =
                    shouldThrow<Throwable> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                sensitive =
                                    mapOf(
                                        SettingsKey.WEBDAV_PASSWORD to "imported-dav-password",
                                    ),
                            ),
                        )
                    }

                failure shouldBe restoreFailure
                failure.suppressed.size shouldBe 1
                failure.suppressed.single() shouldBe rollbackFailure
            }
        }

        test("given preferences and sensitive rollback fail when restore reports failure then both failures are suppressed") {
            runTest {
                val fixture = setUpStore()
                val restoreFailure = IllegalArgumentException("restore preference write failed")
                val rollbackPreferenceFailure = UnsupportedOperationException("rollback preference write failed")
                val rollbackSensitiveFailure = IllegalStateException("rollback password write failed")
                fixture.credentials.webDavPassword = "original-dav-password"
                fixture.preferenceUpdateFailures += restoreFailure
                fixture.preferenceUpdateFailures += rollbackPreferenceFailure
                fixture.credentials.webDavPasswordFailures += rollbackSensitiveFailure

                val failure =
                    shouldThrow<Throwable> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    mapOf(
                                        SettingsKey.THEME_MODE to "dark",
                                    ),
                            ),
                        )
                }

                failure shouldBe restoreFailure
                failure.suppressed.size shouldBe 2
                failure.suppressed.toSet() shouldBe setOf(
                    rollbackPreferenceFailure,
                    rollbackSensitiveFailure,
                )
            }
        }
    }

    private fun TestScope.setUpStore(): SettingsStoreFixture {
        val preferenceUpdateFailures = mutableListOf<Throwable>()
        val dataStore = createLomoDataStore(
            scope = backgroundScope,
            updateFailures = preferenceUpdateFailures,
        )
        val credentials = CredentialState()
        val store =
            DataStoreMigrationSettingsStore(
                dataStore = dataStore,
                gitCredentialStore = fakeGitCredentialStore(credentials),
                webDavCredentialStore = fakeWebDavCredentialStore(credentials),
                s3CredentialStore = fakeS3CredentialStore(credentials),
            )
        return SettingsStoreFixture(
            dataStore = dataStore,
            credentials = credentials,
            preferenceUpdateFailures = preferenceUpdateFailures,
            store = store,
        )
    }

    private fun createLomoDataStore(
        scope: CoroutineScope,
        updateFailures: MutableList<Throwable>,
    ): LomoDataStore {
        val backingFile = Files.createTempFile("lomo-migration-datastore", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
        val failingDataStore = FailingPreferencesDataStore(
            delegate = realDataStore,
            updateFailures = updateFailures,
        )
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(failingDataStore)
    }

    private fun fakeGitCredentialStore(credentials: CredentialState): GitCredentialStore =
        mockk {
            every { getToken() } answers { credentials.gitToken }
            every { setToken(any()) } answers { credentials.gitToken = firstArg() }
            every { setToken(null) } answers { credentials.gitToken = null }
        }

    private fun fakeWebDavCredentialStore(credentials: CredentialState): WebDavCredentialStore =
        mockk {
            every { getUsername() } answers { credentials.webDavUsername }
            every { setUsername(any()) } answers { credentials.webDavUsername = firstArg() }
            every { setUsername(null) } answers { credentials.webDavUsername = null }
            every { getPassword() } answers { credentials.webDavPassword }
            every { setPassword(any()) } answers {
                credentials.webDavPasswordFailures.removeFirstOrNull()?.let { throw it }
                credentials.webDavPassword = firstArg()
            }
            every { setPassword(null) } answers {
                credentials.webDavPasswordFailures.removeFirstOrNull()?.let { throw it }
                credentials.webDavPassword = null
            }
        }

    private fun fakeS3CredentialStore(credentials: CredentialState): S3CredentialStore =
        mockk {
            every { getAccessKeyId() } answers { credentials.s3AccessKeyId }
            every { setAccessKeyId(any()) } answers { credentials.s3AccessKeyId = firstArg() }
            every { setAccessKeyId(null) } answers { credentials.s3AccessKeyId = null }
            every { getSecretAccessKey() } answers { credentials.s3SecretAccessKey }
            every { setSecretAccessKey(any()) } answers { credentials.s3SecretAccessKey = firstArg() }
            every { setSecretAccessKey(null) } answers { credentials.s3SecretAccessKey = null }
            every { getSessionToken() } answers { credentials.s3SessionToken }
            every { setSessionToken(any()) } answers { credentials.s3SessionToken = firstArg() }
            every { setSessionToken(null) } answers { credentials.s3SessionToken = null }
            every { getEncryptionPassword() } answers { credentials.s3EncryptionPassword }
            every { setEncryptionPassword(any()) } answers { credentials.s3EncryptionPassword = firstArg() }
            every { setEncryptionPassword(null) } answers { credentials.s3EncryptionPassword = null }
            every { getEncryptionPassword2() } answers { credentials.s3EncryptionPassword2 }
            every { setEncryptionPassword2(any()) } answers { credentials.s3EncryptionPassword2 = firstArg() }
            every { setEncryptionPassword2(null) } answers { credentials.s3EncryptionPassword2 = null }
        }
}

private data class SettingsStoreFixture(
    val dataStore: LomoDataStore,
    val credentials: CredentialState,
    val preferenceUpdateFailures: MutableList<Throwable>,
    val store: DataStoreMigrationSettingsStore,
)

private class FailingPreferencesDataStore(
    private val delegate: DataStore<Preferences>,
    private val updateFailures: MutableList<Throwable>,
) : DataStore<Preferences> {
    override val data: Flow<Preferences>
        get() = delegate.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateFailures.removeFirstOrNull()?.let { throw it }
        return delegate.updateData(transform)
    }
}

private data class CredentialState(
    var gitToken: String? = null,
    var webDavUsername: String? = null,
    var webDavPassword: String? = null,
    var s3AccessKeyId: String? = null,
    var s3SecretAccessKey: String? = null,
    var s3SessionToken: String? = null,
    var s3EncryptionPassword: String? = null,
    var s3EncryptionPassword2: String? = null,
    val webDavPasswordFailures: MutableList<Throwable> = mutableListOf(),
)
