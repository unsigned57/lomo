package com.lomo.app.di

import com.lomo.domain.usecase.GetMemosByTagPageUseCase
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.SearchMemosPageUseCase
import org.koin.dsl.module

val domainSearchModule = module {
    single { MainMemoListQueryUseCase(get(), get()) }
    single { SearchMemosPageUseCase(get()) }
    single { GetMemosByTagPageUseCase(get()) }
}
