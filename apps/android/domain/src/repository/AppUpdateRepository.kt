package com.lomo.domain.repository

import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.model.LatestAppRelease

interface AppUpdateRepository {
    /**
     * Fetches the latest published release.
     *
     * @throws AppUpdateFetchException when the check cannot produce a release — HTTP rejection,
     *   transport failure or malformed payload. Callers must map this into their own result type;
     *   a failure is never reported as "no update".
     */
    suspend fun fetchLatestRelease(): LatestAppRelease
}
