package com.lomo.app.feature.common

sealed class UiState<out T> {
    data object Idle : UiState<Nothing>()

    data object Loading : UiState<Nothing>()

    data class Success<T>(
        val data: T,
    ) : UiState<T>()

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : UiState<Nothing>()

    val isLoading: Boolean get() = this is Loading

    val isSuccess: Boolean get() = this is Success

    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): String? = (this as? Error)?.message

    fun <R> map(transform: (T) -> R): UiState<R> =
        when (this) {
            is Idle -> Idle
            is Loading -> Loading
            is Success -> Success(transform(data))
            is Error -> Error(message, throwable)
        }
}
