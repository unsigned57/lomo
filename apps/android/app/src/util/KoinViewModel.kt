package com.lomo.app.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
internal inline fun <reified VM : ViewModel> injectedKoinViewModel(): VM = koinViewModel()

@Composable
internal inline fun <reified VM : ViewModel> activityKoinViewModel(): VM {
    val activity =
        LocalContext.current.findComponentActivity()
            ?: error("activityKoinViewModel requires a ComponentActivity context")
    return koinViewModel(viewModelStoreOwner = activity)
}

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
