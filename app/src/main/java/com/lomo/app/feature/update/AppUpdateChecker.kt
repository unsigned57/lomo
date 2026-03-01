package com.lomo.app.feature.update

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import javax.inject.Inject

class AppUpdateChecker
    @Inject
    constructor(
        private val checkStartupAppUpdateUseCase: CheckStartupAppUpdateUseCase,
    ) {
        suspend fun checkForStartupUpdate(): AppUpdateInfo? =
            checkStartupAppUpdateUseCase()
                ?.let { info ->
                    info.copy(releaseNotes = normalizeReleaseNotesForDisplay(info.releaseNotes))
                }

        private fun normalizeReleaseNotesForDisplay(raw: String): String {
            return raw
                .replace(FORCE_UPDATE_MARKER, "")
                .replace("\r\n", "\n")
                .trim()
        }

        private companion object {
            private const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
        }
    }
