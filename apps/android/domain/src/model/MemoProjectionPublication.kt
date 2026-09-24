package com.lomo.domain.model

/**
 * One accepted durable projection publication. [coreRevision] is the Rust store commit clock the
 * projection reflects — the stamp snapshot surfaces reconcile against instead of wall time.
 */
data class MemoProjectionPublication(
    val coreRevision: Long,
)
