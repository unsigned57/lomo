package com.lomo.domain.model

sealed interface SecuritySessionState {
    data object Unknown : SecuritySessionState

    data object LockOff : SecuritySessionState

    data object Locked : SecuritySessionState

    data object Unlocked : SecuritySessionState

    data object StorageFailure : SecuritySessionState
}

sealed interface AppLockPreference {
    data object Enabled : AppLockPreference

    data object Disabled : AppLockPreference

    data object Unreadable : AppLockPreference
}

sealed interface SecuritySessionEvent {
    data class PreferenceRead(
        val preference: AppLockPreference,
    ) : SecuritySessionEvent

    data object Authenticated : SecuritySessionEvent

    data object Backgrounded : SecuritySessionEvent
}

data class SecuritySessionSnapshot(
    val preference: AppLockPreference? = null,
    val authenticated: Boolean = false,
) {
    val state: SecuritySessionState =
        when (preference) {
            null -> SecuritySessionState.Unknown
            AppLockPreference.Unreadable -> SecuritySessionState.StorageFailure
            AppLockPreference.Disabled -> SecuritySessionState.LockOff
            AppLockPreference.Enabled ->
                if (authenticated) {
                    SecuritySessionState.Unlocked
                } else {
                    SecuritySessionState.Locked
                }
        }

    fun apply(event: SecuritySessionEvent): SecuritySessionSnapshot =
        when (event) {
            is SecuritySessionEvent.PreferenceRead -> applyPreference(event.preference)
            SecuritySessionEvent.Authenticated ->
                if (preference == AppLockPreference.Enabled) {
                    copy(authenticated = true)
                } else {
                    this
                }
            SecuritySessionEvent.Backgrounded ->
                if (preference == AppLockPreference.Enabled) {
                    copy(authenticated = false)
                } else {
                    this
                }
        }

    private fun applyPreference(next: AppLockPreference): SecuritySessionSnapshot =
        when (next) {
            AppLockPreference.Disabled ->
                SecuritySessionSnapshot(
                    preference = AppLockPreference.Disabled,
                    authenticated = true,
                )
            AppLockPreference.Unreadable ->
                SecuritySessionSnapshot(
                    preference = AppLockPreference.Unreadable,
                    authenticated = false,
                )
            AppLockPreference.Enabled ->
                SecuritySessionSnapshot(
                    preference = AppLockPreference.Enabled,
                    authenticated =
                        when (preference) {
                            // Only an already-enabled session keeps its authentication. Any
                            // other transition into Enabled — including one produced by an
                            // imported settings snapshot — is an unauthenticated lock enable
                            // and must gate on authentication.
                            AppLockPreference.Enabled -> authenticated
                            null,
                            AppLockPreference.Disabled,
                            AppLockPreference.Unreadable,
                            -> false
                        },
                )
        }
}

fun SecuritySessionState.allowsCredentialRead(): Boolean =
    this is SecuritySessionState.LockOff || this is SecuritySessionState.Unlocked

fun SecuritySessionState.credentialAuthorization(): CredentialReadAuthorization =
    when (this) {
        SecuritySessionState.LockOff,
        SecuritySessionState.Unlocked,
        -> CredentialReadAuthorization.Authorized
        SecuritySessionState.StorageFailure ->
            CredentialReadAuthorization.Denied(CredentialReadDenialReason.PreferenceUnreadable)
        SecuritySessionState.Unknown,
        SecuritySessionState.Locked,
        -> CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
    }

fun SecuritySessionState.showsLockGate(): Boolean =
    this is SecuritySessionState.Unknown ||
        this is SecuritySessionState.Locked ||
        this is SecuritySessionState.StorageFailure

fun SecuritySessionState.showsLockConfigLoading(): Boolean = this is SecuritySessionState.Unknown

fun SecuritySessionState.obscuresRecents(): Boolean =
    this is SecuritySessionState.Locked ||
        this is SecuritySessionState.Unlocked ||
        this is SecuritySessionState.StorageFailure
