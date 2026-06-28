package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.AppPreferenceSnapshotField
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * - Given WebDAV username still exists in legacy DataStore, when settings are snapshotted, then it is drained into credential-sensitive state and ordinary preferences do not export it.
 * - Given a restored archive contains legacy ordinary WebDAV username, when restore commits, then the username is written to credential-sensitive state and cleared from ordinary preferences.
 * - Given WebDAV provider settings rely on a blank legacy ordinary WebDAV username, when restore is attempted, then required credential coverage fails before credentials or ordinary settings mutate.
 * - Given catalog-covered app preferences exist, when settings are snapshotted, then every descriptor storage key is exported from the catalog path.
 * - Given a restore contains catalog descriptor storage keys, when restore commits, then app preferences are written through those descriptor keys.
 * - Given constrained catalog text values are unsupported, when restore is validated, then restore fails before invalid values are persisted.
 * - Given catalog-covered ordinary settings are omitted, when restore is dry-run validated,
 *   then structured coverage identifies the missing descriptor keys before commit.
 * - Given one ordinary setting is invalid, when restore is attempted, then no other ordinary
 *   setting is partially written.
 * - Given an archive contains only sensitive settings, when restore commits, then ordinary
 *   DataStore preferences are not restored or cleared.
 * - Given an archive contains only legacy credential-drain preferences, when restore commits,
 *   then ordinary DataStore preferences are not restored or cleared.
 * - Given an archive contains partial ordinary settings, when restore is attempted, then
 *   missing coverage blocks the restore before any ordinary write.
 * - Given ordinary settings are valid, when restore commits, then they are applied through one
 *   validated DataStore transaction.
 * - Given ordinary provider settings require credentials, when restore is validated, then missing
 *   required sensitive fields are reported before any ordinary write can run.
 * - Given ordinary provider settings require credentials, when required sensitive values are blank,
 *   then unusable values are reported before credential import or ordinary writes can run.
 * - Given a required credential import fails, when restore is attempted, then no ordinary provider
 *   settings are made effective through DataStore.
 * - Given ordinary provider settings and credentials are valid, when restore succeeds, then
 *   credentials are committed before the ordinary DataStore transaction.
 *
 * Observable outcomes:
 * - thrown validation messages, datastore flow values, credential store values after success and rollback, and propagated exception identity.
 *
 * TDD proof:
 * - RED: successful nullable and sensitive cleanup tests fail before restore calls cleanup helpers on the success path.
 * - RED: rollback cleanup test fails if rollback restores present values but does not clear values omitted from the rollback snapshot.
 * - RED: rollback failure boundary test fails when rollback replaces the original restore failure instead of being suppressed.
 * - RED: dual rollback suppression test fails with a temporary single-suppressed expectation, proving both rollback paths execute.
 * - RED: catalog export/restore tests fail while migration uses hand-maintained camelCase setting keys and misses descriptor storage keys.
 * - RED: constrained descriptor text validation test fails while catalog parsing accepts unsupported text unchanged.
 * - RED: coverage test fails before validateRestore returns a structured manifest report.
 * - RED: atomic commit test fails while ordinary settings restore performs multiple DataStore writes.
 * - RED: sensitive-only restore test fails before empty ordinary preferences are modeled as an
 *   ordinary no-op because nullable ordinary settings are cleared.
 * - RED: legacy credential-only restore test fails while legacy credential-drain preferences are
 *   treated as partial ordinary coverage.
 * - RED: partial ordinary coverage restore test fails if missing coverage can reach ordinary writes.
 * - RED: provider-sensitive coverage test fails before validation derives required credential
 *   fields from the restored ordinary provider state.
 * - RED: blank required credential tests fail while required sensitive coverage treats key presence
 *   as sufficient and restore reaches credential import plus ordinary writes.
 * - RED: blank legacy WebDAV username test fails while legacy drain coverage treats ordinary key
 *   presence as sufficient and restore reaches credential import plus ordinary writes.
 * - RED: staged credential failure test fails while restore writes ordinary settings before
 *   credential import can fail and then relies on rollback.
 * - RED: commit ordering test fails while the ordinary transaction is recorded before credential
 *   writes.
 *
 * Excludes:
 * - encrypted settings file format, Android keystore encryption, UI import flow, and repository archive parsing.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
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
                                preferences = mapOf("typography_font_size_scale" to "large"),
                            ),
                        )
                    }

                failure.message.orEmpty() shouldContain "must be a float"
            }
        }

        test("given unsupported constrained catalog text values when restore is validated then failure is observable") {
            runTest {
                val invalidValues =
                    mapOf(
                        "theme_mode" to "midnight",
                        "color_source" to "seeded",
                        "font_preference" to "serif",
                    )

                invalidValues.forEach { (key, value) ->
                    val fixture = setUpStore()

                    val failure =
                        shouldThrow<IllegalArgumentException> {
                            fixture.store.restore(
                                MigrationSettingsSnapshot(
                                    preferences = mapOf(key to value),
                                ),
                            )
                        }

                    failure.message.orEmpty() shouldContain "Migration setting $key"
                    failure.message.orEmpty() shouldContain "unsupported value"
                    fixture.dataStore.themeMode.first() shouldBe ThemeMode.SYSTEM.value
                    fixture.dataStore.colorSource.first() shouldBe ColorSource.DynamicWallpaper.storageValue
                    fixture.dataStore.fontPreference.first() shouldBe FontPreference.SystemDefault.storageValue
                }
            }
        }

        test("given catalog ordinary settings are omitted when restore is validated then structured coverage reports them") {
            runTest {
                val fixture = setUpStore()
                val providedKey = "theme_mode"

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.validateRestore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf(providedKey to ThemeMode.DARK.value),
                            ),
                        )
                    }

                failure.report.ordinary.providedCatalogKeys shouldBe setOf(providedKey)
                failure.report.ordinary.missingCatalogKeys shouldBe
                    SettingsCatalog
                        .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                        .map { descriptor -> descriptor.storageKey }
                        .toSet() - providedKey
                failure.report.ordinary.missingRequiredKeys.containsAll(
                    failure.report.ordinary.missingCatalogKeys,
                ) shouldBe true
                fixture.dataStore.themeMode.first() shouldBe ThemeMode.SYSTEM.value
            }
        }

        test("given one ordinary setting is invalid when restore is attempted then no ordinary setting is written") {
            runTest {
                val fixture = setUpStore()
                val preferences =
                    fullValidPreferencePayload() +
                        mapOf(
                            "theme_mode" to ThemeMode.DARK.value,
                            SettingsKey.GIT_SYNC_ENABLED to "enabled",
                        )

                shouldThrow<IllegalArgumentException> {
                    fixture.store.restore(MigrationSettingsSnapshot(preferences = preferences))
                }

                fixture.dataStore.themeMode.first() shouldBe ThemeMode.SYSTEM.value
                fixture.dataStore.gitSyncEnabled.first() shouldBe false
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given nullable settings are omitted when restore succeeds then existing nullable values are cleared") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateGitRemoteUrl("https://example.invalid/repo.git")
                fixture.dataStore.updateWebDavBaseUrl("https://dav.example.invalid")
                fixture.dataStore.updateS3EndpointUrl("https://s3.example.invalid")
                fixture.preferenceUpdates.reset()

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences = fullValidPreferencePayload(mapOf("theme_mode" to "dark")),
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
                fixture.dataStore.updateGitRemoteUrl("https://example.invalid/keep.git")
                fixture.dataStore.updateWebDavBaseUrl("https://dav.example.invalid/keep")
                fixture.dataStore.updateS3EndpointUrl("https://s3.example.invalid/keep")
                fixture.preferenceUpdates.reset()

                val report =
                    fixture.store.validateRestore(
                        MigrationSettingsSnapshot(
                            sensitive = mapOf(SettingsKey.S3_ACCESS_KEY_ID to "new-access-key"),
                        ),
                    )

                report.ordinary.providedOrdinaryKeys shouldBe emptySet()
                report.ordinary.missingRequiredKeys.contains(SettingsKey.GIT_SYNC_ENABLED) shouldBe true
                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        sensitive = mapOf(SettingsKey.S3_ACCESS_KEY_ID to "new-access-key"),
                    ),
                )

                fixture.credentials.gitToken shouldBe null
                fixture.credentials.webDavPassword shouldBe null
                fixture.credentials.s3AccessKeyId shouldBe "new-access-key"
                fixture.credentials.s3SessionToken shouldBe null
                fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.invalid/keep.git"
                fixture.dataStore.webDavBaseUrl.first() shouldBe "https://dav.example.invalid/keep"
                fixture.dataStore.s3EndpointUrl.first() shouldBe "https://s3.example.invalid/keep"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given only legacy WebDAV username when restore succeeds then ordinary settings are preserved") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateGitRemoteUrl("https://example.invalid/keep.git")
                fixture.dataStore.updateWebDavBaseUrl("https://dav.example.invalid/keep")
                fixture.dataStore.updateS3EndpointUrl("https://s3.example.invalid/keep")
                fixture.preferenceUpdates.reset()

                val report =
                    fixture.store.validateRestore(
                        MigrationSettingsSnapshot(
                            preferences = mapOf(SettingsKey.WEBDAV_USERNAME to "alice"),
                        ),
                    )

                report.ordinary.providedOrdinaryKeys shouldBe emptySet()
                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences = mapOf(SettingsKey.WEBDAV_USERNAME to "alice"),
                    ),
                )

                fixture.credentials.webDavUsername shouldBe "alice"
                fixture.dataStore.webDavUsername.first() shouldBe null
                fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.invalid/keep.git"
                fixture.dataStore.webDavBaseUrl.first() shouldBe "https://dav.example.invalid/keep"
                fixture.dataStore.s3EndpointUrl.first() shouldBe "https://s3.example.invalid/keep"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given legacy datastore WebDAV username when snapshot is built then username is sensitive only") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateWebDavUsername("alice")

                val snapshot = fixture.store.snapshot()

                snapshot.preferences.containsKey(SettingsKey.WEBDAV_USERNAME) shouldBe false
                snapshot.sensitive[SettingsKey.WEBDAV_STORED_USERNAME] shouldBe "alice"
                fixture.dataStore.webDavUsername.first() shouldBe null
                fixture.credentials.webDavUsername shouldBe "alice"
            }
        }

        test("given legacy ordinary WebDAV username when restore succeeds then username is credential only") {
            runTest {
                val fixture = setUpStore()

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences =
                            fullValidPreferencePayload(
                                mapOf(
                                    SettingsKey.WEBDAV_USERNAME to "alice",
                                    "theme_mode" to "dark",
                                ),
                            ),
                    ),
                )

                fixture.dataStore.themeMode.first() shouldBe "dark"
                fixture.dataStore.webDavUsername.first() shouldBe null
                fixture.credentials.webDavUsername shouldBe "alice"
            }
        }

        test("given catalog-covered app preferences when snapshot is built then descriptor storage keys are exported") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateDateFormat("yyyy/MM/dd")
                fixture.dataStore.updateColorSource(ColorSource.Preset(ColorPresetId.OCEAN).storageValue)
                fixture.dataStore.updateFontPreference(FontPreference.UserImported("serif.ttf").storageValue)
                fixture.dataStore.updateShareCardSignatureText("Imported")
                fixture.dataStore.updateFontSizeScale(1.25f)

                val snapshot = fixture.store.snapshot()
                val descriptors = SettingsCatalog.descriptorsFor(SettingsReadModel.APP_PREFERENCES)

                snapshot.preferences.keys
                    .filter { key -> key in descriptors.map { descriptor -> descriptor.storageKey }.toSet() }
                    .shouldContainExactlyInAnyOrder(descriptors.map { descriptor -> descriptor.storageKey })
                snapshot.preferences.getValue("date_format_only") shouldBe "yyyy/MM/dd"
                snapshot.preferences.getValue("color_source") shouldBe
                    ColorSource.Preset(ColorPresetId.OCEAN).storageValue
                snapshot.preferences.getValue("font_preference") shouldBe
                    FontPreference.UserImported("serif.ttf").storageValue
                snapshot.preferences.getValue("share_card_signature_text") shouldBe "Imported"
                snapshot.preferences.getValue("typography_font_size_scale") shouldBe "1.25"
            }
        }

        test("given catalog descriptor storage keys when restore succeeds then app preferences are restored") {
            runTest {
                val fixture = setUpStore()

                fixture.store.restore(MigrationSettingsSnapshot(preferences = fullValidPreferencePayload()))

                fixture.dataStore.dateFormat.first() shouldBe "yyyy/MM/dd"
                fixture.dataStore.timeFormat.first() shouldBe "HH:mm"
                fixture.dataStore.themeMode.first() shouldBe "dark"
                fixture.dataStore.colorSource.first() shouldBe ColorSource.Preset(ColorPresetId.OCEAN).storageValue
                fixture.dataStore.fontPreference.first() shouldBe FontPreference.UserImported("serif.ttf").storageValue
                fixture.dataStore.hapticFeedbackEnabled.first() shouldBe false
                fixture.dataStore.showInputHints.first() shouldBe false
                fixture.dataStore.doubleTapEditEnabled.first() shouldBe false
                fixture.dataStore.freeTextCopyEnabled.first() shouldBe true
                fixture.dataStore.memoActionAutoReorderEnabled.first() shouldBe true
                fixture.dataStore.memoActionOrder.first() shouldBe "copy,edit,delete"
                fixture.dataStore.memoActionOrdersByScope.first() shouldBe "main=edit,copy"
                fixture.dataStore.inputToolbarToolOrder.first() shouldBe "text,image,voice"
                fixture.dataStore.quickSaveOnBackEnabled.first() shouldBe false
                fixture.dataStore.scrollbarEnabled.first() shouldBe false
                fixture.dataStore.shareCardShowTime.first() shouldBe false
                fixture.dataStore.shareCardShowBrand.first() shouldBe false
                fixture.dataStore.shareCardSignatureText.first() shouldBe "Imported"
                listOf(
                    fixture.dataStore.fontSizeScale.first(),
                    fixture.dataStore.lineHeightScale.first(),
                    fixture.dataStore.letterSpacingScale.first(),
                    fixture.dataStore.paragraphSpacingScale.first(),
                ).shouldContainExactly(listOf(1.25f, 1.35f, 0.95f, 1.45f))
            }
        }

        test("given valid ordinary settings when restore succeeds then one datastore transaction commits them") {
            runTest {
                val fixture = setUpStore()

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences =
                            fullValidPreferencePayload(
                                mapOf(
                                    "theme_mode" to ThemeMode.DARK.value,
                                    SettingsKey.GIT_REMOTE_URL to "https://example.invalid/repo.git",
                                    SettingsKey.S3_LOCAL_SYNC_DIRECTORY to "content://tree/local-sync",
                                ),
                            ),
                    ),
                )

                fixture.preferenceUpdates.updateCallCount shouldBe 1
                fixture.dataStore.themeMode.first() shouldBe ThemeMode.DARK.value
                fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.invalid/repo.git"
                fixture.dataStore.s3LocalSyncDirectory.first() shouldBe "content://tree/local-sync"
            }
        }

        test("given partial ordinary coverage when restore is attempted then no ordinary setting is written") {
            runTest {
                val fixture = setUpStore()
                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences = mapOf("theme_mode" to ThemeMode.DARK.value),
                            ),
                        )
                    }

                failure.report.ordinary.providedOrdinaryKeys shouldBe setOf("theme_mode")
                failure.report.ordinary.missingRequiredKeys.contains(SettingsKey.GIT_SYNC_ENABLED) shouldBe true
                fixture.dataStore.themeMode.first() shouldBe ThemeMode.SYSTEM.value
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given restored providers require credentials when restore is validated then missing sensitive coverage is reported") {
            runTest {
                val fixture = setUpStore()

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.validateRestore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(
                                        mapOf(
                                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                                            SettingsKey.GIT_SYNC_ENABLED to true.toString(),
                                            SettingsKey.WEBDAV_SYNC_ENABLED to true.toString(),
                                            SettingsKey.S3_SYNC_ENABLED to true.toString(),
                                            SettingsKey.S3_ENCRYPTION_MODE to "rclone_crypt",
                                        ),
                                    ),
                                sensitive =
                                    mapOf(
                                        SettingsKey.GIT_TOKEN to "git-token",
                                        SettingsKey.WEBDAV_STORED_USERNAME to "alice",
                                        SettingsKey.S3_ACCESS_KEY_ID to "s3-access-key",
                                    ),
                            ),
                        )
                    }

                failure.report.sensitive.missingRequiredKeys shouldBe
                    setOf(
                        SettingsKey.WEBDAV_PASSWORD,
                        SettingsKey.S3_SECRET_ACCESS_KEY,
                        SettingsKey.S3_ENCRYPTION_PASSWORD,
                    )
                failure.report.manifest.schemaVersion shouldBe
                    DataStoreMigrationSettingsStore.migrationSettingsSchemaVersion
                failure.report.manifest.destructiveActions shouldBe
                    setOf(
                        MigrationSettingsRestoreAction.ORDINARY_SETTINGS_TRANSACTION,
                        MigrationSettingsRestoreAction.CREDENTIAL_IMPORT,
                        MigrationSettingsRestoreAction.CREDENTIAL_CLEAR,
                    )
                failure.report.manifest.reAuthRequiredActions shouldBe
                    setOf(MigrationSettingsRestoreAction.CREDENTIAL_IMPORT)
                failure.report.manifest.unsupportedPreferenceKeys shouldBe emptySet()
                failure.report.manifest.unsupportedSensitiveKeys shouldBe emptySet()
                failure.report.sensitive.missingSensitiveKeys.contains(SettingsKey.S3_SESSION_TOKEN) shouldBe true
                fixture.preferenceUpdates.updateCallCount shouldBe 0
                fixture.dataStore.webDavSyncEnabled.first() shouldBe false
                fixture.dataStore.s3SyncEnabled.first() shouldBe false
            }
        }

        test("given Git provider restore has blank required token when restore is attempted then settings and credentials are preserved") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.gitToken = "old-git-token"
                fixture.dataStore.updateGitSyncEnabled(false)
                fixture.dataStore.updateSyncBackendType("none")
                fixture.preferenceUpdates.reset()

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(
                                        mapOf(
                                            SettingsKey.GIT_SYNC_ENABLED to true.toString(),
                                        ),
                                    ),
                                sensitive = mapOf(SettingsKey.GIT_TOKEN to " "),
                            ),
                        )
                    }

                failure.report.sensitive.invalidRequiredKeys shouldBe setOf(SettingsKey.GIT_TOKEN)
                failure.report.sensitive.providedRequiredKeys shouldBe emptySet()
                fixture.credentials.gitToken shouldBe "old-git-token"
                fixture.dataStore.gitSyncEnabled.first() shouldBe false
                fixture.dataStore.syncBackendType.first() shouldBe "none"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given WebDAV provider restore has whitespace required password when restore is attempted then settings and credentials are preserved") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.webDavUsername = "old-dav-user"
                fixture.credentials.webDavPassword = "old-dav-password"
                fixture.dataStore.updateWebDavSyncEnabled(false)
                fixture.dataStore.updateSyncBackendType("none")
                fixture.preferenceUpdates.reset()

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(
                                        mapOf(
                                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                                            SettingsKey.WEBDAV_SYNC_ENABLED to true.toString(),
                                        ),
                                    ),
                                sensitive =
                                    mapOf(
                                        SettingsKey.WEBDAV_STORED_USERNAME to "alice",
                                        SettingsKey.WEBDAV_PASSWORD to "\t\n ",
                                    ),
                            ),
                        )
                    }

                failure.report.sensitive.invalidRequiredKeys shouldBe setOf(SettingsKey.WEBDAV_PASSWORD)
                failure.report.sensitive.providedRequiredKeys shouldBe setOf(SettingsKey.WEBDAV_STORED_USERNAME)
                fixture.credentials.webDavUsername shouldBe "old-dav-user"
                fixture.credentials.webDavPassword shouldBe "old-dav-password"
                fixture.dataStore.webDavSyncEnabled.first() shouldBe false
                fixture.dataStore.syncBackendType.first() shouldBe "none"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given WebDAV provider restore has blank legacy username when restore is attempted then settings and credentials are preserved") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.webDavUsername = "old-dav-user"
                fixture.credentials.webDavPassword = "old-dav-password"
                fixture.dataStore.updateWebDavSyncEnabled(false)
                fixture.dataStore.updateSyncBackendType("none")
                fixture.preferenceUpdates.reset()
                val operationLogBeforeRestore = fixture.operationLog.toList()

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(
                                        mapOf(
                                            SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                                            SettingsKey.WEBDAV_SYNC_ENABLED to true.toString(),
                                            SettingsKey.WEBDAV_USERNAME to " \t\n ",
                                        ),
                                    ),
                                sensitive =
                                    mapOf(
                                        SettingsKey.WEBDAV_PASSWORD to "imported-dav-password",
                                    ),
                            ),
                        )
                    }

                failure.report.sensitive.missingRequiredKeys shouldBe setOf(SettingsKey.WEBDAV_STORED_USERNAME)
                failure.report.sensitive.providedRequiredKeys shouldBe setOf(SettingsKey.WEBDAV_PASSWORD)
                fixture.credentials.webDavUsername shouldBe "old-dav-user"
                fixture.credentials.webDavPassword shouldBe "old-dav-password"
                fixture.dataStore.webDavSyncEnabled.first() shouldBe false
                fixture.dataStore.syncBackendType.first() shouldBe "none"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
                fixture.operationLog shouldBe operationLogBeforeRestore
            }
        }

        test("given S3 provider restore has blank required secret key when restore is attempted then settings and credentials are preserved") {
            runTest {
                val fixture = setUpStore()
                fixture.credentials.s3AccessKeyId = "old-s3-access-key"
                fixture.credentials.s3SecretAccessKey = "old-s3-secret-key"
                fixture.dataStore.updateS3SyncEnabled(false)
                fixture.dataStore.updateSyncBackendType("none")
                fixture.preferenceUpdates.reset()

                val failure =
                    shouldThrow<MigrationSettingsCoverageException> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(
                                        mapOf(
                                            SettingsKey.SYNC_BACKEND_TYPE to "s3",
                                            SettingsKey.S3_SYNC_ENABLED to true.toString(),
                                        ),
                                    ),
                                sensitive =
                                    mapOf(
                                        SettingsKey.S3_ACCESS_KEY_ID to "s3-access-key",
                                        SettingsKey.S3_SECRET_ACCESS_KEY to " ",
                                    ),
                            ),
                        )
                    }

                failure.report.sensitive.invalidRequiredKeys shouldBe setOf(SettingsKey.S3_SECRET_ACCESS_KEY)
                failure.report.sensitive.providedRequiredKeys shouldBe setOf(SettingsKey.S3_ACCESS_KEY_ID)
                fixture.credentials.s3AccessKeyId shouldBe "old-s3-access-key"
                fixture.credentials.s3SecretAccessKey shouldBe "old-s3-secret-key"
                fixture.dataStore.s3SyncEnabled.first() shouldBe false
                fixture.dataStore.syncBackendType.first() shouldBe "none"
                fixture.preferenceUpdates.updateCallCount shouldBe 0
            }
        }

        test("given required credential import fails when restore is attempted then ordinary provider settings are not written") {
            runTest {
                val fixture = setUpStore()
                fixture.dataStore.updateSyncBackendType("none")
                fixture.dataStore.updateWebDavSyncEnabled(false)
                fixture.credentials.webDavPasswordFailures += IllegalStateException("webdav password staging failed")
                fixture.preferenceUpdates.reset()

                shouldThrow<IllegalStateException> {
                    fixture.store.restore(
                        MigrationSettingsSnapshot(
                            preferences =
                                fullValidPreferencePayload(
                                    mapOf(
                                        SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                                        SettingsKey.WEBDAV_SYNC_ENABLED to true.toString(),
                                    ),
                                ),
                            sensitive =
                                mapOf(
                                    SettingsKey.WEBDAV_STORED_USERNAME to "alice",
                                    SettingsKey.WEBDAV_PASSWORD to "imported-dav-password",
                                ),
                        ),
                    )
                }

                fixture.preferenceUpdates.updateCallCount shouldBe 0
                fixture.dataStore.syncBackendType.first() shouldBe "none"
                fixture.dataStore.webDavSyncEnabled.first() shouldBe false
                fixture.credentials.webDavUsername shouldBe null
                fixture.credentials.webDavPassword shouldBe null
            }
        }

        test("given valid provider settings and credentials when restore succeeds then credentials commit before ordinary settings") {
            runTest {
                val fixture = setUpStore()

                fixture.store.restore(
                    MigrationSettingsSnapshot(
                        preferences =
                            fullValidPreferencePayload(
                                mapOf(
                                    SettingsKey.SYNC_BACKEND_TYPE to "s3",
                                    SettingsKey.S3_SYNC_ENABLED to true.toString(),
                                ),
                            ),
                        sensitive =
                            mapOf(
                                SettingsKey.S3_ACCESS_KEY_ID to "s3-access-key",
                                SettingsKey.S3_SECRET_ACCESS_KEY to "s3-secret-key",
                            ),
                    ),
                )

                fixture.operationLog.take(3) shouldBe
                    listOf(
                        "credential:S3_ACCESS_KEY_ID=s3-access-key",
                        "credential:S3_SECRET_ACCESS_KEY=s3-secret-key",
                        "ordinary",
                    )
                fixture.dataStore.syncBackendType.first() shouldBe "s3"
                fixture.dataStore.s3SyncEnabled.first() shouldBe true
                fixture.credentials.s3AccessKeyId shouldBe "s3-access-key"
                fixture.credentials.s3SecretAccessKey shouldBe "s3-secret-key"
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
                                fullValidPreferencePayload(
                                    mapOf(
                                        SettingsKey.GIT_REMOTE_URL to "https://example.invalid/transient.git",
                                    ),
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
                fixture.preferenceUpdates.failures += restoreFailure
                fixture.preferenceUpdates.failures += rollbackPreferenceFailure
                fixture.credentials.webDavPasswordFailures += rollbackSensitiveFailure

                val failure =
                    shouldThrow<Throwable> {
                        fixture.store.restore(
                            MigrationSettingsSnapshot(
                                preferences =
                                    fullValidPreferencePayload(mapOf("theme_mode" to "dark")),
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
                SettingsKey.LAN_SHARE_E2E_ENABLED to false.toString(),
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

    private fun TestScope.setUpStore(): SettingsStoreFixture {
        val operationLog = mutableListOf<String>()
        val preferenceUpdates = PreferenceUpdateControl()
        val dataStore = createLomoDataStore(
            scope = backgroundScope,
            preferenceUpdates = preferenceUpdates,
            operationLog = operationLog,
        )
        val credentials = CredentialFixtureState()
        val store =
            DataStoreMigrationSettingsStore(
                dataStore = dataStore,
                credentialRepository = FakeCredentialRepository(credentials, operationLog),
                securitySessionPolicy = AuthorizedSecuritySessionPolicy,
            )
        return SettingsStoreFixture(
            dataStore = dataStore,
            credentials = credentials,
            preferenceUpdates = preferenceUpdates,
            operationLog = operationLog,
            store = store,
        )
    }

    private fun createLomoDataStore(
        scope: CoroutineScope,
        preferenceUpdates: PreferenceUpdateControl,
        operationLog: MutableList<String>,
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
            preferenceUpdates = preferenceUpdates,
            operationLog = operationLog,
        )
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(failingDataStore)
    }

}

private data class SettingsStoreFixture(
    val dataStore: LomoDataStore,
    val credentials: CredentialFixtureState,
    val preferenceUpdates: PreferenceUpdateControl,
    val operationLog: List<String>,
    val store: DataStoreMigrationSettingsStore,
)

private class PreferenceUpdateControl {
    val failures: MutableList<Throwable> = mutableListOf()
    var updateCallCount: Int = 0

    fun reset() {
        failures.clear()
        updateCallCount = 0
    }
}

private class FailingPreferencesDataStore(
    private val delegate: DataStore<Preferences>,
    private val preferenceUpdates: PreferenceUpdateControl,
    private val operationLog: MutableList<String>,
) : DataStore<Preferences> {
    override val data: Flow<Preferences>
        get() = delegate.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        preferenceUpdates.updateCallCount += 1
        preferenceUpdates.failures.removeFirstOrNull()?.let { throw it }
        operationLog += "ordinary"
        return delegate.updateData(transform)
    }
}

private data class CredentialFixtureState(
    var lanPairingKeyHex: String? = null,
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

private object AuthorizedSecuritySessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization = CredentialReadAuthorization.Authorized
}

private class FakeCredentialRepository(
    private val credentials: CredentialFixtureState,
    private val operationLog: MutableList<String>,
) : CredentialRepository {
    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(stateFor(provider))

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        stateFor(provider)

    private fun stateFor(provider: CredentialProvider): CredentialState =
        CredentialState(
            provider = provider,
            fields =
                fieldsForProvider(provider).map { field ->
                    CredentialFieldState(field = field, status = statusFor(field))
                },
        )

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult {
        if (authorization is CredentialReadAuthorization.Denied) {
            return CredentialSecretReadResult.Unauthorized(authorization.reason)
        }
        return readValue(field)?.let(CredentialSecretReadResult::Present) ?: CredentialSecretReadResult.Missing
    }

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) {
        if (field == CredentialField.WEBDAV_PASSWORD) {
            credentials.webDavPasswordFailures.removeFirstOrNull()?.let { throw it }
        }
        operationLog += "credential:${field.name}=${value ?: "<cleared>"}"
        when (field) {
            CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> credentials.lanPairingKeyHex = value
            CredentialField.GIT_TOKEN -> credentials.gitToken = value
            CredentialField.WEBDAV_USERNAME -> credentials.webDavUsername = value
            CredentialField.WEBDAV_PASSWORD -> credentials.webDavPassword = value
            CredentialField.S3_ACCESS_KEY_ID -> credentials.s3AccessKeyId = value
            CredentialField.S3_SECRET_ACCESS_KEY -> credentials.s3SecretAccessKey = value
            CredentialField.S3_SESSION_TOKEN -> credentials.s3SessionToken = value
            CredentialField.S3_ENCRYPTION_PASSWORD -> credentials.s3EncryptionPassword = value
            CredentialField.S3_ENCRYPTION_PASSWORD2 -> credentials.s3EncryptionPassword2 = value
        }
    }

    private fun statusFor(field: CredentialField): StoredCredentialStatus =
        if (readValue(field).isNullOrBlank()) {
            StoredCredentialStatus.Missing
        } else {
            StoredCredentialStatus.Present
        }

    private fun readValue(field: CredentialField): String? =
        when (field) {
            CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> credentials.lanPairingKeyHex
            CredentialField.GIT_TOKEN -> credentials.gitToken
            CredentialField.WEBDAV_USERNAME -> credentials.webDavUsername
            CredentialField.WEBDAV_PASSWORD -> credentials.webDavPassword
            CredentialField.S3_ACCESS_KEY_ID -> credentials.s3AccessKeyId
            CredentialField.S3_SECRET_ACCESS_KEY -> credentials.s3SecretAccessKey
            CredentialField.S3_SESSION_TOKEN -> credentials.s3SessionToken
            CredentialField.S3_ENCRYPTION_PASSWORD -> credentials.s3EncryptionPassword
            CredentialField.S3_ENCRYPTION_PASSWORD2 -> credentials.s3EncryptionPassword2
        }

    private fun fieldsForProvider(provider: CredentialProvider): List<CredentialField> =
        when (provider) {
            CredentialProvider.GIT -> listOf(CredentialField.GIT_TOKEN)
            CredentialProvider.WEBDAV -> listOf(CredentialField.WEBDAV_USERNAME, CredentialField.WEBDAV_PASSWORD)
            CredentialProvider.S3 ->
                listOf(
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                    CredentialField.S3_SESSION_TOKEN,
                    CredentialField.S3_ENCRYPTION_PASSWORD,
                    CredentialField.S3_ENCRYPTION_PASSWORD2,
                )
            CredentialProvider.LAN_SHARE -> listOf(CredentialField.LAN_SHARE_PAIRING_KEY_HEX)
        }
}
