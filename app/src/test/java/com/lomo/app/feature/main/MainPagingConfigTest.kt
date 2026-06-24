package com.lomo.app.feature.main

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/*
 * Behavior Contract:
 * - Unit under test: MainMemoListStateHolder, SearchViewModel, and MemoPagingSource Paging configurations.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Ensure that page loading configurations align with recommended Paging 3 parameters (initialLoadSize = 60).
 *
 * Scenarios:
 * - Given homepage memo paging data holder, when page is loaded, then the initial load size is 60 and page size is 20.
 * - Given search viewModel, when page is loaded, then the initial load size is 60 and page size is 20.
 * - Given memoPager default constants, when instantiated, then the default initial load size is 60 and page size is 20.
 *
 * Observable outcomes:
 * - Reflection-extracted constant values.
 *
 * TDD proof:
 * - Fails initially because initialLoadSize is 20 in MainMemoListStateHolder and SearchViewModel, and 40 in MemoPagingSource.
 *
 * Excludes:
 * - Compose layouts, Android database, and networking elements.
 */
class MainPagingConfigTest : FunSpec({
    test("given MainMemoListStateHolder when constants are read then initial load size is 60 and placeholders are disabled") {
        val clazz = Class.forName("com.lomo.app.feature.main.MainMemoListStateHolderKt")
        val field = clazz.getDeclaredField("DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe false
    }

    test("given SearchViewModel when constants are read then initial load size is 60 and placeholders are disabled") {
        val clazz = Class.forName("com.lomo.app.feature.search.SearchViewModelKt")
        val field = clazz.getDeclaredField("SEARCH_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("SEARCH_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe false
    }

    test("given MemoPagingSource when constants are read then initial load size is 60 and placeholders are disabled") {
        val clazz = Class.forName("com.lomo.app.feature.common.MemoPagingSourceKt")
        val field = clazz.getDeclaredField("DEFAULT_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("DEFAULT_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe false
    }
})
