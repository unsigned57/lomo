package com.lomo.domain.model

/** Immutable Rust-owned baseline and replacement body; planning performs no durable writes. */
data class ReminderRewritePlan(
    val memoId: String,
    val expectedRevision: Long,
    val expectedFingerprint: String,
    val content: String,
)
