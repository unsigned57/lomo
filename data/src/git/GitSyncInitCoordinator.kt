package com.lomo.data.git
import com.lomo.domain.model.GitSyncResult
import java.io.File
internal class GitSyncInitCoordinator
constructor(
        private val workflow: GitSyncWorkflow,
    ) {
        suspend fun initOrClone(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult = workflow.initOrClone(rootDir, remoteUrl)
    }
