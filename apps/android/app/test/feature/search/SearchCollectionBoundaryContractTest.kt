package com.lomo.app.feature.search

import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SearchCollectionBoundaryContractTest : AppFunSpec() {
    init {
        test(
            "search depends on the common collection projection port",
        ) {
            val viewModelParameterTypes =
                SearchViewModel::class.java.declaredConstructors
                    .flatMap { constructor -> constructor.parameterTypes.map(Class<*>::getName) }
            val collaboratorParameterTypes =
                SearchViewModelDependencies::class.java.declaredConstructors
                    .flatMap { constructor -> constructor.parameterTypes.map(Class<*>::getName) }

            (viewModelParameterTypes + collaboratorParameterTypes)
                .filter { typeName -> typeName == MAIN_MEMO_UI_MAPPER }
                .shouldBeEmpty()
            collaboratorParameterTypes.contains(MemoCollectionProjectionMapper::class.java.name) shouldBe true
        }
    }

    private companion object {
        private const val MAIN_MEMO_UI_MAPPER = "com.lomo.app.feature.main.MemoUiMapper"
    }
}
