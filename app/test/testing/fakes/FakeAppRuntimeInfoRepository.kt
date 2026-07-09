package com.lomo.app.testing.fakes

import com.lomo.domain.repository.AppRuntimeInfoRepository

class FakeAppRuntimeInfoRepository(
    var currentVersionName: String = "1.6.2",
    var currentVersionCode: Long? = 46L,
) : AppRuntimeInfoRepository {
    override suspend fun getCurrentVersionName(): String = currentVersionName

    override suspend fun getCurrentVersionCode(): Long? = currentVersionCode
}
