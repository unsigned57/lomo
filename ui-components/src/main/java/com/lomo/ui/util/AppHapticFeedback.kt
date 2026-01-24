package com.lomo.ui.util

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

val LocalAppHapticFeedback =
    compositionLocalOf<AppHapticFeedback> {
        object : AppHapticFeedback {
            override fun light() {}

            override fun medium() {}

            override fun heavy() {}

            override fun longPress() {}

            override fun error() {}
        }
    }

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
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                }
            } else {
                object : AppHapticFeedback {
                    override fun light() {}

                    override fun medium() {}

                    override fun heavy() {}

                    override fun longPress() {}

                    override fun error() {}
                }
            }
        }

    CompositionLocalProvider(LocalAppHapticFeedback provides haptic) {
        content()
    }
}
