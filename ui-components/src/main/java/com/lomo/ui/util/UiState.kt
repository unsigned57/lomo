package com.lomo.ui.util

/**
 * Unified UI state wrapper for consistent state management across ViewModels.
 * Replaces scattered loading/error/success state implementations.
 */
sealed class UiState<out T> {
    /** Initial idle state before any operation */
    data object Idle : UiState<Nothing>()

    /** Loading state while operation is in progress */
    data object Loading : UiState<Nothing>()

    /** Success state with data payload */
    data class Success<T>(
        val data: T,
    ) : UiState<T>()

    /** Error state with message and optional throwable */
    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : UiState<Nothing>()

    /** Check if current state is loading */
    val isLoading: Boolean get() = this is Loading

    /** Check if current state is success */
    val isSuccess: Boolean get() = this is Success

    /** Check if current state is error */
    val isError: Boolean get() = this is Error

    /** Get data if success, null otherwise */
    fun getOrNull(): T? = (this as? Success)?.data

    /** Get error message if error, null otherwise */
    fun errorOrNull(): String? = (this as? Error)?.message

    /** Map success data to another type */
    fun <R> map(transform: (T) -> R): UiState<R> =
        when (this) {
            is Idle -> Idle
            is Loading -> Loading
            is Success -> Success(transform(data))
            is Error -> Error(message, throwable)
        }
}
