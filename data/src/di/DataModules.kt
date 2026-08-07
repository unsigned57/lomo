package com.lomo.data.di

import org.koin.core.module.Module

val dataModules: List<Module> = listOf(
    appUpdateDataModule,
    applicationScopeModule,
    coreDataRepositoryModule,
    databaseListModule,
    databaseModule,
    engineModule,
    mediaShareModule,
    memoRepositoryModule,
    recordingModule,
    reminderModule,
    storageDataSourceModule,
    syncDataModule
)
