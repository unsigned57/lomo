package com.lomo.data.git

import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult

internal fun GitSyncFailureException.toGitSyncError(): GitSyncResult.Error =
    GitSyncResult.Error(
        code = code,
        message = message ?: GitSyncErrorMessages.PAT_REQUIRED,
        exception = this,
    )
