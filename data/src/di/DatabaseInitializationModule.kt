package com.lomo.data.di

import com.lomo.data.local.DatabaseInitializer
import com.lomo.domain.repository.DatabaseInitializationRepository
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind

val databaseInitializationModule = module {
    single { DatabaseInitializer(androidContext(), get()) } bind DatabaseInitializationRepository::class
}
