package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first

class CheckStartupAppUpdateUseCase
(
        private val preferencesRepository: PreferencesRepository,
        private val appUpdateRepository: AppUpdateRepository,
        private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ) {
        suspend operator fun invoke(): AppUpdateInfo? {
            val latestRelease =
                if (preferencesRepository.isCheckUpdatesOnStartupEnabled().first()) {
                    appUpdateRepository.fetchLatestRelease()
                } else {
                    null
                }

            return latestRelease?.let { release ->
                val remoteVersion = release.tagName.removePrefix("v")
                val localVersion = appRuntimeInfoRepository.getCurrentVersionName().substringBefore("-")
                val forceUpdate = release.body.contains(FORCE_UPDATE_MARKER)
                val updateAvailable = forceUpdate || isRemoteVersionNewer(localVersion, remoteVersion)
                if (updateAvailable) {
                    AppUpdateInfo(
                        url = release.htmlUrl,
                        version = remoteVersion,
                        releaseNotes = release.body,
                    )
                } else {
                    null
                }
            }
        }

        private fun isRemoteVersionNewer(
            local: String,
            remote: String,
        ): Boolean {
            return try {
                compareVersionParts(
                    local = local,
                    remote = remote,
                ) > 0
            } catch (_: Exception) {
                remote != local
            }
        }

        private fun compareVersionParts(
            local: String,
            remote: String,
        ): Int {
            val localParts = local.split('.').map { it.toIntOrNull() ?: 0 }
            val remoteParts = remote.split('.').map { it.toIntOrNull() ?: 0 }
            val length = maxOf(localParts.size, remoteParts.size)
            var comparison = 0
            for (index in 0 until length) {
                val localPart = localParts.getOrElse(index) { 0 }
                val remotePart = remoteParts.getOrElse(index) { 0 }
                comparison = remotePart.compareTo(localPart)
                if (comparison != 0) {
                    break
                }
            }
            return comparison
        }

        private companion object {
            const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
        }
    }
