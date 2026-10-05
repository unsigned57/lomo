package com.lomo.data.repository

// reaudit-2 evidence for F-2/F-3 (audit/11-再复审-Android域.md):
// - F-2: the sensitive phase of a restore is one atomic cancellation unit. Cancellation may land
//   before the first or after the last credential write — never between two fields of the same
//   restore — and the same unit covers imported values and omission clears.
// - F-3: "definitely absent", "unreadable", and "denied" are three different before-states. A
//   restore payload that omits a credential must still read as an identity move when the stored
//   secret cannot be read, because the clear would otherwise land while the stale fence stands.

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.work.Data
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoOrdinarySettingsRestoreTransaction
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.AppPreferenceSnapshotField
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: DataStoreMigrationSettingsStore + RestoreSyncIdentityPreflight
 * - Owning layer: data/repository
 * - Priority tier: P0
 * - Capability: bulk restore commits sensitive credential changes as a single atomic
 *   cancellation unit, and the identity preflight treats an unreadable/denied before-read as
 *   "cannot prove unchanged" — always moving the durable fence, never skipping disposal.
 *
 * Scenarios:
 * - Given a restore cancelled inside the sensitive phase, when credentials are inspected, then
 *   every field lands in the same vintage — a full batch or none, never a partial import.
 * - Given a restore that only clears credentials is cancelled inside the sensitive phase, when
 *   credentials are inspected, then the clear is all-or-nothing too.
 * - Given an unreadable or denied before-read of the selected backend's credential, when the
 *   restore payload omits that credential (i.e. clears it), then the preflight still reports an
 *   identity move so the stale fence is disposed before the clear lands.
 * - Given a denied before-read where the payload supplies the same field name, when the
 *   preflight compares, then the result stays a move.
 *
 * Observable outcomes: credential store contents after the thrown CancellationException, number
 * of repository mutation calls, and movesSyncIdentity verdicts.
 *
 * TDD proof:
 * - RED: cancellation between two per-field writes leaves the first field imported and the rest
 *   uncleared (mixed vintage), and a denied/unreadable before-read compares equal to the
 *   payload's omitted field so the identity move is missed.
 * Excludes: archive file parsing, ordinary DataStore transaction atomicity (covered by the
 * existing suite), UI import flow.
 */
