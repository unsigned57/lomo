package com.lomo.app.feature.main

import com.lomo.ui.component.common.HeadEnterBaseline

internal fun HeadEnterBaseline.isResolvedByHeadId(headId: String): Boolean =
    when (this) {
        HeadEnterBaseline.EmptyList -> true
        is HeadEnterBaseline.ExistingHead -> id != headId
    }
