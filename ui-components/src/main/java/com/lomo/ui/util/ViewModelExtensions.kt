package com.lomo.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/*
 * Legacy compatibility shim.
 *
 * ViewModel and screen-state flow policies belong to app/feature layers.
 * This file remains in ui-components to avoid breaking downstream imports.
 *
 * These extensions keep the common pattern of:
 * ```kotlin
 * .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue)
 * ```
 *
 * Usage in ViewModels:
 * ```kotlin
 * val uiState: StateFlow<UiState> = someFlow
 *     .stateInViewModel(initialValue = UiState.Loading)
 * ```
 */

/**
 * Default timeout for WhileSubscribed (5 seconds).
 * Allows configuration to survive configuration changes.
 */
private const val WHILE_SUBSCRIBED_TIMEOUT_MS = 5000L

/**
 * Convert a Flow to a StateFlow with standard ViewModel lifecycle semantics.
 * Uses WhileSubscribed(5000) for optimal resource management during config changes.
 *
 * @param scope The CoroutineScope (typically viewModelScope)
 * @param initialValue The initial value before the flow emits
 * @return A StateFlow that respects lifecycle
 */
@Deprecated(
    message = "Legacy compatibility shim in ui-components. Prefer app-layer Flow.stateIn(...) usage.",
    replaceWith =
        ReplaceWith(
            expression = "this.stateIn(scope, SharingStarted.WhileSubscribed(5000L), initialValue)",
            imports = ["kotlinx.coroutines.flow.SharingStarted", "kotlinx.coroutines.flow.stateIn"],
        ),
    level = DeprecationLevel.WARNING,
)
fun <T> Flow<T>.stateInViewModel(
    scope: CoroutineScope,
    initialValue: T,
): StateFlow<T> =
    stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_TIMEOUT_MS),
        initialValue = initialValue,
    )

/**
 * Convert a Flow to a StateFlow using a lazy initial value.
 * Useful when the initial value is expensive to compute.
 *
 * @param scope The CoroutineScope (typically viewModelScope)
 * @param initialValue Lazy provider for the initial value
 * @return A StateFlow that respects lifecycle
 */
@Deprecated(
    message = "Legacy compatibility shim in ui-components. Prefer app-layer Flow.stateIn(...) usage.",
    replaceWith =
        ReplaceWith(
            expression = "this.stateIn(scope, SharingStarted.WhileSubscribed(5000L), initialValue())",
            imports = ["kotlinx.coroutines.flow.SharingStarted", "kotlinx.coroutines.flow.stateIn"],
        ),
    level = DeprecationLevel.WARNING,
)
fun <T> Flow<T>.stateInViewModelLazy(
    scope: CoroutineScope,
    initialValue: () -> T,
): StateFlow<T> =
    stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_TIMEOUT_MS),
        initialValue = initialValue(),
    )

/**
 * Convert a Flow to a StateFlow that stays active while the app is in foreground.
 * Uses Eagerly started - the Flow starts immediately and stays hot.
 *
 * @param scope The CoroutineScope
 * @param initialValue The initial value
 * @return A StateFlow that starts eagerly
 */
@Deprecated(
    message = "Legacy compatibility shim in ui-components. Prefer app-layer Flow.stateIn(...) usage.",
    replaceWith =
        ReplaceWith(
            expression = "this.stateIn(scope, SharingStarted.Eagerly, initialValue)",
            imports = ["kotlinx.coroutines.flow.SharingStarted", "kotlinx.coroutines.flow.stateIn"],
        ),
    level = DeprecationLevel.WARNING,
)
fun <T> Flow<T>.stateInEagerly(
    scope: CoroutineScope,
    initialValue: T,
): StateFlow<T> =
    stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = initialValue,
    )