class SettingsRestoreCancelAtomicityContractTest : DataFunSpec() {
    init {
        test("given restore is cancelled inside the sensitive phase then no partial credential set survives") {
            runTest {
                val fixture = setUpRestoreFixture()
                fixture.credentials.gitToken = "old-git-token"
                fixture.credentials.gitUsername = "old-git-user"
                fixture.credentials.s3AccessKeyId = "old-s3-access"
                fixture.credentials.ceAfterMutatingCalls = 1

                shouldThrow<CancellationException> {
                    fixture.store.restore(
                        MigrationSettingsSnapshot(
                            preferences = fullValidPreferencePayload(),
                            sensitive =
                                mapOf(
                                    SettingsKey.GIT_TOKEN to "new-git-token",
                                    SettingsKey.GIT_USERNAME to "new-git-user",
                                ),
                        ),
                    )
                }

                // One atomic commit: the CE may precede or follow the whole batch, never split it.
                fixture.credentials.gitToken shouldBe "new-git-token"
                fixture.credentials.gitUsername shouldBe "new-git-user"
                // The omission-clear rides the same unit: fields absent from the payload are
                // cleared in the same commit.
                fixture.credentials.s3AccessKeyId shouldBe null
                fixture.credentials.mutatingCalls shouldBe 1
            }
        }

        test("given a clear-only restore is cancelled inside the sensitive phase then the clear is all-or-nothing") {
            runTest {
                val fixture = setUpRestoreFixture()
                fixture.credentials.gitToken = "old-git-token"
                fixture.credentials.gitUsername = "old-git-user"
                fixture.credentials.ceAfterMutatingCalls = 1

                shouldThrow<CancellationException> {
                    fixture.store.restore(
                        MigrationSettingsSnapshot(
                            preferences = fullValidPreferencePayload(),
                            sensitive = emptyMap(),
                        ),
                    )
                }

                fixture.credentials.gitToken shouldBe null
                fixture.credentials.gitUsername shouldBe null
                fixture.credentials.mutatingCalls shouldBe 1
            }
        }

        test("given an unreadable stored credential when the restore payload omits it then the identity move is still reported") {
            runTest {
                val fixture = setUpPreflightFixture()
                fixture.dataStore.setRemoteSyncBackendType("webdav")
                fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                fixture.credentials.setRead(CredentialField.WEBDAV_USERNAME, CredentialSecretReadResult.Unreadable)

                // The payload clears the stored username (omitted → cleared). An unreadable
                // before-state must not compare equal to "empty" — the fence cannot prove the
                // identity is unchanged.
                fixture.moves(
                    preferences =
                        mapOf(
                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                            SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                        ),
                    sensitive = emptyMap(),
                ) shouldBe true
            }
        }

        test("given a denied stored credential when the restore payload omits it then the identity move is still reported") {
            runTest {
                val fixture = setUpPreflightFixture(policy = LockedCredentialReadSessionPolicy)
                fixture.dataStore.setRemoteSyncBackendType("webdav")
                fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                fixture.credentials.setRead(
                    CredentialField.WEBDAV_USERNAME,
                    CredentialSecretReadResult.Present("alice"),
                )

                fixture.moves(
                    preferences =
                        mapOf(
                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                            SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                        ),
                    sensitive = emptyMap(),
                ) shouldBe true
            }
        }

        test("given a readable stored credential when the restore payload omits it then the clear still moves the identity") {
            runTest {
                val fixture = setUpPreflightFixture()
                fixture.dataStore.setRemoteSyncBackendType("webdav")
                fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                fixture.credentials.setRead(
                    CredentialField.WEBDAV_USERNAME,
                    CredentialSecretReadResult.Present("alice"),
                )

                fixture.moves(
                    preferences =
                        mapOf(
                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                            SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                        ),
                    sensitive = emptyMap(),
                ) shouldBe true
            }
        }

        test("given no stored credential when the restore payload omits it then the identity is unmoved") {
            runTest {
                val fixture = setUpPreflightFixture()
                fixture.dataStore.setRemoteSyncBackendType("webdav")
                fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                fixture.credentials.setRead(CredentialField.WEBDAV_USERNAME, CredentialSecretReadResult.Missing)

                // Genuinely-absent before + cleared after is a durable no-op on the sensitive
                // axis — disposal stays skipped.
                fixture.moves(
                    preferences =
                        mapOf(
                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                            SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                        ),
                    sensitive = emptyMap(),
                ) shouldBe false
            }
        }
    }

    private fun TestScope.setUpRestoreFixture(): RestoreFixture {
        val dataStore = newLomoDataStore(backgroundScope)
        val credentials = AtomicCredentialRepository()
        val identityResetEvents = mutableListOf<String>()
        val store =
            DataStoreMigrationSettingsStore(
                dataStore = dataStore,
                credentialRepository = credentials,
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
                identityReset =
                    SyncIdentityResetPolicy(
                        scheduler =
                            mockk<RustSyncScheduler>().also {
                                every { it.cancel() } answers { identityResetEvents += "cancel" }
                            },
                        deferredLockStore =
                            object : DeferredLockWorkStore {
                                override fun save(input: Data) = error("not used")

                                override fun take(): Data? = null

                                override fun clear() {
                                    identityResetEvents += "deferred-clear"
                                }
                            },
                        syncStateReset =
                            object : SyncStateResetRepository {
                                override suspend fun resetWorkspaceScopedSyncState() {
                                    identityResetEvents += "reset"
                                }
                            },
                    ),
            )
        return RestoreFixture(dataStore = dataStore, credentials = credentials, store = store)
    }

