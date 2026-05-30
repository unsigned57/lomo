package com.lomo.app.di

import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.usecase.GetMemosByTagPageUseCase
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.SearchMemosPageUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainSearchBindingsModule {
    @Provides
    @Singleton
    fun provideMainMemoListQueryUseCase(
        mainListQueryRepository: MainListQueryRepository,
        memoListQueryRepository: MemoListQueryRepository,
    ): MainMemoListQueryUseCase =
        MainMemoListQueryUseCase(
            mainListQueryRepository = mainListQueryRepository,
            memoListQueryRepository = memoListQueryRepository,
        )

    @Provides
    @Singleton
    fun provideSearchMemosPageUseCase(
        mainListQueryRepository: MainListQueryRepository,
    ): SearchMemosPageUseCase = SearchMemosPageUseCase(mainListQueryRepository)

    @Provides
    @Singleton
    fun provideGetMemosByTagPageUseCase(
        memoSearchRepository: MemoSearchRepository,
    ): GetMemosByTagPageUseCase = GetMemosByTagPageUseCase(memoSearchRepository)
}
