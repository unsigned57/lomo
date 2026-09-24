package com.lomo.app.feature.update

import com.lomo.domain.model.AppUpdateCheckOutcome
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase


class AppUpdateChecker(
    private val checkAppUpdateUseCase: CheckAppUpdateUseCase,
    private val checkStartupAppUpdateUseCase: CheckStartupAppUpdateUseCase,
    private val getLatestAppReleaseUseCase: GetLatestAppReleaseUseCase,
) {
        suspend fun checkForStartupUpdate(): AppUpdateCheckOutcome =
            checkStartupAppUpdateUseCase().normalizeForDisplay()

        suspend fun checkForManualUpdate(): AppUpdateCheckOutcome =
            checkAppUpdateUseCase().normalizeForDisplay()

        suspend fun getLatestReleaseForDebugPreview(): AppUpdateCheckOutcome =
            getLatestAppReleaseUseCase().normalizeForDisplay()

        private fun AppUpdateCheckOutcome.normalizeForDisplay(): AppUpdateCheckOutcome =
            when (this) {
                is AppUpdateCheckOutcome.Available ->
                    AppUpdateCheckOutcome.Available(
                        update.copy(releaseNotes = normalizeReleaseNotesForDisplay(update.releaseNotes)),
                    )
                AppUpdateCheckOutcome.UpToDate,
                is AppUpdateCheckOutcome.Failed,
                -> this
            }

        private fun normalizeReleaseNotesForDisplay(raw: String): String =
            raw
                .replace(FORCE_UPDATE_MARKER, "")
                .replace("\r\n", "\n")
                .trim()

        private companion object {
            private const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
        }
    }
