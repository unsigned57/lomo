package com.lomo.data.di

import com.lomo.data.engine.AndroidPlatformActionAccess
import com.lomo.data.engine.AndroidPlatformActionExecutor
import com.lomo.data.engine.BoltFfiNativeEngineFactory
import com.lomo.data.engine.CapabilityRegistry
import com.lomo.data.engine.ContentResolverPlatformDocumentsGateway
import com.lomo.data.engine.ExchangeResolver
import com.lomo.data.engine.LeasedMarkdownWorkspace
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.NativeEngineOpenRequest
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.WorkspaceCandidateProbe
import com.lomo.data.engine.currentProcessName
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.model.WorkspaceProcessDuty
import com.lomo.nativebridge.eventSequenceRequiresFullInvalidate
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
 * after selection / cold restore. Native engine open is started by the session itself on
 * ApplicationScope (IO) only when the process owns the workspace engine, never as a
 * constructor side effect of a Koin consumption chain.
 */
val engineModule =
    module {
        single { CapabilityRegistry() }
        single {
            StoreInvalidationBus { lastSeen, incoming ->
                eventSequenceRequiresFullInvalidate(lastSeen.toULong(), incoming.toULong())
            }
        }
        single {
            val request = NativeEngineOpenRequest.forAppFilesDir(androidContext().filesDir)
            ExchangeResolver(request.exchangeRoot)
        }
        single<com.lomo.data.engine.PlatformDocumentsGateway> {
            ContentResolverPlatformDocumentsGateway(androidContext().contentResolver)
        }
        single { com.lomo.data.engine.DirectRootDocumentsGateway() }
        single {
            AndroidPlatformActionAccess(
                registry = get(),
                exchange = get(),
                documents = get(),
                directDocuments = get(),
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
            WorkspaceProcessDuty.forProcess(
                packageName = androidContext().packageName,
                processName = currentProcessName(androidContext()),
            )
        }
        single {
            val filesDir = androidContext().filesDir
            val registry = get<CapabilityRegistry>()
            val executor = get<AndroidPlatformActionExecutor>()
            val exchangeResolver = get<ExchangeResolver>()
            val documents = get<com.lomo.data.engine.PlatformDocumentsGateway>()
            val invalidation = get<StoreInvalidationBus>()
            ManagedEngineSession(
                filesDir = filesDir,
                capabilityRegistry = registry,
                openAdapter = { request ->
                    BoltFfiNativeEngineFactory.openAdapter(
                        request,
                        exchangeResolver,
                        executor,
                        documents,
                        invalidation,
                    )
                },
                directorySettingsRepository = get<DirectorySettingsRepository>(),
                appScope = get(named("ApplicationScope")),
                isContentUri = ::isContentStorageUri,
                invalidation = invalidation,
                ownsNativeEngine = get(),
            )
        } withOptions {
            onClose { repository ->
                (repository as? AutoCloseable)?.close()
            }
        }
        single<EngineReadinessRepository> { get<ManagedEngineSession>() }
        single<SessionNativeBridge> { get<ManagedEngineSession>() }
        // Markdown/reminder mutations are admitted at this owning boundary instead of repeating a
        // readiness condition in every use case, so foreground toggles and background alarm
        // rewrites are drained by the same workspace switch barrier.
        single {
            LeasedMarkdownWorkspace(delegate = get<ManagedEngineSession>(), lease = get())
        }
        single<MarkdownWorkspaceRepository> { get<LeasedMarkdownWorkspace>() }
        single<MarkdownReminderRepository> { get<LeasedMarkdownWorkspace>() }
    }
