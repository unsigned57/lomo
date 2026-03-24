package com.lomo.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Immutable
interface AppHapticFeedback {
    fun light()

    fun medium()

    fun heavy()

    fun longPress()

    fun error()
}

private val NoOpAppHapticFeedback =
    object : AppHapticFeedback {
        override fun light() = Unit

        override fun medium() = Unit

        override fun heavy() = Unit

        override fun longPress() = Unit

        override fun error() = Unit
    }

val LocalAppHapticFeedback =
    compositionLocalOf<AppHapticFeedback> { NoOpAppHapticFeedback }

@Composable
fun ProvideAppHapticFeedback(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val haptic =
        remember(view, enabled) {
            if (enabled) {
                object : AppHapticFeedback {
                    override fun light() {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    override fun medium() {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }

                    override fun heavy() {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }

                    override fun longPress() {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }

                    override fun error() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                        } else {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                }
            } else {
                NoOpAppHapticFeedback
            }
        }

    CompositionLocalProvider(LocalAppHapticFeedback provides haptic) {
        content()
    }
}
