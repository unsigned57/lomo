package com.lomo.data.di

import org.koin.core.module.Module

val dataModules: List<Module> = listOf(
    appUpdateDataModule,
    applicationScopeModule,
    coreDataRepositoryModule,
    databaseInitializationModule,
    databaseListModule,
    databaseModule,
    imageLocationCacheModule,
    mediaShareModule,
    memoRepositoryModule,
    recordingModule,
    reminderModule,
    storageDataSourceModule,
    syncDataModule
)
