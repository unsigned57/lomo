package com.lomo.ui.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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

@Composable
fun Modifier.benchmarkAnchor(tag: String?): Modifier =
    if (!LocalBenchmarkAnchorConfig.current.enabled || tag.isNullOrBlank()) {
        this
    } else {
        this.testTag(tag)
    }

@Composable
fun Modifier.benchmarkAnchorRoot(tag: String? = null): Modifier =
    if (!LocalBenchmarkAnchorConfig.current.enabled) {
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
