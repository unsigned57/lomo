package com.lomo.app.testing

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * Keeps [kotlinx.coroutines.flow.SharingStarted.WhileSubscribed] screen flows hot for `.value`
 * assertions.
 *
 * Compose collectors do the same; without a subscriber the shared flow stays at `stateIn`'s
 * initial value and the test reads a silent default instead of the screen state machine.
 */
fun TestScope.collectWhileSubscribed(vararg flows: StateFlow<*>) {
    flows.forEach { flow ->
        backgroundScope.launch { flow.collect() }
    }
}
