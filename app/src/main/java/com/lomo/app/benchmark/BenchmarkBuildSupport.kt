package com.lomo.app.benchmark

import com.lomo.app.BuildConfig

object BenchmarkBuildSupport {
    fun isEnabledBuildType(
        buildType: String,
        isDebug: Boolean,
    ): Boolean =
        isDebug ||
            buildType == "benchmark" ||
            buildType == "nonMinifiedRelease"
}

internal fun areBenchmarkFeaturesEnabled(): Boolean =
    BenchmarkBuildSupport.isEnabledBuildType(
        buildType = BuildConfig.BUILD_TYPE,
        isDebug = BuildConfig.DEBUG,
    )
