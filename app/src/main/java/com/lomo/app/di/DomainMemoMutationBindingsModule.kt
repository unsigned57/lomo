package com.lomo.app.di

import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.RecordingSessionUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SetMemoPinnedUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import org.koin.dsl.module

val domainMemoMutationModule = module {
    single { DeleteMemoUseCase(get()) }
    single { SetMemoPinnedUseCase(get()) }
    single { SyncAndRebuildUseCase(get(), get(), get()) }
    single { RefreshMemosUseCase(get()) }
    single { ToggleMemoCheckboxUseCase(get(), get()) }
    single { UpdateMemoContentUseCase(get(), get(), get(), get()) }
    single { CreateMemoUseCase(get(), get(), get()) }
    single { RecordingSessionUseCase(get()) }
}
