package com.lomo.data.repository

import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncConflictRepository
import com.lomo.domain.repository.WebDavSyncOperationRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WebDavSyncReviewRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncRepositoryImpl
    @Inject
    constructor(
        configurationRepository: WebDavSyncConfigurationRepositoryImpl,
        configurationMutationRepository: WebDavSyncConfigurationMutationRepositoryImpl,
        operationRepository: WebDavSyncOperationRepositoryImpl,
        conflictRepository: WebDavSyncConflictRepositoryImpl,
        reviewRepository: WebDavSyncReviewRepositoryImpl,
        stateRepository: WebDavSyncStateRepositoryImpl,
    ) : WebDavSyncRepository,
        WebDavSyncConfigurationRepository by configurationRepository,
        WebDavSyncConfigurationMutationRepository by configurationMutationRepository,
        WebDavSyncOperationRepository by operationRepository,
        WebDavSyncConflictRepository by conflictRepository,
        WebDavSyncReviewRepository by reviewRepository,
        WebDavSyncStateRepository by stateRepository
