package com.lomo.ui.component.menu

import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

fun sortActionItemUiByPreferredKeys(
    actions: ImmutableList<ActionItemUi>,
    preferredKeys: List<String>,
): ImmutableList<ActionItemUi> {
    validateUniqueStableActionKeys(actions)
    val rankedKeys = preferredKeys.map(String::trim).filter(String::isNotEmpty).distinct()
    if (rankedKeys.isEmpty()) {
        return actions
    }
    val actionByKey = actions.associateBy(ActionItemUi::key)
    return buildList {
        rankedKeys.forEach { actionKey ->
            actionByKey[actionKey]?.let(::add)
        }
        addAll(actions.filterNot { action -> action.key != null && action.key in rankedKeys })
    }.toImmutableList()
}

internal fun performActionItemHaptic(
    haptic: AppHapticFeedback,
    type: ActionItemHaptic,
) {
    when (type) {
        ActionItemHaptic.NONE -> Unit
        ActionItemHaptic.MEDIUM -> haptic.medium()
        ActionItemHaptic.HEAVY -> haptic.heavy()
    }
}
