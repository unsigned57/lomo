package com.lomo.ui.benchmark

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@Immutable
data class BenchmarkAnchorConfig(
    val enabled: Boolean,
)

val LocalBenchmarkAnchorConfig =
    staticCompositionLocalOf {
        BenchmarkAnchorConfig(enabled = false)
    }

fun Modifier.benchmarkAnchor(tag: String?): Modifier =
    composed {
        val config = LocalBenchmarkAnchorConfig.current
        if (!config.enabled || tag.isNullOrBlank()) {
            this
        } else {
            this.testTag(tag)
        }
    }

fun Modifier.benchmarkAnchorRoot(tag: String? = null): Modifier =
    composed {
        val config = LocalBenchmarkAnchorConfig.current
        if (!config.enabled) {
            this
        } else {
            val anchored =
                this.semantics {
                    testTagsAsResourceId = true
                }
            if (tag.isNullOrBlank()) {
                anchored
            } else {
                anchored.testTag(tag)
            }
        }
    }
