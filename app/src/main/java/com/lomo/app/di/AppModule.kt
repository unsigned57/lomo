package com.lomo.app.di

import com.lomo.app.AndroidExternalAppCommandStore
import com.lomo.app.ExternalAppCommandStore
import com.lomo.app.AppShutdownCoordinator
import com.lomo.app.TrustedLaunchIntents
import com.lomo.app.TrustedLaunchSecretStore
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.startup.AndroidDynamicShortcutPublisher
import com.lomo.app.startup.DynamicShortcutPublisher
import com.lomo.app.startup.AppStartupCoordinator
import com.lomo.app.startup.SecuritySessionRestoreTask
import com.lomo.app.startup.StartupUpdateCheckTask
import com.lomo.app.startup.DynamicShortcutStartupTask
import com.lomo.app.startup.WorkspaceMaintenanceStartupTask
import com.lomo.app.startup.ThemeApplicationStartupTask
import com.lomo.app.startup.ThemeSideEffect
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.main.MainMemoMutationCoordinator
import com.lomo.app.feature.main.MainSidebarStateHolder
import com.lomo.app.feature.main.MainStartupCoordinator
import com.lomo.app.feature.main.MainVersionHistoryCoordinator
import com.lomo.app.feature.main.MainWorkspaceCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.settings.SettingsCoordinatorFactory
import com.lomo.app.feature.share.LanShareUiCoordinator
import com.lomo.app.feature.share.ShareErrorPolicy
import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDownloadManager
import com.lomo.app.feature.update.UpdateStartupOrchestrator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.util.ShareUtils
import com.lomo.app.util.ShareCardBitmapRenderer
import com.lomo.app.presentation.sharecard.ShareCardDisplayFormatter
import com.lomo.ui.media.AudioPlayerController
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.DefaultDispatcherProvider
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.koin.android.ext.koin.androidContext


val appModule = module {
    // Core providers
    single<AudioPlayerController> { get<AudioPlayerManager>() }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<DynamicShortcutPublisher> { get<AndroidDynamicShortcutPublisher>() }
    single<ExternalAppCommandStore> { get<AndroidExternalAppCommandStore>() }

    // App core classes
    single { AudioPlayerManager(androidContext(), get()) }
    single { AndroidDynamicShortcutPublisher(androidContext(), get()) }
    single { AndroidExternalAppCommandStore(androidContext()) }
    single { AppShutdownCoordinator(get(), get()) }
    single { TrustedLaunchSecretStore(androidContext()) }
    single { TrustedLaunchIntents(androidContext(), get()) }

    // App Startup coordinators and tasks
    single { AppStartupCoordinator(get(named("AppScope")), get(), get(), get(), get(), get()) }
    single { SecuritySessionRestoreTask(get()) }
    single { StartupUpdateCheckTask(get()) }
    single { DynamicShortcutStartupTask(get()) }
    single { WorkspaceMaintenanceStartupTask(get()) }
    single { ThemeApplicationStartupTask(get(), get()) }
    single { ThemeSideEffect(androidContext()) }

    // Feature coordinators/mappers/providers
    single { AppConfigStateProvider(get(), get(), get(), get(named("AppScope"))) }
    single { AppConfigUiCoordinator(get()) }
    single { MemoCollectionProjectionMapper(get()) }
    single { MainMemoMutationCoordinator(get(), get(), get()) }
    single { MainSidebarStateHolder() }
    single { MainStartupCoordinator(get(), get(), get()) }
    single { MainVersionHistoryCoordinator(get(), get()) }
    single { MainWorkspaceCoordinator(get(), get(), get(), get()) }
    single { MemoUiMapper(get()) }
    single {
        SettingsCoordinatorFactory(
            get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), getOrNull()
        )
    }
    single { LanShareUiCoordinator(get()) }
    single { ShareErrorPolicy() }
    single { AppUpdateChecker(get(), get(), get()) }
    single { AppUpdateDownloadManager(androidContext(), get(), get()) }
    single { UpdateStartupOrchestrator(get<AppUpdateChecker>()) }
    single { ImageMapProvider(get()) }
    single { AppWidgetRepository(androidContext()) }

    // Sharing helpers
    single { ShareCardDisplayFormatter() }
    single { ShareCardBitmapRenderer(get(), get()) }
    single { ShareUtils(get(), get(), get()) }
}
