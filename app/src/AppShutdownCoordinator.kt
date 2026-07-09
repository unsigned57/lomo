package com.lomo.app

import android.content.ComponentCallbacks2
import com.lomo.domain.repository.AppBackgroundWorkRepository
import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository


class AppShutdownCoordinator(
    private val appUpdateTransportLifecycleRepository: AppUpdateTransportLifecycleRepository,
    private val appBackgroundWorkRepository: AppBackgroundWorkRepository,
) {
        fun closeAppResources(onCloseFailure: (Throwable) -> Unit = {}) {
            closeUpdateTransport(onCloseFailure)
            cancelAppBackgroundWork(onCloseFailure)
        }

        fun closeForTrimMemory(
            level: Int,
            onCloseFailure: (Throwable) -> Unit = {},
        ) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                closeUpdateTransport(onCloseFailure)
            }
        }

        fun closeForLowMemory(onCloseFailure: (Throwable) -> Unit = {}) {
            closeUpdateTransport(onCloseFailure)
        }

        private fun closeUpdateTransport(onCloseFailure: (Throwable) -> Unit) {
            runCatching {
                appUpdateTransportLifecycleRepository.closeUpdateTransport()
            }.onFailure(onCloseFailure)
        }

        private fun cancelAppBackgroundWork(onCloseFailure: (Throwable) -> Unit) {
            runCatching {
                appBackgroundWorkRepository.cancelAppBackgroundWork()
            }.onFailure(onCloseFailure)
        }
    }
