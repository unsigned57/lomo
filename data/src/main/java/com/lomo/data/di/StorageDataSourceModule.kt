package com.lomo.data.di

import android.content.Context
import com.lomo.data.repository.DefaultWorkspaceMediaAccess
import com.lomo.data.repository.MemoVersionBlobRoot
import com.lomo.data.repository.WorkspaceMediaAccess
import com.lomo.data.source.FileDataSourceImpl
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.WorkspaceConfigSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageDataSourceModule {
    @Provides
    @Singleton
    @MemoVersionBlobRoot
    fun provideMemoVersionBlobRoot(
        @ApplicationContext context: Context,
    ): File = File(context.filesDir, "memo-versions/blobs")

    @Provides
    @Singleton
    fun provideWorkspaceConfigSource(
        dataSource: FileDataSourceImpl,
    ): WorkspaceConfigSource = dataSource

    @Provides
    @Singleton
    fun provideMarkdownStorageDataSource(
        dataSource: FileDataSourceImpl,
    ): MarkdownStorageDataSource = dataSource

    @Provides
    @Singleton
    fun provideMediaStorageDataSource(
        dataSource: FileDataSourceImpl,
    ): MediaStorageDataSource = dataSource
}

@Module
@InstallIn(SingletonComponent::class)
object WorkspaceMediaAccessModule {
    @Provides
    @Singleton
    fun provideWorkspaceMediaAccess(
        access: DefaultWorkspaceMediaAccess,
    ): WorkspaceMediaAccess = access
}
