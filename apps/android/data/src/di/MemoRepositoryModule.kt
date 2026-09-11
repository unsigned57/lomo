package com.lomo.data.di

import com.lomo.data.diagnostics.RingBufferEngineDiagnosticsRecorder
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.store.BoltFfiStorePort
import com.lomo.data.engine.store.StorePort
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.repository.StoreMemoMutationRepository
import com.lomo.data.repository.StoreMemoQueryRepository
import com.lomo.data.repository.StoreMemoSearchRepository
import com.lomo.data.repository.StoreMemoStatisticsRepository
import com.lomo.data.repository.StoreMemoTaskRepository
import com.lomo.data.repository.StoreMemoTrashRepository
import com.lomo.data.repository.StoreMemoVersionRepository
import com.lomo.data.repository.StoreWorkspaceStateResolver
import com.lomo.data.util.MarkdownWorkspaceContentProjector
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
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
        single<EngineDiagnosticsRecorder> { RingBufferEngineDiagnosticsRecorder() }
        single<StorePort> {
            val session = get<ManagedEngineSession>()
            BoltFfiStorePort(nativeBridge = session, session = session)
        }

        single {
            StoreMemoQueryRepository(
                port = get(),
                invalidation = get(),
                readiness = get(),
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
                diagnostics = get(),
                pendingStages = get(),
            )
        } bind MemoMutationRepository::class

        single {
            StoreMemoSearchRepository(
                port = get(),
                session = get<ManagedEngineSession>(),
                invalidation = get(),
            )
        } bind MemoSearchRepository::class
        single {
            StoreMemoStatisticsRepository(
                port = get(),
                session = get<ManagedEngineSession>(),
                invalidation = get(),
                readiness = get(),
                applicationScope = get(named("ApplicationScope")),
            )
        } bind MemoStatisticsRepository::class
        single {
            StoreMemoTrashRepository(
                port = get(),
                writeLease = get(),
                invalidation = get(),
                readiness = get(),
                reminderScheduler = get(),
                mediaRepository = get(),
            )
        } bind MemoTrashRepository::class
        single {
            StoreMemoVersionRepository(
                port = get(),
                session = get<ManagedEngineSession>(),
            )
        } bind MemoVersionRepository::class

        single {
            StoreMemoTaskRepository(
                session = get<ManagedEngineSession>(),
                invalidation = get(),
                writeLease = get(),
                readiness = get(),
            )
        } bind MemoTaskRepository::class

        single {
            StoreWorkspaceStateResolver(port = get(), invalidation = get())
        } bind WorkspaceStateResolver::class
    }
