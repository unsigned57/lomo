package com.lomo.data.di

import com.lomo.domain.repository.AppBackgroundWorkRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(owner: ApplicationBackgroundWorkOwner): CoroutineScope = owner.scope

    @Provides
    @Singleton
    fun provideAppBackgroundWorkRepository(owner: ApplicationBackgroundWorkOwner): AppBackgroundWorkRepository = owner
}

@Singleton
class ApplicationBackgroundWorkOwner
    @Inject
    constructor() : AppBackgroundWorkRepository {
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override fun cancelAppBackgroundWork() {
            scope.cancel()
        }
    }
