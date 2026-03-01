package com.lomo.data.git

import com.lomo.domain.model.GitSyncResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GitSyncInitCoordinator
    @Inject
    constructor(
        private val workflow: GitSyncWorkflow,
    ) {
        suspend fun initOrClone(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult = workflow.initOrClone(rootDir, remoteUrl)
    }
