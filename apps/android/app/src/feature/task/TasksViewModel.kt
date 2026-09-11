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
            runCatching {
                toggleMemoTaskUseCase(task = task, done = !task.done)
                listMemoTasksUseCase()
            }.onSuccess { tasks ->
                _uiState.value = TasksScreenState.Ready(tasks)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.value = TasksScreenState.Error(throwable.toUserMessage("Failed to update task"))
            }
        }
    }

    private fun loadTasks(force: Boolean) {
        if (!force && _uiState.value is TasksScreenState.Ready) {
            return
        }
        viewModelScope.launch {
            _uiState.value = TasksScreenState.Loading
            runCatching {
                listMemoTasksUseCase()
            }.onSuccess { tasks ->
                _uiState.value = TasksScreenState.Ready(tasks)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.value = TasksScreenState.Error(throwable.toUserMessage("Failed to load tasks"))
            }
        }
    }
}
