package com.lomo.domain.model

sealed interface AppUpdateInstallState {
    data object Idle : AppUpdateInstallState

    data object Preparing : AppUpdateInstallState

    data class Downloading(
        val progress: Int,
    ) : AppUpdateInstallState

    data object Installing : AppUpdateInstallState

    data object Completed : AppUpdateInstallState

    data class RequiresInstallPermission(
        val message: String,
    ) : AppUpdateInstallState

    data class Failed(
        val message: String,
    ) : AppUpdateInstallState
}
