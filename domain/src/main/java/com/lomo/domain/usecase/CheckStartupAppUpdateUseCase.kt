package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first

class CheckStartupAppUpdateUseCase
    constructor(
        private val preferencesRepository: PreferencesRepository,
        private val appUpdateRepository: AppUpdateRepository,
        private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ) {
        suspend operator fun invoke(): AppUpdateInfo? {
            if (!preferencesRepository.isCheckUpdatesOnStartupEnabled().first()) {
                return null
            }

            val latestRelease = appUpdateRepository.fetchLatestRelease() ?: return null
            val remoteVersion = latestRelease.tagName.removePrefix("v")
            val localVersion = appRuntimeInfoRepository.getCurrentVersionName().substringBefore("-")
            val forceUpdate = latestRelease.body.contains(FORCE_UPDATE_MARKER)
            val updateAvailable = forceUpdate || isRemoteVersionNewer(localVersion, remoteVersion)
            if (!updateAvailable) {
                return null
            }

            return AppUpdateInfo(
                url = latestRelease.htmlUrl,
                version = remoteVersion,
                releaseNotes = latestRelease.body,
            )
        }

        private fun isRemoteVersionNewer(
            local: String,
            remote: String,
        ): Boolean {
            return try {
                val localParts = local.split('.').map { it.toIntOrNull() ?: 0 }
                val remoteParts = remote.split('.').map { it.toIntOrNull() ?: 0 }
                val length = maxOf(localParts.size, remoteParts.size)
                for (index in 0 until length) {
                    val localPart = localParts.getOrElse(index) { 0 }
                    val remotePart = remoteParts.getOrElse(index) { 0 }
                    if (remotePart > localPart) return true
                    if (remotePart < localPart) return false
                }
                false
            } catch (_: Exception) {
                remote != local
            }
        }

        private companion object {
            const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
        }
    }
