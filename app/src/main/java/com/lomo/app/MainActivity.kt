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
                com.lomo.ui.util.ProvideAppHapticFeedback(enabled = appPreferences.hapticFeedbackEnabled) {
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
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE
            -> handleShareIntent(intent)

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

    private fun handleShareIntent(intent: Intent) {
        val type = intent.type.orEmpty()
        if (type.startsWith("text/")) {
            extractSharedTexts(intent).forEach(viewModel::handleSharedText)
            return
        }
        if (type.startsWith("image/")) {
            extractSharedImageUris(intent).forEach(viewModel::handleSharedImage)
            return
        }
        // Fallback for mixed or missing MIME type.
        extractSharedTexts(intent).forEach(viewModel::handleSharedText)
        extractSharedImageUris(intent).forEach(viewModel::handleSharedImage)
    }

    private fun extractSharedTexts(intent: Intent): List<String> {
        val texts = mutableListOf<String>()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            if (text.isNotBlank()) {
                texts += text
            }
        }
        @Suppress("DEPRECATION")
        intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach { text ->
            if (text.isNotBlank()) {
                texts += text
            }
        }
        @Suppress("DEPRECATION")
        intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach { text ->
            val normalized = text?.toString()
            if (!normalized.isNullOrBlank()) {
                texts += normalized
            }
        }
        return texts.distinct()
    }

    private fun extractSharedImageUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        androidx.core.content.IntentCompat
            .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let(uris::add)
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(uris::add)
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        return uris.distinct()
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServiceManager.stopServices()
    }
}
