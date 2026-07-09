package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.DefaultMainListDao
import org.koin.dsl.module

val databaseListModule = module {
    single<DefaultMainListDao> { get<MemoDatabase>().defaultMainListDao() }
}
