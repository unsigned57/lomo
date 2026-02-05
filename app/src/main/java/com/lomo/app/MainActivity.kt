package com.lomo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.navigation.LomoNavHost
import com.lomo.app.util.ProvideHapticFeedback
import com.lomo.ui.theme.LomoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var audioPlayerManager: com.lomo.ui.media.AudioPlayerManager

    @Inject lateinit var dataStore: com.lomo.data.local.datastore.LomoDataStore

    private val viewModel: MainViewModel by viewModels()

    companion object {
        const val ACTION_NEW_MEMO = "com.lomo.app.ACTION_NEW_MEMO"
        const val ACTION_OPEN_MEMO = "com.lomo.app.ACTION_OPEN_MEMO"
        const val EXTRA_MEMO_ID = "memo_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep the splash screen on-screen until the UI state is loaded
        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is MainViewModel.MainScreenState.Loading
        }

        enableEdgeToEdge()
        
        handleIntent(intent)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LomoTheme(themeMode = themeMode) {
                ProvideHapticFeedback(dataStore) { hapticEnabled ->
                    com.lomo.ui.util.ProvideAppHapticFeedback(enabled = hapticEnabled) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.lomo.ui.media.LocalAudioPlayerManager provides audioPlayerManager,
                        ) {
                            LomoApp(
                                viewModel = viewModel,
                                initialAction = intent?.action,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            if (intent.type?.startsWith("text/") == true) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    viewModel.handleSharedText(text)
                }
            } else if (intent.type?.startsWith("image/") == true) {
                // Try EXTRA_STREAM first
                // Use IntentCompat for backward compatibility
                androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                    viewModel.handleSharedImage(uri)
                } ?: run {
                    // Fallback to ClipData
                    intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                         viewModel.handleSharedImage(uri)
                    }
                }
            }
        }
    }
}

@Composable
private fun LomoApp(
    viewModel: MainViewModel,
    initialAction: String? = null,
) {
    // Handle widget actions
    LaunchedEffect(initialAction) {
        when (initialAction) {
            MainActivity.ACTION_NEW_MEMO -> {
                // TODO: Trigger new memo creation via navigation or event
            }

            MainActivity.ACTION_OPEN_MEMO -> {
                // TODO: Navigate to memo detail/edit
            }
        }
    }

    val updateUrl by viewModel.updateUrl.collectAsStateWithLifecycle()
    if (updateUrl != null) {
        val context = LocalContext.current
        val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            title = {
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.update_dialog_title),
                )
            },
            text = {
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.update_dialog_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.medium()
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl))
                        context.startActivity(intent)
                        viewModel.dismissUpdateDialog()
                    },
                ) {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.lomo.app.R.string.action_download),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        haptic.medium()
                        viewModel.dismissUpdateDialog()
                    },
                ) {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.lomo.app.R.string.action_cancel),
                    )
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val navController = rememberNavController()
        LomoNavHost(
            navController = navController,
            viewModel = viewModel,
        )
    }
}
