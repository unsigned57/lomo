package com.lomo.domain.model

/**
 * Single published mount fact for the active engine session.
 *
 * Read and write admission are derived here so UI and data layers do not recombine
 * readiness, authority, and freshness independently.
 */
data class WorkspaceMount(
    val readiness: EngineReadiness,
    val location: StorageLocation?,
    val authority: WorkspaceAuthority?,
    val freshness: ProjectionFreshness,
    /** Independent attachment roots installed with this generation. A missing override follows the notes tree. */
    val attachments: WorkspaceAttachmentDirectories = WorkspaceAttachmentDirectories(),
) {
    val admitsProjectionReads: Boolean
        get() =
            (
                readiness is EngineReadiness.Ready ||
                    (readiness is EngineReadiness.ReadOnlyRecovery && freshness is ProjectionFreshness.Retained)
                ) &&
                authority != null &&
                freshness.permitsReadsAt(authority.projectionRevision)

    val admitsProjectionWrites: Boolean
        get() =
            admitsProjectionReads &&
                freshness.permitsWrites()

    val admittedAuthority: WorkspaceAuthority?
        get() = authority.takeIf { admitsProjectionReads }

    companion object {
        val Opening: WorkspaceMount =
            WorkspaceMount(
                readiness = EngineReadiness.Opening,
                location = null,
                authority = null,
                freshness = ProjectionFreshness.Unavailable,
            )
    }
}

data class WorkspaceAttachmentDirectories(val images: StorageLocation? = null, val recordings: StorageLocation? = null)
