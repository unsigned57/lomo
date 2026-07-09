package com.lomo.data.repository
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncConflictRepository
import com.lomo.domain.repository.S3SyncOperationRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.S3SyncReviewRepository
import com.lomo.domain.repository.S3SyncStateRepository
class S3SyncRepositoryImpl
constructor(
        configurationRepository: S3SyncConfigurationRepositoryImpl,
        configurationMutationRepository: S3SyncConfigurationMutationRepositoryImpl,
        operationRepository: S3SyncOperationRepositoryImpl,
        conflictRepository: S3SyncConflictRepositoryImpl,
        reviewRepository: S3SyncReviewRepositoryImpl,
        stateRepository: S3SyncStateRepositoryImpl,
    ) : S3SyncRepository,
        S3SyncConfigurationRepository by configurationRepository,
        S3SyncConfigurationMutationRepository by configurationMutationRepository,
        S3SyncOperationRepository by operationRepository,
        S3SyncConflictRepository by conflictRepository,
        S3SyncReviewRepository by reviewRepository,
        S3SyncStateRepository by stateRepository
