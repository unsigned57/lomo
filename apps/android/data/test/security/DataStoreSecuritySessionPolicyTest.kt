package com.lomo.data.security

/*
 * Behavior Contract:
 * Capability: UI, credential reads, and background work consume one security session; DataStore
 * IOException is StorageFailure, not lock-off; owning layer: data; priority: P0.
 * Scenarios:
 * - Given Unreadable preference, when authorizeCredentialRead runs, then Denied PreferenceUnreadable.
 * - Given Enabled and never authenticated, when authorizeCredentialRead runs, then Denied locked.
 * - Given Enabled, when recordAuthenticated then authorize, then Authorized; backgrounding denies again.
 * - Given Disabled, when authorizeCredentialRead runs, then Authorized without authentication.
 * - Given Locked worker demand, when session later Unlocks, then AuthorizedWorkResume fires once.
 * Observable outcomes: CredentialReadAuthorization, SecuritySessionState, resume invocations.
 * TDD proof: ./kotlin test --include-module=data --include-classes='com.lomo.data.security.DataStoreSecuritySessionPolicyTest'
 * Excludes: BiometricPrompt, FLAG_SECURE window, Keystore userAuthenticationRequired, foreground timer.
 */

import com.lomo.data.local.datastore.AppSecurityStoreImpl
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppLockPreference
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.repository.AuthorizedWorkResume
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.IOException

private class FakeAppLockPreferenceSource(
    initial: AppLockPreference,
) : AppLockPreferenceSource {
    private val preference = MutableStateFlow(initial)

    override suspend fun read(): AppLockPreference = preference.value

    override fun observe(): Flow<AppLockPreference> = preference.asStateFlow()

    fun emit(next: AppLockPreference) {
        preference.value = next
    }
}

private class RecordingAuthorizedWorkResume : AuthorizedWorkResume {
    var count: Int = 0

    override fun onSessionAllowsBackgroundWork() {
        count += 1
    }
}

class DataStoreSecuritySessionPolicyTest : DataFunSpec() {
    init {
        test("given unreadable preference when authorizing then credential reads are denied") {
            runTest {
                val resume = RecordingAuthorizedWorkResume()
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource = FakeAppLockPreferenceSource(AppLockPreference.Unreadable),
                        authorizedWorkResume = resume,
                        appScope = backgroundScope,
                    )

                val authorization = policy.authorizeCredentialRead()

                authorization shouldBe
                    CredentialReadAuthorization.Denied(CredentialReadDenialReason.PreferenceUnreadable)
                policy.current() shouldBe SecuritySessionState.StorageFailure
                resume.count shouldBe 0
            }
        }

        test("given enabled lock when authorizing before authentication then reads stay denied") {
            runTest {
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource = FakeAppLockPreferenceSource(AppLockPreference.Enabled),
                        authorizedWorkResume = RecordingAuthorizedWorkResume(),
                        appScope = backgroundScope,
                    )

                policy.authorizeCredentialRead() shouldBe
                    CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
                policy.current() shouldBe SecuritySessionState.Locked
            }
        }

        test("given locked session when authenticated then reads are authorized until backgrounded") {
            runTest {
                val resume = RecordingAuthorizedWorkResume()
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource = FakeAppLockPreferenceSource(AppLockPreference.Enabled),
                        authorizedWorkResume = resume,
                        appScope = backgroundScope,
                    )

                policy.refresh()
                policy.recordAuthenticated()
                policy.authorizeCredentialRead() shouldBe CredentialReadAuthorization.Authorized
                policy.current() shouldBe SecuritySessionState.Unlocked
                resume.count shouldBe 1

                policy.recordBackgrounded()
                policy.authorizeCredentialRead() shouldBe
                    CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
                policy.current() shouldBe SecuritySessionState.Locked
            }
        }

        test("given disabled lock when authorizing then reads are authorized without authentication") {
            runTest {
                val resume = RecordingAuthorizedWorkResume()
                val policy =
                    DataStoreSecuritySessionPolicy(
                        preferenceSource = FakeAppLockPreferenceSource(AppLockPreference.Disabled),
                        authorizedWorkResume = resume,
                        appScope = backgroundScope,
                    )

                policy.authorizeCredentialRead() shouldBe CredentialReadAuthorization.Authorized
                policy.current() shouldBe SecuritySessionState.LockOff
                resume.count shouldBe 1
            }
        }

        test("given DataStore IOException when reading app lock preference then result is Unreadable") {
            runTest {
                val dataStore =
                    object : DataStore<Preferences> {
                        override val data: Flow<Preferences> =
                            flow { throw IOException("preferences unreadable") }

                        override suspend fun updateData(
                            transform: suspend (t: Preferences) -> Preferences,
                        ): Preferences = error("unused")
                    }

                AppSecurityStoreImpl(dataStore).readAppLockPreference() shouldBe
                    AppLockPreference.Unreadable
            }
        }
    }
}
