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
import com.lomo.app.BuildConfig
import com.lomo.app.di.appModule
import com.lomo.app.di.appScopeModule
import com.lomo.app.di.domainAppUpdateModule
import com.lomo.app.di.domainCoreModule
import com.lomo.app.di.domainMemoMutationModule
import com.lomo.app.di.domainMemoReadModule
import com.lomo.app.di.domainSearchModule
import com.lomo.app.di.domainShareModule
import com.lomo.app.di.domainSyncModule
import com.lomo.app.di.domainWorkspaceModule
import com.lomo.app.di.viewModelModule
import com.lomo.app.feature.image.LOMO_IMAGE_LOADER_MEMORY_CACHE_PERCENT
import com.lomo.app.feature.image.lomoImageDecoderCoroutineContext
import com.lomo.app.feature.image.lomoImageDiskCache
import com.lomo.app.feature.image.lomoImageFetcherCoroutineContext
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.startup.AppStartupCoordinator
import com.lomo.domain.repository.DatabaseInitializationRepository
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    listInstance.map { module ->
        require(module is Module) {
            "DataModulesKt.getDataModules returned ${module?.javaClass?.name ?: "null"}, expected Module."
        }
        module
    }
}

class LomoApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory,
    KoinComponent {

    private val syncPolicyRepository: SyncPolicyRepository by inject()
    private val appStartupCoordinator: AppStartupCoordinator by inject()
    private val databaseInitializationRepository: DatabaseInitializationRepository by inject()
    private val appShutdownCoordinator: AppShutdownCoordinator by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncStartupRegistered = AtomicBoolean(false)
    private var lastKnownUiMode: Int? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(get<KoinWorkerFactory>())
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
            .fetcherCoroutineContext(lomoImageFetcherCoroutineContext)
            .decoderCoroutineContext(lomoImageDecoderCoroutineContext)
            .crossfade(true)
            .serviceLoaderEnabled(false)
            .build()

    override fun onCreate() {
        super.onCreate()
        
        // Start Koin
        startKoin {
            androidContext(this@LomoApplication)
            workManagerFactory()
            modules(
                // Data modules loaded via reflection
                dataModules +
                
                // App modules
                listOf(
                    appModule,
                    appScopeModule,
                    domainAppUpdateModule,
                    domainCoreModule,
                    domainMemoMutationModule,
                    domainMemoReadModule,
                    domainSearchModule,
                    domainShareModule,
                    domainSyncModule,
                    domainWorkspaceModule,
                    viewModelModule
                )
            )
        }

        lastKnownUiMode = resources.configuration.uiMode
        ShareRoutePayloadStore.configurePersistentCache(cacheDir.resolve(SHARE_ROUTE_PAYLOAD_CACHE_DIR))

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        appStartupCoordinator.start()

        appScope.launch(Dispatchers.IO) {
            runCatching {
                databaseInitializationRepository.ensureReady()
            }.onFailure { error ->
                Timber.e(error, "Failed to warm memo database")
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (!syncStartupRegistered.compareAndSet(false, true)) {
                        return
                    }
                    appScope.launch {
                        runCatching {
                            syncPolicyRepository.ensureCoreSyncActive()
                        }.onFailure { error ->
                            Timber.e(error, "Failed to schedule sync")
                        }

                        runCatching {
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }.onFailure { error ->
                            Timber.e(error, "Failed to schedule remote sync")
                        }
                    }

                    // Reconcile reminder alarms on every cold start.
                    appScope.launch {
                        runCatching {
                            reminderCoordinator.rebuildAll()
                        }.onFailure { error ->
                            Timber.e(error, "Failed to rebuild reminder alarms after process start")
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

        appStartupCoordinator.resyncThemeOnConfigurationChange(
            previousUiMode = previousUiMode,
            currentUiMode = newConfig.uiMode,
        )
    }

    override fun onTrimMemory(level: Int) {
        appShutdownCoordinator.closeForTrimMemory(level) { error ->
            Timber.w(error, "Failed to close app resources while trimming memory")
        }
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        appShutdownCoordinator.closeForLowMemory { error ->
            Timber.w(error, "Failed to close app resources on low memory")
        }
        super.onLowMemory()
    }

    override fun onTerminate() {
        appShutdownCoordinator.closeAppResources { error ->
            Timber.w(error, "Failed to close app resources")
        }
        super.onTerminate()
    }
}

private const val SHARE_ROUTE_PAYLOAD_CACHE_DIR = "share-route-payloads"
