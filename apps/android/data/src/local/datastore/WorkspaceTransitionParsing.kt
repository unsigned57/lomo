package com.lomo.data.local.datastore

import com.lomo.domain.model.WorkspaceRootTransitionPhase

internal fun requireTransitionValue(value: Boolean?, message: String): Boolean =
    value ?: throw transitionCorruption(message)

internal fun requireTransitionString(value: String?, message: String): String =
    value ?: throw transitionCorruption(message)

internal fun requireTransitionCondition(condition: Boolean, message: String) {
    if (!condition) throw transitionCorruption(message)
}

internal fun requireTransitionPhase(value: String): WorkspaceRootTransitionPhase =
    WorkspaceRootTransitionPhase.entries.firstOrNull { it.name == value }
        ?: throw transitionCorruption("Workspace transition phase is invalid")
