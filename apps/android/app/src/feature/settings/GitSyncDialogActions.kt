package com.lomo.app.feature.settings

data class GitSyncDialogActions(
    val openRemoteUrl: () -> Unit,
    val openPat: () -> Unit,
    val openAuthorName: () -> Unit,
    val openAuthorEmail: () -> Unit,
    val openReset: () -> Unit,
)
