package com.lomo.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val SharedExpressiveMotionScheme: MotionScheme = MotionScheme.expressive()

@Composable
fun rememberExpressiveMotionScheme(): MotionScheme = remember { SharedExpressiveMotionScheme }
