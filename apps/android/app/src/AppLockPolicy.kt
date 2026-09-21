package com.lomo.app

import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.showsLockConfigLoading
import com.lomo.domain.model.showsLockGate

/**
 * Pure derivations behind the in-app biometric/keyguard lock gate.
 *
 * The Compose surface observes [SecuritySessionState] from data; these helpers stay free of
 * framework dependency so unit tests can pin the gate without spinning up Compose.
 */

internal fun resolveAppLockGateVisible(session: SecuritySessionState): Boolean = session.showsLockGate()

internal fun shouldAutoRequestAppLockUnlock(
    session: SecuritySessionState,
    hasRequestedAutoUnlock: Boolean,
    unlockPromptInProgress: Boolean,
): Boolean =
    session is SecuritySessionState.Locked &&
        !hasRequestedAutoUnlock &&
        !unlockPromptInProgress

internal fun resolveAppLockConfigLoading(session: SecuritySessionState): Boolean =
    session.showsLockConfigLoading()
