package com.lomo.app.feature.update

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase
import javax.inject.Inject

class AppUpdateChecker
    @Inject
    constructor(
        private val checkAppUpdateUseCase: CheckAppUpdateUseCase,
        private val checkStartupAppUpdateUseCase: CheckStartupAppUpdateUseCase,
        private val getLatestAppReleaseUseCase: GetLatestAppReleaseUseCase,
    ) {
        suspend fun checkForStartupUpdate(): AppUpdateInfo? =
            checkStartupAppUpdateUseCase()?.normalizeForDisplay()

        suspend fun checkForManualUpdate(): AppUpdateInfo? =
            checkAppUpdateUseCase()?.normalizeForDisplay()

        suspend fun getLatestReleaseForDebugPreview(): AppUpdateInfo? =
            getLatestAppReleaseUseCase()?.normalizeForDisplay()

        private fun AppUpdateInfo.normalizeForDisplay(): AppUpdateInfo =
            copy(releaseNotes = normalizeReleaseNotesForDisplay(releaseNotes))

        private fun normalizeReleaseNotesForDisplay(raw: String): String =
            raw
                .replace(FORCE_UPDATE_MARKER, "")
                .replace("\r\n", "\n")
                .trim()

        private companion object {
            private const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
        }
    }
