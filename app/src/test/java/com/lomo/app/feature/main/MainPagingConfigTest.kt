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
 * - Capability: Ensure memo Paging configurations use the shared initial load size and placeholder-backed index space.
 *
 * Scenarios:
 * - Given homepage memo paging data holder, when constants are read, then the initial load size is 60 and placeholders are enabled.
 * - Given search viewModel, when constants are read, then the initial load size is 60 and placeholders are enabled.
 * - Given memoPager defaults, when constants are read, then the default initial load size is 60 and placeholders are enabled.
 *
 * Observable outcomes:
 * - Reflection-extracted constant values.
 *
 * TDD proof:
 * - Fails before the placeholder fix because memo paging paths disabled placeholders and each UI path maintained its own index space.
 *
 * Excludes:
 * - Compose layouts, Android database, and networking elements.
 *
 * Test Change Justification:
 * - Reason category: behavior contract change.
 * - Old behavior/assertion being replaced: placeholder constants were asserted false for main, search, and default memo paging.
 * - Why old assertion is no longer correct: placeholders are now the canonical owner of deep paging absolute indexes.
 * - Coverage preserved by: the same configuration tests assert the new shared initial-load and placeholder defaults.
 * - Why this is not fitting the test to the implementation: the test locks the intended paging contract, not a private branch or incidental call sequence.
 */
class MainPagingConfigTest : FunSpec({
    test("given MainMemoListStateHolder when constants are read then initial load size is 60 and placeholders are enabled") {
        val clazz = Class.forName("com.lomo.app.feature.main.MainMemoListStateHolderKt")
        val field = clazz.getDeclaredField("DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe true
    }

    test("given SearchViewModel when constants are read then initial load size is 60 and placeholders are enabled") {
        val clazz = Class.forName("com.lomo.app.feature.search.SearchViewModelKt")
        val field = clazz.getDeclaredField("SEARCH_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("SEARCH_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe true
    }

    test("given MemoPagingSource when constants are read then initial load size is 60 and placeholders are enabled") {
        val clazz = Class.forName("com.lomo.app.feature.common.MemoPagingSourceKt")
        val field = clazz.getDeclaredField("DEFAULT_INITIAL_LOAD_SIZE")
        field.isAccessible = true
        val initialLoadSize = field.get(null) as Int
        initialLoadSize shouldBe 60

        val placeholderField = clazz.getDeclaredField("DEFAULT_ENABLE_PLACEHOLDERS")
        placeholderField.isAccessible = true
        val enablePlaceholders = placeholderField.get(null) as Boolean
        enablePlaceholders shouldBe true
    }
})
