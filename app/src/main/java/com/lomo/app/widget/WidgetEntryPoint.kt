package com.lomo.app.widget

import com.lomo.data.local.dao.MemoDao
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
    fun memoDao(): MemoDao

    fun textProcessor(): com.lomo.data.util.MemoTextProcessor
}
