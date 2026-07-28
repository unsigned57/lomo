package com.lomo.data.di

import com.lomo.data.repository.DefaultWorkspaceMediaAccess
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

val storageDataSourceModule = module {
    single { FileStorageBackendResolver(androidContext(), get()) }
    // Bind workspace config separately from markdown/media writers. DirectorySettings (used by
    // ManagedEngineSession) only needs WorkspaceConfigSource; routing it through FileDataSourceImpl
    // also constructed FileMarkdown/Media delegates, which require WorkspaceMutationLease, which
    // requires EngineReadinessRepository (= ManagedEngineSession) — a Koin creation cycle that
    // StackOverflowError'd on cold start.
    single {
        FileWorkspaceConfigSourceDelegate(androidContext(), get(), get())
    } bind WorkspaceConfigSource::class
    single {
        FileMarkdownStorageDataSourceDelegate(get(), get())
    } bind MarkdownStorageDataSource::class
    single {
        FileMediaStorageDataSourceDelegate(androidContext(), get(), get())
    } bind MediaStorageDataSource::class

    // Optional aggregate for call sites that still want the combined FileDataSource surface.
    single {
        FileDataSourceImpl(
            workspaceConfigSource = get<FileWorkspaceConfigSourceDelegate>(),
            markdownStorageDataSource = get<FileMarkdownStorageDataSourceDelegate>(),
            mediaStorageDataSource = get<FileMediaStorageDataSourceDelegate>(),
        )
    }

    singleOf(::DefaultWorkspaceMediaAccess) bind WorkspaceMediaAccess::class
}
