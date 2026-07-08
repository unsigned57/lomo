package com.lomo.app.di

import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.feature.conflict.SyncConflictViewModel
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.RecordingViewModel
import com.lomo.app.feature.main.SidebarViewModel
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.review.DailyReviewViewModel
import com.lomo.app.feature.search.SearchViewModel
import com.lomo.app.feature.settings.SettingsViewModel
import com.lomo.app.feature.share.ShareViewModel
import com.lomo.app.feature.statistics.StatisticsViewModel
import com.lomo.app.feature.tag.TagFilterViewModel
import com.lomo.app.feature.trash.TrashViewModel
import com.lomo.app.feature.update.AppUpdateViewModel
import com.lomo.app.navigation.LanShareAvailabilityViewModel
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModelOf

val viewModelModule = module {
    viewModelOf(::SyncConflictStateViewModel)
    viewModelOf(::SyncConflictViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::RecordingViewModel)
    viewModelOf(::SidebarViewModel)
    viewModelOf(::MemoEditorViewModel)
    viewModelOf(::DailyReviewViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ShareViewModel)
    viewModelOf(::StatisticsViewModel)
    viewModelOf(::TagFilterViewModel)
    viewModelOf(::TrashViewModel)
    viewModelOf(::AppUpdateViewModel)
    viewModelOf(::LanShareAvailabilityViewModel)
}
