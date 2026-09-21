package com.lomo.domain.model

/**
 * Process-level duty for the native workspace engine.
 *
 * The default application process owns the engine. Any other process of the same UID
 * (Glance widgets, isolated services) is a projection renderer and must not open native.
 * The typed duty is the single injected input; consumers never re-derive ownership from a raw
 * Boolean.
 */
class WorkspaceProcessDuty private constructor(
    val ownsNativeEngine: Boolean,
) {
    companion object {
        /** The default application process owns the native engine. */
        val OWNED: WorkspaceProcessDuty = WorkspaceProcessDuty(true)

        /** A projection-only process (for example a Glance widget) never opens the native engine. */
        val PROJECTION_ONLY: WorkspaceProcessDuty = WorkspaceProcessDuty(false)

        /** Returns whether the process name identifies the default application process. */
        fun ownsNativeEngine(
            packageName: String,
            processName: String,
        ): Boolean = processName == packageName

        /** Resolves the typed duty for a concrete process identity. */
        fun forProcess(
            packageName: String,
            processName: String,
        ): WorkspaceProcessDuty = WorkspaceProcessDuty(ownsNativeEngine(packageName, processName))
    }
}
