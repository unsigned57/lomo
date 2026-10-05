package com.lomo.domain.model

/*
 * Behavior Contract:
 * Capability: reduce app-lock preference, authentication, and background events into one
 * queryable session; owning layer: domain; priority: P0.
 * Scenarios:
 * - Given unread preference, when snapshot is initial, then state is Unknown and credential
 *   reads are denied as locked.
 * - Given Unreadable preference, when applied, then StorageFailure and PreferenceUnreadable,
 *   never LockOff.
 * - Given Disabled, when applied, then LockOff and Authorized.
 * - Given Enabled from Unknown, when applied, then Locked (cold start requires auth).
 * - Given Enabled from Disabled, when applied, then Locked (enabling the lock requires auth).
 * - Given Locked, when Authenticated, then Unlocked; when Backgrounded from Unlocked, then Locked.
 * - Given LockOff, when Backgrounded, then LockOff.
 * Observable outcomes: SecuritySessionState and CredentialReadAuthorization.
 * TDD proof: domain reducer tests; DataStore IOException path is locked in data-layer specs.
 * Excludes: Compose remember, WorkManager, BiometricPrompt, Keystore user-auth binding, foreground timer.
 *
 * Test Change Justification:
 * - Reason category: security session transition correction.
 * - Old behavior/assertion being replaced: Enabled applied to a lock-off session stayed Unlocked,
 *   so enabling the app lock silently left the session open until the next background event.
 * - Why old assertion is no longer correct: enabling the lock while LockOff must require
 *   authentication before credential reads, otherwise the preference flip weakens a live session.
 * - Coverage preserved by: Enabled-from-Disabled now asserts Locked plus Denied(SecuritySessionLocked),
 *   and a repeated Enabled preference while Unlocked must not revoke an authenticated session.
 * - Why this is not fitting the test to the implementation: assertions check the user-visible
 *   session state and credential authorization contract, not private reducer internals.
 */

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class SecuritySessionTest : DomainFunSpec() {
    init {
        test("given unread preference when queried then unknown denies credential reads") {
            val snapshot = SecuritySessionSnapshot()

            snapshot.state shouldBe SecuritySessionState.Unknown
            snapshot.state.credentialAuthorization() shouldBe
                CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
            snapshot.state.allowsCredentialRead() shouldBe false
            snapshot.state.showsLockGate() shouldBe true
            snapshot.state.obscuresRecents() shouldBe false
        }

        test("given unreadable preference when applied then storage failure does not authorize") {
            val snapshot =
                SecuritySessionSnapshot().apply(
                    SecuritySessionEvent.PreferenceRead(AppLockPreference.Unreadable),
                )

            snapshot.state shouldBe SecuritySessionState.StorageFailure
            snapshot.state.credentialAuthorization() shouldBe
                CredentialReadAuthorization.Denied(CredentialReadDenialReason.PreferenceUnreadable)
            snapshot.state.showsLockGate() shouldBe true
            snapshot.state.obscuresRecents() shouldBe true
        }

        test("given disabled preference when applied then lock-off authorizes without biometric") {
            val snapshot =
                SecuritySessionSnapshot().apply(
                    SecuritySessionEvent.PreferenceRead(AppLockPreference.Disabled),
                )

            snapshot.state shouldBe SecuritySessionState.LockOff
            snapshot.state.credentialAuthorization() shouldBe CredentialReadAuthorization.Authorized
            snapshot.state.showsLockGate() shouldBe false
            snapshot.state.obscuresRecents() shouldBe false
        }

        test("given enabled preference from unknown when applied then session is locked") {
            val snapshot =
                SecuritySessionSnapshot().apply(
                    SecuritySessionEvent.PreferenceRead(AppLockPreference.Enabled),
                )

            snapshot.state shouldBe SecuritySessionState.Locked
            snapshot.state.credentialAuthorization() shouldBe
                CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
        }

        test("given lock enabled from a lock-off session when applied then session locks until authenticated") {
            val snapshot =
                SecuritySessionSnapshot()
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Disabled))
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Enabled))

            snapshot.state shouldBe SecuritySessionState.Locked
            snapshot.state.credentialAuthorization() shouldBe
                CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
        }

        test("given enabled preference arriving while already unlocked when applied then authentication is kept") {
            val snapshot =
                SecuritySessionSnapshot()
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Enabled))
                    .apply(SecuritySessionEvent.Authenticated)
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Enabled))

            snapshot.state shouldBe SecuritySessionState.Unlocked
            snapshot.state.credentialAuthorization() shouldBe CredentialReadAuthorization.Authorized
        }

        test("given locked session when authenticated then unlocked and backgrounding locks again") {
            val locked =
                SecuritySessionSnapshot().apply(
                    SecuritySessionEvent.PreferenceRead(AppLockPreference.Enabled),
                )
            val unlocked = locked.apply(SecuritySessionEvent.Authenticated)
            val backgrounded = unlocked.apply(SecuritySessionEvent.Backgrounded)

            unlocked.state shouldBe SecuritySessionState.Unlocked
            unlocked.state.obscuresRecents() shouldBe true
            backgrounded.state shouldBe SecuritySessionState.Locked
        }

        test("given lock-off when backgrounded then session stays lock-off") {
            val snapshot =
                SecuritySessionSnapshot()
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Disabled))
                    .apply(SecuritySessionEvent.Backgrounded)

            snapshot.state shouldBe SecuritySessionState.LockOff
            snapshot.state.credentialAuthorization() shouldBe CredentialReadAuthorization.Authorized
        }

        test("given storage failure recovered to disabled when applied then lock-off authorizes") {
            val snapshot =
                SecuritySessionSnapshot()
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Unreadable))
                    .apply(SecuritySessionEvent.PreferenceRead(AppLockPreference.Disabled))

            snapshot.state shouldBe SecuritySessionState.LockOff
            snapshot.state.credentialAuthorization() shouldBe CredentialReadAuthorization.Authorized
        }
    }
}
