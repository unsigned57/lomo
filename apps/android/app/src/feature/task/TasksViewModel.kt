package com.lomo.app.feature.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.MemoTask
import com.lomo.domain.usecase.ListMemoTasksUseCase
import com.lomo.domain.usecase.ToggleMemoTaskUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TasksScreenState {
    data object Loading : TasksScreenState

    data class Ready(
        val tasks: List<MemoTask>,
    ) : TasksScreenState

    data class Error(
        val message: String,
    ) : TasksScreenState
}

class TasksViewModel(
    private val listMemoTasksUseCase: ListMemoTasksUseCase,
    private val toggleMemoTaskUseCase: ToggleMemoTaskUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TasksScreenState>(TasksScreenState.Loading)
    val uiState: StateFlow<TasksScreenState> = _uiState.asStateFlow()
    private val hasLoaded = MutableStateFlow(false)

    fun refresh() {
        loadTasks(force = true)
    }

    fun ensureLoaded() {
        if (hasLoaded.value) {
            return
        }
        hasLoaded.value = true
        loadTasks(force = false)
    }

    fun toggleTask(task: MemoTask) {
        viewModelScope.launch {
            try {
                toggleMemoTaskUseCase(task = task, done = !task.done)
                _uiState.value = TasksScreenState.Ready(listMemoTasksUseCase())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = TasksScreenState.Error(error.toUserMessage("Failed to update task"))
            }
        }
    }

    private fun loadTasks(force: Boolean) {
        if (!force && _uiState.value is TasksScreenState.Ready) {
            return
        }
        viewModelScope.launch {
            _uiState.value = TasksScreenState.Loading
            try {
                _uiState.value = TasksScreenState.Ready(listMemoTasksUseCase())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = TasksScreenState.Error(error.toUserMessage("Failed to load tasks"))
            }
        }
    }
}
