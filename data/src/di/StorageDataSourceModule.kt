package com.lomo.data.di

import com.lomo.data.repository.DefaultWorkspaceMediaAccess
import com.lomo.data.repository.MemoVersionBlobRoot
import com.lomo.data.repository.WorkspaceMediaAccess
import com.lomo.data.source.FileDataSourceImpl
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.source.FileStorageBackendResolver
import com.lomo.data.source.FileWorkspaceConfigSourceDelegate
import com.lomo.data.source.FileMarkdownStorageDataSourceDelegate
import com.lomo.data.source.FileMediaStorageDataSourceDelegate
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds

val storageDataSourceModule = module {
    single { MemoVersionBlobRoot.fromFilesDir(androidContext().filesDir) }

    single { FileStorageBackendResolver(androidContext(), get()) }
    single { FileWorkspaceConfigSourceDelegate(androidContext(), get(), get()) }
    single { FileMarkdownStorageDataSourceDelegate(get()) }
    single { FileMediaStorageDataSourceDelegate(androidContext(), get()) }

    // FileDataSourceImpl binds multiple interfaces
    single { FileDataSourceImpl(get(), get(), get()) } binds arrayOf(
        WorkspaceConfigSource::class,
        MarkdownStorageDataSource::class,
        MediaStorageDataSource::class
    )

    singleOf(::DefaultWorkspaceMediaAccess) bind WorkspaceMediaAccess::class
}
