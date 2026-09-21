package com.lomo.app.di

import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.ListMemoTasksUseCase
import com.lomo.domain.usecase.LoadCreateDraftUseCase
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.MemoTrashUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ObserveSidebarStatisticsUseCase
import com.lomo.domain.usecase.SaveCreateDraftUseCase
import org.koin.dsl.module

val domainMemoReadModule = module {
    single { DailyReviewQueryUseCase(get()) }
    single { DailyReviewSessionUseCase(get()) }
    single { MemoStatisticsUseCase(get()) }
    single { ListMemoTasksUseCase(get()) }
    single { ObserveActiveDayCountUseCase(get()) }
    single { ObserveSidebarStatisticsUseCase(get()) }
    single { MemoTrashUseCase(get()) }
    single { LoadCreateDraftUseCase(get()) }
    single { SaveCreateDraftUseCase(get()) }
}
