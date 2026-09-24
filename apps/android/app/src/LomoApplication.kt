package com.lomo.app

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import com.lomo.app.di.processStartupPlan
import com.lomo.app.feature.image.LOMO_IMAGE_LOADER_MEMORY_CACHE_PERCENT
import com.lomo.app.feature.image.lomoImageDecoderCoroutineContext
import com.lomo.app.feature.image.lomoImageDiskCache
import com.lomo.app.feature.image.lomoImageFetcherCoroutineContext
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.startup.AppStartupCoordinator
import com.lomo.app.startup.currentProcessName
import com.lomo.app.widget.WidgetProjectionBinder
import com.lomo.domain.model.WorkspaceProcessDuty
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.module.Module
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private val dataModules: List<Module> by lazy {
    val p1 = "com.lomo"
    val p2 = "data.di.DataModulesKt"
    val listClass = Class.forName("$p1.$p2")
    val listGetter = listClass.getMethod("getDataModules")
    val listInstance = listGetter.invoke(null)
    require(listInstance is List<*>) {
        "DataModulesKt.getDataModules must return List<Module>."
    }
    listInstance
        .onEach { module ->
            require(module is Module) {
                val returnedClass = module?.javaClass
                "DataModulesKt.getDataModules returned ${returnedClass?.name ?: "null"}, expected Module."
            }
        }
        .filterIsInstance<Module>()
}

class LomoApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory,
    KoinComponent {

    private val processDuty: WorkspaceProcessDuty by lazy {
        WorkspaceProcessDuty.forProcess(
            packageName = packageName,
            processName = currentProcessName(this),
        )
    }

    private val syncPolicyRepository: SyncPolicyRepository by inject()
    private val appStartupCoordinator: AppStartupCoordinator by inject()
    private val appShutdownCoordinator: AppShutdownCoordinator by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val engineReadinessRepository: EngineReadinessRepository by inject()
    private val securitySessionController: SecuritySessionController by inject()
    
    // behavior-contract: unmanaged-scope-ok: process-lifetime app
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncStartupRegistered = AtomicBoolean(false)
    private var lastKnownUiMode: Int? = null

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .apply {
                    // Projection-only processes install no Koin graph, so they can only hand
                    // WorkManager the platform default factory; widget duties never run workers.
                    if (processDuty.ownsNativeEngine) {
                        setWorkerFactory(get<KoinWorkerFactory>())
                    }
                }
                .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache(
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, LOMO_IMAGE_LOADER_MEMORY_CACHE_PERCENT)
                    .build(),
            ).diskCache(lomoImageDiskCache(context.cacheDir))
            .diskCachePolicy(CachePolicy.ENABLED)
            .fetcherCoroutineContext(lomoImageFetcherCoroutineContext(Dispatchers.IO))
            .decoderCoroutineContext(lomoImageDecoderCoroutineContext(Dispatchers.IO))
            .crossfade(true)
            .serviceLoaderEnabled(false)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging in every process before the startup trace.
        if (AppBuildInfo.isDebuggable(this)) {
            Timber.plant(Timber.DebugTree())
        }

        val startupPlan =
            processStartupPlan(
                duty = processDuty,
                processName = currentProcessName(this),
                dataModules =
                    if (processDuty.ownsNativeEngine) {
                        dataModules
                    } else {
                        emptyList()
                    },
            )
        Timber.i("startup plan %s", startupPlan.describe())

        if (startupPlan.modules.isNotEmpty()) {
            startKoin {
                androidContext(this@LomoApplication)
                workManagerFactory()
                modules(startupPlan.modules)
            }
        }

        lastKnownUiMode = resources.configuration.uiMode

        if (!processDuty.ownsNativeEngine) {
            // A projection-only process (Glance widget) renders the file snapshot and mints trusted
            // intents; it must never load the data graph, WorkManager, native engine, DataStore, or
            // a workspace session.
            return
        }

        ShareRoutePayloadStore.configurePersistentCache(cacheDir.resolve(SHARE_ROUTE_PAYLOAD_CACHE_DIR))

        get<WidgetProjectionBinder>()
        appStartupCoordinator.start()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    securitySessionController.recordBackgrounded()
                }

                override fun onStart(owner: LifecycleOwner) {
                    // Foreground entry re-reads the authoritative engine snapshot. Grants can be
                    // revoked and workspaces removed while the process is backgrounded, so a stale
                    // Ready would keep the write gate open against a workspace that is gone.
                    runCatching { engineReadinessRepository.resnapshot() }
                        .onFailure { error ->
                            Timber.e(error, "Failed to resnapshot engine readiness on foreground entry")
                        }
                    if (!syncStartupRegistered.compareAndSet(false, true)) {
                        return
                    }
                    appScope.launch {
                        runCatching {
                            syncPolicyRepository.ensureCoreSyncActive()
                        }.onFailure { error ->
                            Timber.e(error, "Failed to schedule sync")
                        }

                        try {
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Timber.e(error, "Failed to schedule remote sync")
                        }
                    }

                    // Reminder queries are admitted only by the published mount: bare authority
                    // without a verified projection must not rebuild alarms against a stale store.
                    appScope.launch {
                        engineReadinessRepository.mount
                            .map { it.admittedAuthority }
                            .distinctUntilChanged()
                            .filterNotNull()
                            .collectLatest {
                                try {
                                    reminderCoordinator.rebuildAll()
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Timber.e(error, "Failed to rebuild reminder alarms after workspace activation")
                                }
                            }
                        }
                }
            },
        )
    }

    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        val previousUiMode = lastKnownUiMode ?: resources.configuration.uiMode
        super.onConfigurationChanged(newConfig)
        lastKnownUiMode = newConfig.uiMode

        if (!processDuty.ownsNativeEngine) {
            return
        }
        appStartupCoordinator.resyncThemeOnConfigurationChange(
            previousUiMode = previousUiMode,
            currentUiMode = newConfig.uiMode,
        )
    }

    override fun onTrimMemory(level: Int) {
        if (processDuty.ownsNativeEngine) {
            appShutdownCoordinator.closeForTrimMemory(level) { error ->
                Timber.w(error, "Failed to close app resources while trimming memory")
            }
        }
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        if (processDuty.ownsNativeEngine) {
            appShutdownCoordinator.closeForLowMemory { error ->
                Timber.w(error, "Failed to close app resources on low memory")
            }
        }
        super.onLowMemory()
    }

    override fun onTerminate() {
        if (processDuty.ownsNativeEngine) {
            appShutdownCoordinator.closeAppResources { error ->
                Timber.w(error, "Failed to close app resources")
            }
        }
        super.onTerminate()
    }
}

private const val SHARE_ROUTE_PAYLOAD_CACHE_DIR = "share-route-payloads"
