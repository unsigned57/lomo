package com.lomo.app

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
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
import com.lomo.app.feature.image.LOMO_IMAGE_LOADER_MEMORY_CACHE_PERCENT
import com.lomo.app.feature.image.lomoImageDecoderCoroutineContext
import com.lomo.app.feature.image.lomoImageDiskCache
import com.lomo.app.feature.image.lomoImageFetcherCoroutineContext
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.startup.AppStartupCoordinator
import com.lomo.domain.repository.DatabaseInitializationRepository
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.SyncPolicyRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltAndroidApp
class LomoApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncPolicyRepository: SyncPolicyRepository

    @Inject lateinit var appStartupCoordinator: AppStartupCoordinator
    @Inject lateinit var databaseInitializationRepository: DatabaseInitializationRepository
    @Inject lateinit var appShutdownCoordinator: AppShutdownCoordinator
    @Inject lateinit var reminderCoordinator: ReminderCoordinator
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncStartupRegistered = AtomicBoolean(false)
    private var lastKnownUiMode: Int? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

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

                    // Reconcile reminder alarms on every cold start. AlarmManager alarms are cleared
                    // when the OS (or an OEM "clear recents" force-stop) kills the process, and the
                    // boot receiver only fires on device reboot. Rebuilding here re-arms every memo's
                    // reminder when the app is reopened, and rebuildAll fires past-due markers
                    // immediately so reminders missed while the process was dead are caught up.
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
