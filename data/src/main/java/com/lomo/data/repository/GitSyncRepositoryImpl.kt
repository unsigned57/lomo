package com.lomo.data.repository
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncConflictRepository
import com.lomo.domain.repository.GitSyncOperationRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.GitSyncStateRepository
class GitSyncRepositoryImpl
constructor(
        configurationRepository: GitSyncConfigurationRepositoryImpl,
        configurationMutationRepository: GitSyncConfigurationMutationRepositoryImpl,
        operationRepository: GitSyncOperationRepositoryImpl,
        conflictRepository: GitSyncConflictRepositoryImpl,
        stateRepository: GitSyncStateRepositoryImpl,
    ) : GitSyncRepository,
        GitSyncConfigurationRepository by configurationRepository,
        GitSyncConfigurationMutationRepository by configurationMutationRepository,
        GitSyncOperationRepository by operationRepository,
        GitSyncConflictRepository by conflictRepository,
        GitSyncStateRepository by stateRepository
