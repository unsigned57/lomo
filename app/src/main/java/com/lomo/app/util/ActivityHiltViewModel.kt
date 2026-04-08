package com.lomo.app.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel

@Composable
internal inline fun <reified VM : ViewModel> injectedHiltViewModel(): VM = hiltViewModel()

@Composable
internal inline fun <reified VM : ViewModel> activityHiltViewModel(): VM {
    val activity =
        LocalContext.current.findComponentActivity()
            ?: error("activityHiltViewModel requires a ComponentActivity context")
    return hiltViewModel(activity)
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
