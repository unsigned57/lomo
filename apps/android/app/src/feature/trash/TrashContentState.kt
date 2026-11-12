package com.lomo.app.feature.trash

import com.lomo.ui.component.common.LomoListExitPhase

/** Hidden retention protects rollback; it must not delay the empty state after source removal. */
internal fun trashIsEmpty(sourceItemCount: Int, exitPhases: List<LomoListExitPhase?>): Boolean =
    sourceItemCount == 0 && exitPhases.all { it == LomoListExitPhase.Hidden }
