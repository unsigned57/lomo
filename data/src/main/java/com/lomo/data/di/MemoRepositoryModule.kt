package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.local.withDriverTransactionAndSuspendedMemoFtsTriggers
import com.lomo.data.repository.MemoMutationGate
import com.lomo.data.repository.MemoMutationRepositoryImpl
import com.lomo.data.repository.MemoQueryRepositoryImpl
import com.lomo.data.repository.MemoRefreshDbApplier
import com.lomo.data.repository.MemoRefreshEngine
import com.lomo.data.repository.MemoRefreshParserWorker
import com.lomo.data.repository.MemoRefreshPlanner
import com.lomo.data.repository.MemoSearchRepositoryImpl
import com.lomo.data.repository.MemoStatisticsRepositoryImpl
import com.lomo.data.repository.MemoTrashRepositoryImpl
import com.lomo.data.repository.MemoVersionJournal
import com.lomo.data.repository.MemoVersionRepositoryImpl
import com.lomo.data.repository.MemoVersionStore
import com.lomo.data.repository.MemoWorkspaceProjector
import com.lomo.data.repository.MemoWorkspaceReader
import com.lomo.data.repository.RefreshingWorkspaceStateResolver
import com.lomo.data.repository.RoomMemoVersionStore
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.SyncStateResetRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.repository.WorkspaceTransitionRepository
import com.lomo.domain.usecase.MemoIdentityPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoVersionModule {
    @Provides
    @Singleton
    fun provideMemoVersionStore(
        store: RoomMemoVersionStore,
    ): MemoVersionStore = store

    @Provides
    @Singleton
    fun provideMemoVersionRepository(
        impl: MemoVersionRepositoryImpl,
    ): MemoVersionRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoRefreshModule {
    @Provides
    @Singleton
    fun provideWorkspaceTransitionRepositoryImpl(
        database: MemoDatabase,
        memoWriteDao: MemoWriteDao,
        memoOutboxDao: MemoOutboxDao,
        memoTagDao: MemoTagDao,
        memoImageDao: MemoImageDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
        syncStateResetRepository: SyncStateResetRepository,
    ): WorkspaceTransitionRepositoryImpl =
        WorkspaceTransitionRepositoryImpl(
            memoWriteDao = memoWriteDao,
            memoOutboxDao = memoOutboxDao,
            memoTagDao = memoTagDao,
            memoImageDao = memoImageDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
            syncStateResetRepository = syncStateResetRepository,
            runInTransaction = { block ->
                database.withDriverTransaction {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshPlanner(): MemoRefreshPlanner = MemoRefreshPlanner

    @Provides
    @Singleton
    fun provideMemoIdentityPolicy(): MemoIdentityPolicy = MemoIdentityPolicy()

    @Provides
    @Singleton
    fun provideMemoRefreshParserWorker(
        workspaceProjector: MemoWorkspaceProjector,
        dao: MemoDao,
    ): MemoRefreshParserWorker =
        MemoRefreshParserWorker(
            workspaceProjector = workspaceProjector,
            dao = dao,
        )

    @Provides
    @Singleton
    fun provideMemoRefreshDbApplier(
        memoDao: MemoDao,
        memoWriteDao: MemoWriteDao,
        memoTagDao: MemoTagDao,
        memoImageDao: MemoImageDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
        memoVersionJournal: MemoVersionJournal,
        database: MemoDatabase,
    ): MemoRefreshDbApplier =
        MemoRefreshDbApplier(
            memoDao = memoDao,
            memoWriteDao = memoWriteDao,
            memoTagDao = memoTagDao,
            memoImageDao = memoImageDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
            memoVersionJournal = memoVersionJournal,
            runInTransaction = { block ->
                database.withDriverTransactionAndSuspendedMemoFtsTriggers {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshEngine(
        workspaceReader: MemoWorkspaceReader,
        localFileStateDao: LocalFileStateDao,
        planner: MemoRefreshPlanner,
        parserWorker: MemoRefreshParserWorker,
        dbApplier: MemoRefreshDbApplier,
        mutationGate: MemoMutationGate,
    ): MemoRefreshEngine =
        MemoRefreshEngine(
            workspaceReader = workspaceReader,
            localFileStateDao = localFileStateDao,
            refreshPlanner = planner,
            refreshParserWorker = parserWorker,
            refreshDbApplier = dbApplier,
            mutationGate = mutationGate,
        )

    @Provides
    @Singleton
    fun provideWorkspaceStateResolver(
        cleanupRepository: WorkspaceTransitionRepository,
        mediaRepository: MediaRepository,
        refreshEngine: MemoRefreshEngine,
    ): WorkspaceStateResolver =
        RefreshingWorkspaceStateResolver(
            cleanupRepository = cleanupRepository,
            mediaRepository = mediaRepository,
            refreshEngine = refreshEngine,
        )
}

@Module
@InstallIn(SingletonComponent::class)
object MemoQueryRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoQueryRepository(impl: MemoQueryRepositoryImpl): MemoQueryRepository = impl

    @Provides
    @Singleton
    fun provideMemoListQueryRepository(impl: MemoQueryRepositoryImpl): MemoListQueryRepository = impl

    @Provides
    @Singleton
    fun provideMainListQueryRepository(impl: MemoQueryRepositoryImpl): MainListQueryRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoMutationRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoMutationRepository(impl: MemoMutationRepositoryImpl): MemoMutationRepository = impl

    @Provides
    @Singleton
    fun provideMemoTrashRepository(impl: MemoTrashRepositoryImpl): MemoTrashRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoAnalysisRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoSearchRepository(impl: MemoSearchRepositoryImpl): MemoSearchRepository = impl

    @Provides
    @Singleton
    fun provideMemoStatisticsRepository(impl: MemoStatisticsRepositoryImpl): MemoStatisticsRepository = impl
}
