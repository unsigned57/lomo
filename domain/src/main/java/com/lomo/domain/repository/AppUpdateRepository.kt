package com.lomo.domain.repository

import com.lomo.domain.model.LatestAppRelease

interface AppUpdateRepository {
    suspend fun fetchLatestRelease(): LatestAppRelease?
}
