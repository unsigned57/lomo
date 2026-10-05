package com.lomo.data.repository

/*
 * Reaudit evidence for the restore-preflight boundary (11-再复审-Android域).
 *
 * The 05-F5 repair record claims restore reconciles durable sync state whenever the payload
 * moves any canonical-identity input of the selected backend. RestoreSyncIdentityPreflight
 * replays the same normalization RustSyncCycleInputFactory applies; these probes pin the
 * parity edges the record's own tests leave unexercised:
 *
 * - The mutation repository persists S3 prefix as trim()+trim('/'); a payload spelling that
 *   differs only by a trailing slash must not read as an identity move (and a real move must).
 * - WebDAV identity is the RESOLVED endpoint: an explicit endpointUrl shadows baseUrl, so a
 *   restore that rewrites only the shadowed baseUrl is a durable-state no-op, while one that
 *   clears endpointUrl and falls back to a different baseUrl genuinely moves the identity.
 * - A Skip ordinary plan must still surface a sensitive identity move: clearing/changing the
 *   selected backend's username rides the sensitive map, not the ordinary payload.
 * - Under a locked session the before-read is denied; the preflight must lean toward reset
 *   (a conservative extra disposal), never toward skipping a real move.
 *
 * Behavior Contract:
 * - Unit under test: RestoreSyncIdentityPreflight normalization parity.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a settings restore that moves a canonical identity input of the selected backend
 *   triggers identity reset; payload spellings that normalize to the same identity do not.
 *
 * Scenarios:
 * - Given an S3 prefix differing only by a trailing slash, when the payload is compared, then it
 *   is not an identity move.
 * - Given a WebDAV restore rewriting only a shadowed baseUrl, when compared, then it is a no-op;
 *   clearing endpointUrl onto a different baseUrl moves the resolved identity.
 * - Given a Skip ordinary plan, when the selected backend's username changes, then the sensitive
 *   map still surfaces the identity move.
 * - Given a locked session denying the before-read, when compared, then the preflight leans
 *   toward reset rather than skipping a real move.
 *
 * Observable outcomes: identity-move verdicts against normalized persisted state.
 *
 * TDD proof:
 * - The normalization and shadowed-endpoint arms fail RED against a raw-string comparison.
 *
 * Excludes:
 * - The actual disposal/reset side effects and UI surfaces of the reset decision.
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoOrdinarySettingsRestoreTransaction
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialSecretReadResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class RestoreSyncIdentityPreflightContractTest : DataFunSpec() {
    init {
        test("s3 prefix spelling normalization: a trailing-slash payload is not an identity move") {
            runTest {
                val fixture = Fixture(backgroundScope)
                fixture.dataStore.setRemoteSyncBackendType("s3")
                fixture.dataStore.updateS3EndpointUrl("https://s3.example")
                fixture.dataStore.updateS3Region("us-east-1")
                fixture.dataStore.updateS3Bucket("bucket")
                fixture.dataStore.updateS3Prefix("media")
                fixture.credentials.setRead(
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialSecretReadResult.Present("AKIA"),
                )

                // The mutation repository stores prefix normalized; "media/" is the same identity.
                fixture.moves(
                    preferences = restorePayload(
                        SettingsKey.SYNC_BACKEND_TYPE to "s3",
                        SettingsKey.S3_ENDPOINT_URL to "https://s3.example",
                        SettingsKey.S3_REGION to "us-east-1",
                        SettingsKey.S3_BUCKET to "bucket",
                        SettingsKey.S3_PREFIX to "media/",
                    ),
                    sensitive = mapOf(SettingsKey.S3_ACCESS_KEY_ID to "AKIA"),
                ) shouldBe false

                // A real prefix move is still caught through the same normalization.
                fixture.moves(
                    preferences = restorePayload(
                        SettingsKey.SYNC_BACKEND_TYPE to "s3",
                        SettingsKey.S3_ENDPOINT_URL to "https://s3.example",
                        SettingsKey.S3_REGION to "us-east-1",
                        SettingsKey.S3_BUCKET to "bucket",
                        SettingsKey.S3_PREFIX to "other",
                    ),
                    sensitive = mapOf(SettingsKey.S3_ACCESS_KEY_ID to "AKIA"),
                ) shouldBe true
            }
        }

        test("webdav shadowed baseUrl rewrite is a no-op; clearing endpointUrl moves the resolved identity") {
            runTest {
                val fixture = Fixture(backgroundScope)
                fixture.dataStore.setRemoteSyncBackendType("webdav")
                fixture.dataStore.updateWebDavBaseUrl("https://dav.example/base")
                fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
                fixture.credentials.setRead(
                    CredentialField.WEBDAV_USERNAME,
                    CredentialSecretReadResult.Present("alice"),
                )

                // endpointUrl still shadows baseUrl after restore → resolved endpoint unmoved.
                fixture.moves(
                    preferences = restorePayload(
                        SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                        SettingsKey.WEBDAV_BASE_URL to "https://dav.example/other-base",
                        SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                    ),
                    sensitive = mapOf(SettingsKey.WEBDAV_STORED_USERNAME to "alice"),
                ) shouldBe false

                // Restore semantics clear absent nullable keys: dropping endpointUrl makes
                // baseUrl authoritative — the resolved endpoint moved and must dispose.
                fixture.moves(
                    preferences = restorePayload(
                        SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                        SettingsKey.WEBDAV_BASE_URL to "https://dav.example/other-base",
                    ),
                    sensitive = mapOf(SettingsKey.WEBDAV_STORED_USERNAME to "alice"),
                ) shouldBe true
            }
        }

        test("a Skip ordinary plan still surfaces a sensitive identity move on the selected backend") {
            runTest {
                val fixture = Fixture(backgroundScope)
                fixture.dataStore.setRemoteSyncBackendType("git")
                fixture.dataStore.updateGitRemoteUrl("https://example.com/repo.git")
                fixture.dataStore.updateGitAuthorName("Lomo")
                fixture.dataStore.updateGitAuthorEmail("git@lomo.local")
                fixture.credentials.setRead(
                    CredentialField.GIT_USERNAME,
                    CredentialSecretReadResult.Present("alice"),
                )

                // Ordinary keys untouched (Skip), but the payload rewrites gitUsername — the
                // identity moved on the sensitive axis and the fence must be disposed.
                fixture.moves(
                    preferences = emptyMap(),
                    sensitive = mapOf(SettingsKey.GIT_USERNAME to "bob"),
                    plan = OrdinarySettingsRestorePlan.Skip(clearsLegacyWebDavUsername = false),
                ) shouldBe true
            }
        }

        test("locked session compares secrets as empty and still detects a supplied identity move") {
            runTest {
                val lockedFixture = Fixture(backgroundScope, policy = LockedCredentialReadSessionPolicy)
                lockedFixture.dataStore.setRemoteSyncBackendType("webdav")
                lockedFixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")

                // A payload supplying a different username against an unreadable stored secret
                // must read as a move — the conservative direction (reset), never a skip.
                lockedFixture.moves(
                    preferences = restorePayload(
                        SettingsKey.SYNC_BACKEND_TYPE to "webdav",
                        SettingsKey.WEBDAV_ENDPOINT_URL to "https://dav.example/endpoint",
                    ),
                    sensitive = mapOf(SettingsKey.WEBDAV_STORED_USERNAME to "mallory"),
                ) shouldBe true
            }
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        policy: com.lomo.domain.repository.SecuritySessionPolicy = AuthorizedCredentialReadSessionPolicy,
    ) {
        val dataStore: LomoDataStore = newIdentityDataStore(scope)
        val credentials = TestCredentialRepository(mutableMapOf())
        private val preflight =
            RestoreSyncIdentityPreflight(
                dataStore = dataStore,
                credentialRepository = credentials,
                securitySessionPolicy = policy,
            )

        suspend fun moves(
            preferences: Map<String, String>,
            sensitive: Map<String, String>,
            plan: OrdinarySettingsRestorePlan =
                OrdinarySettingsRestorePlan.Restore(
                    LomoOrdinarySettingsRestoreTransaction(
                        catalogValues = emptyMap(),
                        stringValues = emptyMap(),
                        nullableStringValues = emptyMap(),
                        booleanValues = emptyMap(),
                        intValues = emptyMap(),
                    ),
                ),
        ): Boolean =
            preflight.movesSyncIdentity(
                snapshot = MigrationSettingsSnapshot(preferences = preferences, sensitive = sensitive),
                sensitiveSettings = sensitive,
                ordinaryPlan = plan,
            )
    }

    private companion object {
        fun newIdentityDataStore(scope: CoroutineScope): LomoDataStore {
            val backingFile =
                Files.createTempFile("preflight-reaudit", ".preferences_pb").toFile().apply {
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

        // The Restore plan reads ordinary values straight from the payload map.
        fun restorePayload(vararg pairs: Pair<String, String>): Map<String, String> =
            mapOf(*pairs)
    }
}
