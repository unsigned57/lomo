package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal fun <T> Flow<T>.settingsStateIn(
    scope: CoroutineScope,
    initialValue: T,
): StateFlow<T> = stateIn(scope, settingsWhileSubscribed(), initialValue)

internal inline fun <T, R> Flow<T>.mapSettingsStateIn(
    scope: CoroutineScope,
    initialValue: R,
    crossinline transform: (T) -> R,
): StateFlow<R> = map { value -> transform(value) }.stateIn(scope, settingsWhileSubscribed(), initialValue)

internal suspend fun runSettingsOperation(
    fallbackMessage: String,
    specificError: (Throwable) -> SettingsOperationError?,
    action: suspend () -> Unit,
): SettingsOperationError? =
    runCatching {
        action()
        null
    }.getOrElse { throwable ->
        if (throwable is CancellationException) {
            throw throwable
        }
        specificError(throwable)
            ?: SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))
    }
