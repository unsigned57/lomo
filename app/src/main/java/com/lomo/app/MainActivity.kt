package com.lomo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.util.ProvideHapticFeedback
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.theme.LomoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var audioPlayerManager: com.lomo.ui.media.AudioPlayerManager

    @Inject lateinit var shareServiceManager: LanShareService

    private val viewModel: MainViewModel by viewModels()

    companion object {
        const val ACTION_NEW_MEMO = "com.lomo.app.ACTION_NEW_MEMO"
        const val ACTION_OPEN_MEMO = "com.lomo.app.ACTION_OPEN_MEMO"
        const val EXTRA_MEMO_ID = "memo_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is MainViewModel.MainScreenState.Loading
        }

        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
            LomoTheme(themeMode = appPreferences.themeMode.value) {
                ProvideHapticFeedback(appPreferences.hapticFeedbackEnabled) { hapticEnabled ->
                    com.lomo.ui.util.ProvideAppHapticFeedback(enabled = hapticEnabled) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.lomo.ui.media.LocalAudioPlayerManager provides audioPlayerManager,
                        ) {
                            LomoAppRoot(
                                viewModel = viewModel,
                                shareServiceManager = shareServiceManager,
                            )
                        }
                    }
                }
            }
        }

        // Network service bootstrap is not required for first frame rendering.
        window.decorView.post {
            shareServiceManager.startServices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        viewModel.handleSharedText(text)
                    }
                } else if (intent.type?.startsWith("image/") == true) {
                    androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                        viewModel.handleSharedImage(uri)
                    } ?: run {
                        intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                            viewModel.handleSharedImage(uri)
                        }
                    }
                }
            }

            ACTION_NEW_MEMO -> {
                viewModel.requestCreateMemo()
            }

            ACTION_OPEN_MEMO -> {
                val memoId = intent.getStringExtra(EXTRA_MEMO_ID)
                if (!memoId.isNullOrBlank()) {
                    viewModel.requestOpenMemo(memoId)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServiceManager.stopServices()
    }
}
