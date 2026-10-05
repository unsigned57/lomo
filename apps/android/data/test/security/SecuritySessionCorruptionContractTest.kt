package com.lomo.data.security

// adversarial-audit: a corrupted preferences file is a storage failure and must deny
// credential reads; the corruption handler must not let corruption masquerade as "lock off".

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.lomo.data.local.datastore.AppSecurityStoreImpl
import com.lomo.data.local.datastore.LomoDataStoreKeys
import com.lomo.data.local.datastore.PreferencesCorruptionRegistry
import com.lomo.data.local.datastore.lomoPreferencesCorruptionHandler
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppLockPreference
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.repository.AuthorizedWorkResume
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Adversarial seam under test: the corruption handler quarantines the file and rebuilds on
 * `emptyPreferences()`. The rebuilt store is indistinguishable from a fresh install unless the
 * reader also consults the corruption witness — absent `APP_LOCK_ENABLED` after a witnessed
 * corruption must read as `Unreadable` (a storage failure), never as the user's opt-out.
 *
 * Behavior Contract:
 * - Unit under test: DataStoreAppLockPreferenceSource and DataStoreSecuritySessionPolicy under
 *   witnessed corruption.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a corrupted preferences file is a storage failure: it denies credential reads as
 *   PreferenceUnreadable and never masquerades as the user turning the lock off.
 *
 * Scenarios:
 * - Given a corrupted preferences file, when the lock preference is read, then it surfaces
 *   Unreadable instead of Disabled.
 * - Given a corrupted preferences file, when a credential read is authorized, then it is denied
 *   as PreferenceUnreadable.
 * - Given a post-corruption explicit Disabled write, when read again, then the real choice is
 *   honored over the witness.
 * - Given a healthy file with the lock enabled, when a credential read is authorized before
 *   authentication, then it is denied as locked.
 *
 * Observable outcomes: AppLockPreference reads, CredentialReadAuthorization decisions.
 *
 * TDD proof:
 * - The Unreadable scenarios fail RED when the rebuilt store reads absent APP_LOCK_ENABLED as
 *   Disabled without consulting the corruption witness.
 *
 * Excludes:
 * - BiometricPrompt presentation, Keystore binding and the corruption notice UI.
 */
class SecuritySessionCorruptionContractTest : DataFunSpec() {
    init {
        test("corrupted preferences file surfaces as Unreadable, not the user's opt-out") {
            runTest {
                val source = appLockSource(corruptedPreferencesFile(), backgroundScope)

                // Corruption destroys the recorded choice; presenting `Disabled` would pretend
                // the user turned the lock off.
                source.read() shouldBe AppLockPreference.Unreadable
            }
        }

        test("corrupted preferences file must deny credential reads as a storage failure") {
            runTest {
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource = appLockSource(corruptedPreferencesFile(), backgroundScope),
                        authorizedWorkResume = AuthorizedWorkResume { },
                        appScope = backgroundScope,
                    )

                // Spec: "设置读取失败不等于用户关闭锁" — a failed read must deny, not authorize.
                policy.authorizeCredentialRead() shouldBe
                    CredentialReadAuthorization.Denied(CredentialReadDenialReason.PreferenceUnreadable)
            }
        }

        test("explicitly stored disabled flag after witnessed corruption is still honored") {
            runTest {
                val backing = corruptedPreferencesFile()
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, backgroundScope, registry)
                val source = DataStoreAppLockPreferenceSource(AppSecurityStoreImpl(store))

                source.read() shouldBe AppLockPreference.Unreadable

                // A post-corruption explicit write is a real user choice, not a destroyed record.
                store.edit { it[LomoDataStoreKeys.APP_LOCK_ENABLED] = false }
                source.read() shouldBe AppLockPreference.Disabled
            }
        }

        test("healthy preferences file with app lock enabled still denies before authentication") {
            runTest {
                val backing = Files.createTempFile("healthy-prefs", ".preferences_pb").toFile()
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, backgroundScope, registry)
                store.edit { it[LomoDataStoreKeys.APP_LOCK_ENABLED] = true }
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource =
                            DataStoreAppLockPreferenceSource(AppSecurityStoreImpl(store)),
                        authorizedWorkResume = AuthorizedWorkResume { },
                        appScope = backgroundScope,
                    )

                policy.authorizeCredentialRead()
                    .shouldBeInstanceOf<CredentialReadAuthorization.Denied>()
            }
        }
    }

    private fun corruptedPreferencesFile(): File =
        Files.createTempFile("corrupt-prefs", ".preferences_pb").toFile().apply {
            writeBytes("%%% not a serialized preferences file %%%".toByteArray())
        }

    private fun newStore(
        backing: File,
        scope: CoroutineScope,
        registry: PreferencesCorruptionRegistry,
    ) = PreferenceDataStoreFactory.create(
        scope = scope,
        corruptionHandler =
            lomoPreferencesCorruptionHandler(
                corruptionFile = { backing },
                registry = registry,
            ),
        produceFile = { backing },
    )

    private fun appLockSource(
        backing: File,
        scope: CoroutineScope,
    ): DataStoreAppLockPreferenceSource {
        val registry = PreferencesCorruptionRegistry()
        return DataStoreAppLockPreferenceSource(
            AppSecurityStoreImpl(newStore(backing, scope, registry)),
        )
    }
}
