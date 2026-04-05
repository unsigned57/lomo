package com.lomo.app

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lomo.app.BuildConfig
import com.lomo.app.theme.ThemeResyncPolicy
import com.lomo.app.theme.applyAppNightMode
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
import javax.inject.Inject

@HiltAndroidApp
class LomoApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncPolicyRepository: SyncPolicyRepository

    @Inject lateinit var appConfigRepository: AppConfigRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var currentThemeMode: ThemeMode = ThemeMode.SYSTEM
    private var lastKnownUiMode: Int? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        lastKnownUiMode = resources.configuration.uiMode

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        observeThemeMode()

        // Defer non-critical worker registration off the main thread.
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
                    currentThemeMode = themeMode
                    withContext(Dispatchers.Main.immediate) {
                        applyAppNightMode(
                            context = this@LomoApplication,
                            themeMode = themeMode,
                        )
                    }
                }
            }.onFailure { error ->
                Timber.w(error, "Failed to observe persisted theme mode, fallback to system")
                currentThemeMode = ThemeMode.SYSTEM
                withContext(Dispatchers.Main.immediate) {
                    applyAppNightMode(
                        context = this@LomoApplication,
                        themeMode = ThemeMode.SYSTEM,
                    )
                }
            }
        }
    }
}
