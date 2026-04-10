package com.lomo.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val SharedExpressiveMotionScheme: MotionScheme = MotionScheme.expressive()

@Composable
fun rememberExpressiveMotionScheme(): MotionScheme = remember { SharedExpressiveMotionScheme }

@Composable
fun PrewarmExpressiveMotion() {
    rememberExpressiveMotionScheme()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProvideExpressiveMotion(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = rememberExpressiveMotionScheme(),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
