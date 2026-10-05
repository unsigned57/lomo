package com.lomo.data.repository

// reaudit-3 adversarial checks for F-2/F-3 (audit/12-修复-Android域残留.md):
// - F-2: writeSecrets is *cancellation*-atomic — the batch commits without a suspension point.
//   A mid-batch store-layer exception is NOT atomic: earlier fields commit and the failure
//   propagates, leaving repair to the restore-level rollback. Pin the true boundary.
// - F-3: an undetermined baseline (denied/unreadable) can never certify "unchanged" — even when
//   the payload supplies the field — and a fully locked session aborts the restore at snapshot
//   time before any mutation lands, bounding the Undetermined path to post-snapshot degradation.

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.Data
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoOrdinarySettingsRestoreTransaction
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.security.SecureStringStore
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.AppPreferenceSnapshotField
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: DefaultCredentialRepository.writeSecrets + DataStoreMigrationSettingsStore
 *   + RestoreSyncIdentityPreflight
 * - Owning layer: data/security, data/repository
 * - Priority tier: P0
 * - Capability: the sensitive write batch commits without a suspension point (cancellation can
 *   only land before or after the whole unit); a store-layer exception mid-batch propagates with
 *   earlier entries committed — failure atomicity is owned by the restore-level rollback, not by
 *   writeSecrets. The identity preflight treats a denied/unreadable baseline as "cannot prove
 *   unchanged" and always reports a move, while a restore under a fully locked session never
 *   reaches the write phase at all.
 *
 * Scenarios:
 * - Given a batch whose mid-entry store write throws, when writeSecrets runs, then earlier
 *   entries are committed and the exception propagates (repair is the caller's rollback).
 * - Given a denied baseline where the payload *supplies* the credential field, when the
 *   preflight compares, then the identity still reports moved — the conservative direction.
 * - Given a session that denies credential reads, when restore is attempted, then the snapshot
 *   step itself fails before preflight, disposal, or any credential/ordinary write.
 *
 * Observable outcomes: per-key secure-store contents, propagated exception identity, mutation
 * counters, and movesSyncIdentity verdicts.
 *
 * TDD proof:
 * - The partial-commit pin would fail if writeSecrets wrapped entries in per-entry rollback;
 *   the denial pins would fail if the baseline collapsed denial to "" (F-3 regression).
 * Excludes: Android Keystore cryptography, WorkManager, UI import flow.
 */
class SettingsRestoreWriteAtomicityContractTest : DataFunSpec() {
    init {
        test("a mid-batch store-layer failure commits earlier entries and propagates — cancellation is atomic, failure is not") {
            runTest {
                val s3Store =
                    FailOnceSecureStringStore(failOnKey = "s3_secret_access_key")
                val repository =
                    DefaultCredentialRepository(
                        gitCredentialStore = GitCredentialStore(FailOnceSecureStringStore()),
                        webDavCredentialStore = WebDavCredentialStore(FailOnceSecureStringStore()),
                        s3CredentialStore = S3CredentialStore(s3Store),
                    )

                shouldThrow<IllegalStateException> {
                    repository.writeSecrets(
                        linkedMapOf(
                            CredentialField.S3_ACCESS_KEY_ID to "access",
                            CredentialField.S3_SECRET_ACCESS_KEY to "secret",
                            CredentialField.S3_SESSION_TOKEN to "token",
                        ),
                    )
                }

                // The batch is a cancellation unit, not a transaction: entries before the
                // failing one have landed, the failing one and its successors have not — the
                // restore-level rollback is what repairs a failure-split batch.
                s3Store.written["s3_access_key_id"] shouldBe "access"
                s3Store.written.containsKey("s3_secret_access_key") shouldBe false
                s3Store.written.containsKey("s3_session_token") shouldBe false
            }
        }

        test("a denied baseline where the payload supplies the credential still reports an identity move") {
            runTest {
                val dataStore = newLomoDataStore(backgroundScope)
                dataStore.setRemoteSyncBackendType("webdav")
                dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                val credentials =
                    TestCredentialRepository(
                        mutableMapOf(
                            CredentialField.WEBDAV_USERNAME to CredentialSecretReadResult.Present("alice"),
                        ),
                    )
                val preflight =
                    RestoreSyncIdentityPreflight(
                        dataStore = dataStore,
                        credentialRepository = credentials,
                        securitySessionPolicy = LockedCredentialReadSessionPolicy,
                    )

                // The payload even supplies the same field name — but a denied baseline cannot
                // prove the stored secret already equals it, so the fence moves anyway.
                preflight.movesSyncIdentity(
                    snapshot =
                        MigrationSettingsSnapshot(
                            preferences =
                                mapOf(
                                    SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                                    SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                                ),
                            sensitive = mapOf(SettingsKey.WEBDAV_STORED_USERNAME to "alice"),
                        ),
                    sensitiveSettings = mapOf(SettingsKey.WEBDAV_STORED_USERNAME to "alice"),
                    ordinaryPlan = emptyOrdinaryPlan(),
                ) shouldBe true
            }
        }

        test("a fully locked session aborts the restore at snapshot time before any mutation") {
            runTest {
                val dataStore = newLomoDataStore(backgroundScope)
                dataStore.setRemoteSyncBackendType("none")
                val credentials = TestCredentialRepository(mutableMapOf())
                val identityResetEvents = mutableListOf<String>()
                val store =
                    DataStoreMigrationSettingsStore(
                        dataStore = dataStore,
                        credentialRepository = credentials,
                        securitySessionPolicy = LockedCredentialReadSessionPolicy,
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

                val failure =
                    shouldThrow<IllegalStateException> {
                        store.restore(
                            MigrationSettingsSnapshot(
                                preferences = fullValidPreferencePayload(),
                                sensitive = mapOf(SettingsKey.GIT_TOKEN to "imported-token"),
                            ),
                        )
                    }

                // Denial is surfaced at the rollback-snapshot read — no preflight, no disposal,
                // no credential or ordinary write ever ran.
                failure.message.orEmpty() shouldContain "read denied"
                credentials.writes shouldBe emptyList()
                identityResetEvents shouldBe emptyList()
                dataStore.syncBackendType.first() shouldBe "none"
            }
        }
    }

    private fun emptyOrdinaryPlan() =
        OrdinarySettingsRestorePlan.Restore(
            LomoOrdinarySettingsRestoreTransaction(
                catalogValues = emptyMap(),
                stringValues = emptyMap(),
                nullableStringValues = emptyMap(),
                booleanValues = emptyMap(),
                intValues = emptyMap(),
            ),
        )

    private fun newLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val backingFile =
            Files.createTempFile("reaudit3-restore", ".preferences_pb").toFile().apply {
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
 * A [SecureStringStore] that records writes and throws once on [failOnKey] — modeling a
 * Keystore-layer failure that lands inside a [DefaultCredentialRepository.writeSecrets] batch.
 */
private class FailOnceSecureStringStore(
    private val failOnKey: String? = null,
) : SecureStringStore {
    val written: MutableMap<String, String?> = mutableMapOf()
    private var failed = false

    override fun readString(key: String): SecureStringReadResult =
        written[key]?.let(SecureStringReadResult::Present) ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) {
        if (key == failOnKey && !failed) {
            failed = true
            throw IllegalStateException("keystore write failed for $key")
        }
        written[key] = value
    }
}
