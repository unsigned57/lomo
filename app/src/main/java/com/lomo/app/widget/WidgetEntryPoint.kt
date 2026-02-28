package com.lomo.app.widget

import com.lomo.domain.repository.MemoRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing dependencies in Widget context.
 * Widgets run outside of Activity/Fragment lifecycle, so we need this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun memoRepository(): MemoRepository
}