    private fun newLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val backingFile =
            Files.createTempFile("reaudit2-restore", ".preferences_pb").toFile().apply {
                deleteOnExit()
            }
        val realDataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { backingFile },
            )
        val constructor =
            LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }

    private class RestoreFixture(
        val dataStore: LomoDataStore,
        val credentials: AtomicCredentialRepository,
        val store: DataStoreMigrationSettingsStore,
    )

    private fun TestScope.setUpPreflightFixture(
        policy: com.lomo.domain.repository.SecuritySessionPolicy = AuthorizedCredentialReadSessionPolicy,
    ): PreflightFixture {
        val dataStore = newLomoDataStore(backgroundScope)
        val credentials = TestCredentialRepository(mutableMapOf())
        val preflight =
            RestoreSyncIdentityPreflight(
                dataStore = dataStore,
                credentialRepository = credentials,
                securitySessionPolicy = policy,
            )
        return PreflightFixture(dataStore = dataStore, credentials = credentials, preflight = preflight)
    }

    private class PreflightFixture(
        val dataStore: LomoDataStore,
        val credentials: TestCredentialRepository,
        private val preflight: RestoreSyncIdentityPreflight,
    ) {
        suspend fun moves(
            preferences: Map<String, String>,
            sensitive: Map<String, String>,
        ): Boolean =
            preflight.movesSyncIdentity(
                snapshot = MigrationSettingsSnapshot(preferences = preferences, sensitive = sensitive),
                sensitiveSettings = sensitive,
                ordinaryPlan =
                    OrdinarySettingsRestorePlan.Restore(
                        LomoOrdinarySettingsRestoreTransaction(
                            catalogValues = emptyMap(),
                            stringValues = emptyMap(),
                            nullableStringValues = emptyMap(),
                            booleanValues = emptyMap(),
                            intValues = emptyMap(),
                        ),
                    ),
            )
    }

    private fun com.lomo.domain.model.SettingDescriptor.restoreValue(): String =
        when (snapshotField) {
            AppPreferenceSnapshotField.DATE_FORMAT -> "yyyy/MM/dd"
            AppPreferenceSnapshotField.TIME_FORMAT -> "HH:mm"
            AppPreferenceSnapshotField.THEME_MODE -> ThemeMode.DARK.value
            AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS -> "2,5,9"
            AppPreferenceSnapshotField.COLOR_SOURCE -> ColorSource.Preset(ColorPresetId.OCEAN).storageValue
            AppPreferenceSnapshotField.FONT_PREFERENCE -> FontPreference.UserImported("serif.ttf").storageValue
            AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED -> false.toString()
            AppPreferenceSnapshotField.SHOW_INPUT_HINTS -> false.toString()
            AppPreferenceSnapshotField.DOUBLE_TAP_EDIT_ENABLED -> false.toString()
            AppPreferenceSnapshotField.FREE_TEXT_COPY_ENABLED -> true.toString()
            AppPreferenceSnapshotField.MEMO_ACTION_AUTO_REORDER_ENABLED -> true.toString()
            AppPreferenceSnapshotField.AUTO_OPEN_INPUT_ON_FOREGROUND -> true.toString()
            AppPreferenceSnapshotField.MEMO_ACTION_ORDER -> "copy,edit,delete"
            AppPreferenceSnapshotField.MEMO_ACTION_ORDERS_BY_SCOPE -> "main=edit,copy"
            AppPreferenceSnapshotField.INPUT_TOOLBAR_TOOL_ORDER -> "text,image,voice"
            AppPreferenceSnapshotField.QUICK_SAVE_ON_BACK_ENABLED -> false.toString()
            AppPreferenceSnapshotField.SCROLLBAR_ENABLED -> false.toString()
            AppPreferenceSnapshotField.SHARE_CARD_SHOW_TIME -> false.toString()
            AppPreferenceSnapshotField.SHARE_CARD_SHOW_BRAND -> false.toString()
            AppPreferenceSnapshotField.SHARE_CARD_SIGNATURE_TEXT -> "Imported"
            AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE -> "1.25"
            AppPreferenceSnapshotField.TYPOGRAPHY_LINE_HEIGHT_SCALE -> "1.35"
            AppPreferenceSnapshotField.TYPOGRAPHY_LETTER_SPACING_SCALE -> "0.95"
            AppPreferenceSnapshotField.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE -> "1.45"
        }

    private fun fullValidPreferencePayload(
        overrides: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val catalogPreferences =
            SettingsCatalog
                .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                .associate { descriptor -> descriptor.storageKey to descriptor.restoreValue() }
        val ordinaryPreferences =
            mapOf(
                SettingsKey.CHECK_UPDATES_ON_STARTUP to false.toString(),
                SettingsKey.SIDEBAR_TAG_ORDER to "work,home",
                SettingsKey.APP_LOCK_ENABLED to true.toString(),
                SettingsKey.LAN_SHARE_ENABLED to true.toString(),
                SettingsKey.SYNC_INBOX_ENABLED to true.toString(),
                SettingsKey.MEMO_SNAPSHOTS_ENABLED to true.toString(),
                SettingsKey.MEMO_SNAPSHOT_MAX_COUNT to "7",
                SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS to "90",
                SettingsKey.STORAGE_FILENAME_FORMAT to "{{title}}",
                SettingsKey.STORAGE_TIMESTAMP_FORMAT to "yyyyMMddHHmmss",
                SettingsKey.GIT_SYNC_ENABLED to false.toString(),
                SettingsKey.GIT_AUTHOR_NAME to "Alice",
                SettingsKey.GIT_AUTHOR_EMAIL to "alice@example.invalid",
                SettingsKey.GIT_AUTO_SYNC_ENABLED to false.toString(),
                SettingsKey.GIT_AUTO_SYNC_INTERVAL to "15",
                SettingsKey.GIT_SYNC_ON_REFRESH to false.toString(),
                SettingsKey.SYNC_BACKEND_TYPE to "none",
                SettingsKey.WEBDAV_SYNC_ENABLED to false.toString(),
                SettingsKey.WEBDAV_PROVIDER to "custom",
                SettingsKey.WEBDAV_AUTO_SYNC_ENABLED to false.toString(),
                SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL to "30",
                SettingsKey.WEBDAV_SYNC_ON_REFRESH to true.toString(),
                SettingsKey.S3_SYNC_ENABLED to false.toString(),
                SettingsKey.S3_PATH_STYLE to true.toString(),
                SettingsKey.S3_ENCRYPTION_MODE to "none",
                SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION to "standard",
                SettingsKey.S3_RCLONE_FILENAME_ENCODING to "base32",
                SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION to true.toString(),
                SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED to false.toString(),
                SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX to ".bin",
                SettingsKey.S3_AUTO_SYNC_ENABLED to false.toString(),
                SettingsKey.S3_AUTO_SYNC_INTERVAL to "60",
                SettingsKey.S3_SYNC_ON_REFRESH to true.toString(),
            )
        return catalogPreferences + ordinaryPreferences + overrides
    }
}

