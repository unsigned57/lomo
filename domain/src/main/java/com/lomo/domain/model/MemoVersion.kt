package com.lomo.domain.model

data class MemoVersion(
    val commitHash: String,
    val commitTime: Long,
    val commitMessage: String,
    val memoContent: String,
    val isCurrent: Boolean = false,
)
