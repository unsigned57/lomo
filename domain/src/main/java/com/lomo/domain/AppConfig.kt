package com.lomo.domain

object AppConfig {
    const val MAX_MEMO_LENGTH = 100000
    const val PAGE_SIZE = 20

    // Paging optimization settings
    const val PREFETCH_DISTANCE = PAGE_SIZE / 2 // Load next page when 10 items away
    const val INITIAL_LOAD_SIZE = PAGE_SIZE * 2 // Load 40 items initially for smoother start
}
