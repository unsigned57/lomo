package com.lomo.app

/**
 * Pure boolean derivations behind the in-app biometric/keyguard lock gate.
 *
 * Extracted from [MainActivity] so unit tests can pin the behavior contract without spinning up
 * Compose / Robolectric. The Composable side wires real `remember`-backed state to these helpers;
 * the helpers themselves stay free of any framework dependency.
 */

/**
 * Whether the lock gate should currently cover the unlocked app surface.
 *
 * The gate is only visible when the user has app lock enabled AND has not yet unlocked the
 * current process lifetime. Once unlocked, toggling the setting (or any other transition) must
 * not silently re-display the gate, because the surrounding screen state (e.g. the Settings
 * sub-page the user is currently editing) would be torn down underneath them.
 */
internal fun resolveAppLockGateVisible(
    appLockEnabled: Boolean?,
    hasUnlockedThisLaunch: Boolean,
): Boolean = appLockEnabled == true && !hasUnlockedThisLaunch

/**
 * Whether the launcher should auto-fire the biometric prompt for the user.
 *
 * Only fires when lock is on, the launch has not yet been unlocked, no auto-attempt has been
 * scheduled, and no prompt is currently in flight. In particular, returns false the moment lock
 * flips from disabled to enabled mid-session (the user is already authenticated by virtue of
 * driving the UI), preventing the gate from popping up and disposing the active screen tree.
 */
internal fun shouldAutoRequestAppLockUnlock(
    appLockEnabled: Boolean?,
    hasUnlockedThisLaunch: Boolean,
    hasRequestedAutoUnlock: Boolean,
    unlockPromptInProgress: Boolean,
): Boolean = appLockEnabled == true &&
    !hasUnlockedThisLaunch &&
    !hasRequestedAutoUnlock &&
    !unlockPromptInProgress
