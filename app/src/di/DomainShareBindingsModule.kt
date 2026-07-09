package com.lomo.app.di

import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import org.koin.dsl.module

val domainShareModule = module {
    single { PrepareShareCardContentUseCase() }
    single { ExtractShareAttachmentsUseCase() }
    single { PersistShareImageUseCase(get()) }
}
