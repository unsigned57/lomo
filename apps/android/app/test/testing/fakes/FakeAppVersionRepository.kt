package com.lomo.app.testing.fakes

import com.lomo.domain.repository.AppVersionRepository

class FakeAppVersionRepository : AppVersionRepository {
    var lastAppVersion: String? = ""
    var updateLastAppVersionCalled = false

    override suspend fun getLastAppVersionOnce(): String? = lastAppVersion

    override suspend fun updateLastAppVersion(version: String?) {
        lastAppVersion = version
        updateLastAppVersionCalled = true
    }
}
