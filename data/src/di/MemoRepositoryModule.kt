package com.lomo.data.di

import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.store.BoltFfiStorePort
import com.lomo.data.engine.store.StorePort
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.repository.StoreMemoMutationRepository
import com.lomo.data.repository.StoreMemoQueryRepository
import com.lomo.data.repository.StoreMemoSearchRepository
import com.lomo.data.repository.StoreMemoStatisticsRepository
import com.lomo.data.repository.StoreMemoTrashRepository
import com.lomo.data.repository.StoreMemoVersionRepository
import com.lomo.data.repository.StoreWorkspaceStateResolver
import com.lomo.data.repository.StoreWorkspaceTransitionRepository
import com.lomo.data.util.MarkdownWorkspaceContentProjector
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.repository.WorkspaceTransitionRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * P3-10 production DI: Rust store sole local-data owner. No Room dual-stack path.
 */
val memoRepositoryModule =
    module {
        singleOf(::MarkdownWorkspaceContentProjector)
        single { StoreInvalidationBus() }
        single<StorePort> {
            // ManagedEngineSession implements StoreNativeBridge via WorkspaceNativeAdapter.
            BoltFfiStorePort(bridge = get<ManagedEngineSession>())
        }

        single {
            StoreMemoQueryRepository(
                port = get(),
                invalidation = get(),
            )
        } binds
            arrayOf(
                MemoQueryRepository::class,
                MemoListQueryRepository::class,
                MainListQueryRepository::class,
            )

        single {
            StoreMemoMutationRepository(
                port = get(),
                queryRepository = get(),
                reminderScheduler = get(),
                writeLease = get(),
                invalidation = get(),
                pendingStages = get(),
                syncEdge = get(),
            )
        } bind MemoMutationRepository::class

        singleOf(::StoreMemoSearchRepository) bind MemoSearchRepository::class
        single {
            StoreMemoStatisticsRepository(port = get(), invalidation = get())
        } bind MemoStatisticsRepository::class
        single {
            StoreMemoTrashRepository(
                port = get(),
                writeLease = get(),
                invalidation = get(),
            )
        } bind MemoTrashRepository::class
        singleOf(::StoreMemoVersionRepository) bind MemoVersionRepository::class

        single {
            StoreWorkspaceStateResolver(port = get(), invalidation = get())
        } bind WorkspaceStateResolver::class
        single {
            StoreWorkspaceTransitionRepository(
                syncStateResetRepository = get(),
                port = get(),
                invalidation = get(),
            )
        } bind WorkspaceTransitionRepository::class
    }
