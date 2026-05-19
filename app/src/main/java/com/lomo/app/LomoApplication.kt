package com.lomo.app

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.appcompat.app.AppCompatDelegate
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
import com.lomo.app.theme.ThemeResyncPolicy
import com.lomo.app.theme.applyAppNightMode
import com.lomo.app.theme.resolvePlatformNightMode
import com.lomo.app.theme.toAppCompatNightMode
import com.lomo.domain.repository.DatabaseInitializationRepository
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.SyncPolicyRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Inject lateinit var appConfigRepository: AppConfigRepository
    @Inject lateinit var databaseInitializationRepository: DatabaseInitializationRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncStartupRegistered = AtomicBoolean(false)
    @Volatile
    private var currentThemeMode: ThemeMode = ThemeMode.SYSTEM
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

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        observeThemeMode()

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
                }
            },
        )
    }

    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        val previousUiMode = lastKnownUiMode ?: resources.configuration.uiMode
        super.onConfigurationChanged(newConfig)
        lastKnownUiMode = newConfig.uiMode

        if (
            ThemeResyncPolicy.shouldResyncOnConfigurationChange(
                themeMode = currentThemeMode,
                previousUiMode = previousUiMode,
                currentUiMode = newConfig.uiMode,
            )
        ) {
            applyAppNightMode(this, currentThemeMode)
        }
    }

    private fun observeThemeMode() {
        appScope.launch {
            runCatching {
                appConfigRepository.getThemeMode().collectLatest { themeMode ->
                    applyThemeIfChanged(themeMode)
                }
            }.onFailure { error ->
                Timber.w(error, "Failed to observe persisted theme mode, fallback to system")
                applyThemeIfChanged(ThemeMode.SYSTEM)
            }
        }
    }

    private suspend fun applyThemeIfChanged(themeMode: ThemeMode) {
        currentThemeMode = themeMode
        val targetCompatMode = themeMode.toAppCompatNightMode()
        val targetPlatformMode = resolvePlatformNightMode(themeMode)
        val alreadyAppliedCompatMode = AppCompatDelegate.getDefaultNightMode() == targetCompatMode
        val alreadyAppliedPlatformMode =
            targetPlatformMode == null || resources.configuration.uiMode.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    getSystemService(android.app.UiModeManager::class.java)?.nightMode == targetPlatformMode
                } else {
                    true
                }
            }
        if (alreadyAppliedCompatMode && alreadyAppliedPlatformMode) {
            return
        }
        withContext(Dispatchers.Main.immediate) {
            applyAppNightMode(
                context = this@LomoApplication,
                themeMode = themeMode,
            )
        }
    }
}
