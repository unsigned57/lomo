package com.lomo.app.di

import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.feature.conflict.SyncConflictViewModel
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MainViewModelDependencies
import com.lomo.app.feature.main.RecordingViewModel
import com.lomo.app.feature.main.SidebarViewModel
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.review.DailyReviewViewModel
import com.lomo.app.feature.review.DailyReviewViewModelDependencies
import com.lomo.app.feature.search.SearchViewModel
import com.lomo.app.feature.search.SearchViewModelDependencies
import com.lomo.app.feature.settings.SettingsViewModel
import com.lomo.app.feature.share.ShareViewModel
import com.lomo.app.feature.statistics.StatisticsViewModel
import com.lomo.app.feature.synccenter.SyncCenterViewModel
import com.lomo.app.feature.tag.TagFilterViewModel
import com.lomo.app.feature.tag.TagFilterViewModelDependencies
import com.lomo.app.feature.task.TasksViewModel
import com.lomo.app.feature.trash.TrashViewModel
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.navigation.LanShareAvailabilityViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    // View-model collaborator aggregates. Each is defined once from the same graph the view-model
    // used to resolve directly, so the constructor stays within the parameter budget without hiding
    // a missing binding behind an allowlist.
    factory {
        MainViewModelDependencies(
            mainMemoListQueryUseCase = get(),
            observeActiveDayCountUseCase = get(),
            setMemoPinnedUseCase = get(),
            appConfigStateProvider = get(),
            appConfigUiCoordinator = get(),
            sidebarStateHolder = get(),
            versionHistoryCoordinator = get(),
            memoUiMapper = get(),
            imageMapProvider = get(),
            mainMemoMutationCoordinator = get(),
            workspaceCoordinator = get(),
            startupCoordinator = get(),
            markReminderDoneUseCase = get(),
            dispatcherProvider = get(),
            externalAppCommandStore = get(),
        )
    }
    factory {
        TagFilterViewModelDependencies(
            getMemosByTagPageUseCase = get(),
            observeActiveDayCountUseCase = get(),
            appConfigStateProvider = get(),
            appConfigUiCoordinator = get(),
            imageMapProvider = get(),
            memoUiMapper = get(),
            deleteMemoUseCase = get(),
            updateMemoContentUseCase = get(),
            toggleMemoCheckboxUseCase = get(),
            saveImageUseCase = get(),
            loadEditableMemoUseCase = get(),
            workspaceCoordinator = get(),
        )
    }
    factory {
        SearchViewModelDependencies(
            observeActiveDayCountUseCase = get(),
            appConfigStateProvider = get(),
            appConfigUiCoordinator = get(),
            imageMapProvider = get(),
            projectionMapper = get(),
            searchMemosPageUseCase = get(),
            deleteMemoUseCase = get(),
            updateMemoContentUseCase = get(),
            saveImageUseCase = get(),
            toggleMemoCheckboxUseCase = get(),
            loadEditableMemoUseCase = get(),
            workspaceCoordinator = get(),
        )
    }
    factory {
        DailyReviewViewModelDependencies(
            observeActiveDayCountUseCase = get(),
            appConfigStateProvider = get(),
            appConfigUiCoordinator = get(),
            imageMapProvider = get(),
            memoUiMapper = get(),
            deleteMemoUseCase = get(),
            updateMemoContentUseCase = get(),
            toggleMemoCheckboxUseCase = get(),
            saveImageUseCase = get(),
            dailyReviewQueryUseCase = get(),
            dailyReviewSessionUseCase = get(),
        )
    }
    viewModelOf(::SyncConflictStateViewModel)
    viewModelOf(::SyncConflictViewModel)
    viewModelOf(::SyncCenterViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::RecordingViewModel)
    viewModelOf(::SidebarViewModel)
    viewModelOf(::MemoEditorViewModel)
    viewModelOf(::DailyReviewViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ShareViewModel)
    viewModelOf(::StatisticsViewModel)
    viewModelOf(::TasksViewModel)
    viewModelOf(::TagFilterViewModel)
    viewModelOf(::TrashViewModel)
    viewModelOf(::AppUpdateViewModel)
    viewModelOf(::LanShareAvailabilityViewModel)
}
