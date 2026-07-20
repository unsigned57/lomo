package com.lomo.data.di

import com.lomo.data.engine.AndroidPlatformActionAccess
import com.lomo.data.engine.AndroidPlatformActionExecutor
import com.lomo.data.engine.BoltFfiNativeEngineFactory
import com.lomo.data.engine.BoltFfiNativeEnginePort
import com.lomo.data.engine.CapabilityRegistry
import com.lomo.data.engine.ContentResolverPlatformDocumentsGateway
import com.lomo.data.engine.ExchangeResolver
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.NativeEngineOpenRequest
import com.lomo.data.engine.PlatformBatchRunner
import com.lomo.data.engine.RustEngineAdapter
import com.lomo.data.engine.WorkspaceCandidateProbe
import com.lomo.data.engine.WorkspaceMarkdownOwner
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.WorkspaceCandidateValidator
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Wires the sole production Rust engine readiness session, SAF platform-action edge, and candidate
 * workspace probe.
 *
 * Generated BoltFFI classes stay inside `data.engine`; domain only sees
 * [EngineReadinessRepository] and [WorkspaceCandidateValidator]. Close runs through Koin `onClose`
 * so process teardown releases native handles. Workspace activation is performed by the session
 * after selection / cold restore.
 */
val engineModule =
    module {
        single { CapabilityRegistry() }
        single {
            val request = NativeEngineOpenRequest.forAppFilesDir(androidContext().filesDir)
            ExchangeResolver(request.exchangeRoot)
        }
        single<com.lomo.data.engine.PlatformDocumentsGateway> {
            ContentResolverPlatformDocumentsGateway(androidContext().contentResolver)
        }
        single {
            AndroidPlatformActionAccess(
                registry = get(),
                exchange = get(),
                documents = get(),
            )
        }
        single<com.lomo.data.engine.PlatformActionAccess> {
            get<AndroidPlatformActionAccess>()
        }
        single {
            AndroidPlatformActionExecutor(
                access = get(),
                currentTimeMillis = System::currentTimeMillis,
            )
        }
        single<WorkspaceCandidateValidator> {
            WorkspaceCandidateProbe(androidContext())
        }
        single {
            val filesDir = androidContext().filesDir
            val registry = get<CapabilityRegistry>()
            val executor = get<AndroidPlatformActionExecutor>()
            val exchangeResolver = get<ExchangeResolver>()
            ManagedEngineSession(
                filesDir = filesDir,
                capabilityRegistry = registry,
                openAdapter = { request ->
                    val port: BoltFfiNativeEnginePort =
                        BoltFfiNativeEngineFactory.openPort(request, exchangeResolver)
                    val runner =
                        PlatformBatchRunner(
                            native = port,
                            executor = executor,
                        )
                    RustEngineAdapter(native = port, platformBatchRunner = runner)
                },
                directorySettingsRepository = get<DirectorySettingsRepository>(),
                appScope = get(named("ApplicationScope")),
                isContentUri = ::isContentStorageUri,
            )
        } withOptions {
            onClose { repository ->
                (repository as? AutoCloseable)?.close()
            }
        }
        single<EngineReadinessRepository> { get<ManagedEngineSession>() }
        single<MarkdownWorkspaceRepository> { get<ManagedEngineSession>() }
        single<MarkdownReminderRepository> { get<ManagedEngineSession>() }
        single<WorkspaceMarkdownOwner> { get<ManagedEngineSession>() }
    }
