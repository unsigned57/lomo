package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.ImageLocationCacheDao
import org.koin.dsl.module

val imageLocationCacheModule = module {
    single<ImageLocationCacheDao> { get<MemoDatabase>().imageLocationCacheDao() }
}
