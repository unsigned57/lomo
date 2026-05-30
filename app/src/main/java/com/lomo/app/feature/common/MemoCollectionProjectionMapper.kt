package com.lomo.app.feature.common

import com.lomo.app.feature.main.MemoUiMapper
import javax.inject.Inject

class MemoCollectionProjectionMapper
    @Inject
    constructor(
        internal val memoUiMapper: MemoUiMapper,
    )