/**
 * Records every mutating call and applies each one fully or not at all — the batch write applies
 * its entries synchronously, so a [ceAfterMutatingCalls] cancellation lands only at a commit
 * boundary (before the next call starts or after the current one finished).
 */
private class AtomicCredentialRepository : CredentialRepository {
    private val values = mutableMapOf<CredentialField, String?>()
    var mutatingCalls = 0
    var ceAfterMutatingCalls = -1

    var gitToken: String?
        get() = values[CredentialField.GIT_TOKEN]
        set(value) {
            values[CredentialField.GIT_TOKEN] = value
        }
    var gitUsername: String?
        get() = values[CredentialField.GIT_USERNAME]
        set(value) {
            values[CredentialField.GIT_USERNAME] = value
        }
    var s3AccessKeyId: String?
        get() = values[CredentialField.S3_ACCESS_KEY_ID]
        set(value) {
            values[CredentialField.S3_ACCESS_KEY_ID] = value
        }

    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(
            CredentialState(
                provider,
                fieldsForProvider(provider).map { field ->
                    CredentialFieldState(
                        field,
                        if (values[field].isNullOrBlank()) {
                            StoredCredentialStatus.Missing
                        } else {
                            StoredCredentialStatus.Present
                        },
                    )
                },
            ),
        )

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        CredentialState(provider, emptyList())

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult =
        if (authorization is CredentialReadAuthorization.Denied) {
            CredentialSecretReadResult.Unauthorized(CredentialReadDenialReason.SecuritySessionLocked)
        } else {
            values[field]
                ?.takeIf(String::isNotBlank)
                ?.let(CredentialSecretReadResult::Present)
                ?: CredentialSecretReadResult.Missing
        }

    override suspend fun writeSecrets(values: Map<CredentialField, String?>) {
        mutatingCalls++
        // One batch = one atomic commit: all entries land before any cancellation boundary.
        values.forEach { (field, value) -> this.values[field] = value }
        throwIfCancellationPoint()
    }

    private fun throwIfCancellationPoint() {
        if (mutatingCalls == ceAfterMutatingCalls) {
            throw CancellationException("cancelled inside sensitive restore phase")
        }
    }

    private fun fieldsForProvider(provider: CredentialProvider): List<CredentialField> =
        when (provider) {
            CredentialProvider.GIT -> listOf(CredentialField.GIT_TOKEN, CredentialField.GIT_USERNAME)
            CredentialProvider.WEBDAV -> listOf(CredentialField.WEBDAV_USERNAME, CredentialField.WEBDAV_PASSWORD)
            CredentialProvider.S3 ->
                listOf(
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                    CredentialField.S3_SESSION_TOKEN,
                    CredentialField.S3_ENCRYPTION_PASSWORD,
                    CredentialField.S3_ENCRYPTION_PASSWORD2,
                )
        }
}
