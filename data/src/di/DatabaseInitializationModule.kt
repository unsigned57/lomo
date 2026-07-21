package com.lomo.data.di

import com.lomo.data.local.StoreDatabaseInitializer
import com.lomo.domain.repository.DatabaseInitializationRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val databaseInitializationModule =
    module {
        single {
            StoreDatabaseInitializer(
                context = androidContext(),
                port = get(),
                invalidation = get(),
            )
        } bind DatabaseInitializationRepository::class
    }
