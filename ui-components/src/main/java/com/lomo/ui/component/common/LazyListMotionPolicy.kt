package com.lomo.ui.component.common

enum class LazyListItemEntranceState {
    Active,
    Settled,
}

enum class LazyListItemPlacementMode {
    Disabled,
    Spring,
}

data class LazyListItemMotionPolicy(
    val usesLazyItemFadeIn: Boolean,
    val usesPlacementSpring: Boolean,
)

fun resolveLazyListItemMotionPolicy(
    entranceState: LazyListItemEntranceState,
    placementMode: LazyListItemPlacementMode,
    structureMotionActive: Boolean,
): LazyListItemMotionPolicy =
    LazyListItemMotionPolicy(
        usesLazyItemFadeIn =
            entranceState == LazyListItemEntranceState.Active &&
                !structureMotionActive,
        usesPlacementSpring =
            placementMode == LazyListItemPlacementMode.Spring &&
                !structureMotionActive,
    )
