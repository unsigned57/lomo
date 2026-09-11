package com.lomo.app.testing.fakes

import com.lomo.domain.repository.AppRuntimeInfoRepository

class FakeAppRuntimeInfoRepository(
    var currentVersionName: String = "1.0.0",
    var currentVersionCode: Long? = 1L,
) : AppRuntimeInfoRepository {
    override suspend fun getCurrentVersionName(): String = currentVersionName

    override suspend fun getCurrentVersionCode(): Long? = currentVersionCode
}
