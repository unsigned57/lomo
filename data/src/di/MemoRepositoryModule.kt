package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.withDriverTransactionAndSuspendedMemoFtsTriggers
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.repository.AsyncMemoVersionRecorder
import com.lomo.data.repository.MemoMutationDaoBundle
import com.lomo.data.repository.RoomMemoVersionStore
import com.lomo.data.repository.MemoVersionBlobRoot
import com.lomo.data.repository.MemoVersionStore
import com.lomo.data.repository.MemoVersionRepositoryImpl
import com.lomo.data.repository.MemoVersionJournal
import com.lomo.data.repository.MemoWorkspaceFileStateStore
import com.lomo.data.repository.MemoWorkspaceProjector
import com.lomo.data.repository.MemoWorkspaceReader
import com.lomo.data.repository.MemoWorkspaceShardWriter
import com.lomo.data.repository.MemoWorkspaceStore
import com.lomo.data.repository.MemoMutationGate
import com.lomo.data.repository.MemoMutationHandler
import com.lomo.data.repository.MemoRefreshPlanner
import com.lomo.data.repository.MemoRefreshParserWorker
import com.lomo.data.repository.MemoRefreshDbApplier
import com.lomo.data.repository.MemoRefreshEngine
import com.lomo.data.repository.MemoSavePlanFactory
import com.lomo.data.repository.MemoSynchronizer
import com.lomo.data.repository.MemoTrashMutationHandler
import com.lomo.data.repository.RefreshingWorkspaceStateResolver
import com.lomo.data.repository.MemoQueryRepositoryImpl
import com.lomo.data.repository.MemoMutationRepositoryImpl
import com.lomo.data.repository.MemoTrashRepositoryImpl
import com.lomo.data.repository.MemoSearchRepositoryImpl
import com.lomo.data.repository.MemoStatisticsRepositoryImpl
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.MemoIdentityPolicy
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds

val memoRepositoryModule = module {
    singleOf(::MemoTextProcessor)
    singleOf(::MarkdownParser)
    singleOf(::MemoSavePlanFactory)

    // Memo version / journal
    singleOf(::RoomMemoVersionStore) bind MemoVersionStore::class
    singleOf(::MemoVersionRepositoryImpl) bind MemoVersionRepository::class
    single {
        MemoVersionJournal(
            store = get(),
            blobRoot = get<MemoVersionBlobRoot>(),
            workspaceMediaAccess = get(),
            memoTextProcessor = get(),
        )
    }
    single { AsyncMemoVersionRecorder(get(), get(named("ApplicationScope"))) }
    
    // Core workspace projectors / readers / mutation gates
    singleOf(::MemoWorkspaceFileStateStore)
    singleOf(::MemoWorkspaceProjector)
    singleOf(::MemoWorkspaceReader)
    singleOf(::MemoWorkspaceShardWriter)
    singleOf(::MemoWorkspaceStore)
    singleOf(::MemoMutationGate)
    single {
        MemoMutationDaoBundle(
            memoDao = get(),
            memoWriteDao = get(),
            memoTagDao = get(),
            memoImageDao = get(),
            memoIdentityDao = get(),
            memoTrashDao = get(),
            memoOutboxDao = get(),
            runInTransaction = { block ->
                get<MemoDatabase>().withDriverTransaction {
                    block()
                }
            },
        )
    }
    singleOf(::MemoTrashMutationHandler)
    single {
        MemoMutationHandler(
            markdownStorageDataSource = get(),
            mediaStorageDataSource = get(),
            daoBundle = get(),
            memoStatisticsDao = get(),
            localFileStateDao = get(),
            workspaceStore = get(),
            workspaceMediaAccess = get(),
            savePlanFactory = get(),
            textProcessor = get(),
            dataStore = get(),
            trashMutationHandler = get(),
            memoIdentityPolicy = get(),
            memoVersionJournal = get(),
            mediaRepository = get(),
            s3LocalChangeRecorder = get(),
            webDavLocalChangeRecorder = get(),
            backgroundScope = get(named("ApplicationScope")),
        )
    }

    // Planner / Policies / Workers / Appliers / Engines
    single { MemoRefreshPlanner }
    single { MemoIdentityPolicy() }
    singleOf(::MemoRefreshParserWorker)
    single {
        MemoRefreshDbApplier(
            memoDao = get(),
            memoWriteDao = get(),
            memoTagDao = get(),
            memoImageDao = get(),
            memoTrashDao = get(),
            localFileStateDao = get(),
            memoVersionJournal = get(),
            runInTransaction = { block ->
                get<MemoDatabase>().withDriverTransactionAndSuspendedMemoFtsTriggers {
                    block()
                }
            }
        )
    }
    singleOf(::MemoRefreshEngine)
    single {
        MemoSynchronizer(
            refreshEngine = get(),
            mutationHandler = get(),
            outboxScope = get(named("ApplicationScope")),
        )
    }
    single<WorkspaceStateResolver> { RefreshingWorkspaceStateResolver(get(), get(), get()) }

    // Repositories
    single { MemoQueryRepositoryImpl(get(), get(), get(), get(), get()) } binds arrayOf(
        MemoQueryRepository::class,
        MemoListQueryRepository::class,
        MainListQueryRepository::class
    )
    single { MemoMutationRepositoryImpl(get(), get(), get(), get()) }
        .bind(MemoMutationRepository::class)
    singleOf(::MemoTrashRepositoryImpl) bind MemoTrashRepository::class
    singleOf(::MemoSearchRepositoryImpl) bind MemoSearchRepository::class
    singleOf(::MemoStatisticsRepositoryImpl) bind MemoStatisticsRepository::class
}
