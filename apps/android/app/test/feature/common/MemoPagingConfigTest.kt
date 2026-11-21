package com.lomo.app.feature.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoPagingConfigTest :
    FunSpec({
        test("default cursor paging bounds its initial load and preserves placeholder indexes with jumping disabled") {
            val config = memoPagingConfig()

            config.initialLoadSize shouldBe 60
            config.enablePlaceholders shouldBe true
            config.jumpThreshold shouldBe Int.MIN_VALUE
        }
    })
