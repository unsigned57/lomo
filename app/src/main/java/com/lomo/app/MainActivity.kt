package com.lomo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.util.LocalShareUtils
import com.lomo.app.util.ShareUtils
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AudioPlaybackController
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.media.LocalAudioPlayerManager
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LomoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var audioPlayerController: AudioPlaybackController

    @Inject lateinit var shareUtils: ShareUtils

    @Inject lateinit var shareServiceManager: LanShareService

    @Inject lateinit var appConfigRepository: AppConfigRepository

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
            val appLockEnabled by
                appConfigRepository
                    .isAppLockEnabled()
                    .map<Boolean, Boolean?> { it }
                    .collectAsStateWithLifecycle(initialValue = null)

            var hasUnlockedThisLaunch by remember { mutableStateOf(false) }
            var hasRequestedAutoUnlock by remember { mutableStateOf(false) }
            var unlockPromptInProgress by remember { mutableStateOf(false) }
            var unlockErrorMessage by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(appLockEnabled) {
                if (appLockEnabled == false) {
                    hasUnlockedThisLaunch = true
                    hasRequestedAutoUnlock = true
                    unlockPromptInProgress = false
                    unlockErrorMessage = null
                }
            }

            fun requestUnlock() {
                unlockPromptInProgress = true
                unlockErrorMessage = null
                requestAppUnlock(
                    onSuccess = {
                        hasUnlockedThisLaunch = true
                        unlockPromptInProgress = false
                        unlockErrorMessage = null
                    },
                    onFailure = { message ->
                        unlockPromptInProgress = false
                        unlockErrorMessage = message
                    },
                )
            }

            LaunchedEffect(
                appLockEnabled,
                hasUnlockedThisLaunch,
                hasRequestedAutoUnlock,
                unlockPromptInProgress,
            ) {
                if (appLockEnabled == true &&
                    !hasUnlockedThisLaunch &&
                    !hasRequestedAutoUnlock &&
                    !unlockPromptInProgress
                ) {
                    hasRequestedAutoUnlock = true
                    requestUnlock()
                }
            }

            val showLockGate = appLockEnabled == null || (appLockEnabled == true && !hasUnlockedThisLaunch)

            LomoTheme(themeMode = appPreferences.themeMode.value) {
                com.lomo.ui.util.ProvideAppHapticFeedback(enabled = appPreferences.hapticFeedbackEnabled) {
                    if (showLockGate) {
                        AppLockGate(
                            isConfigLoading = appLockEnabled == null,
                            isUnlockInProgress = unlockPromptInProgress,
                            errorMessage = unlockErrorMessage,
                            onRetry = {
                                if (appLockEnabled == true && !unlockPromptInProgress) {
                                    requestUnlock()
                                }
                            },
                        )
                    } else {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalAudioPlayerManager provides audioPlayerController,
                            LocalShareUtils provides shareUtils,
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

    private fun requestAppUnlock(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val authenticators = resolveSupportedAuthenticators()
        if (authenticators == null) {
            onFailure(getString(R.string.app_lock_error_unavailable))
            return
        }

        val promptInfo =
            runCatching {
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(getString(R.string.app_lock_prompt_title))
                    .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                    .setAllowedAuthenticators(authenticators)
                    .apply {
                        if (authenticators == BiometricManager.Authenticators.BIOMETRIC_WEAK) {
                            setNegativeButtonText(getString(R.string.action_cancel))
                        }
                    }.build()
            }.getOrElse {
                onFailure(getString(R.string.app_lock_error_generic))
                return
            }

        val biometricPrompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        val message =
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED -> getString(R.string.app_lock_error_canceled)
                                else -> errString.toString().ifBlank { getString(R.string.app_lock_error_generic) }
                            }
                        onFailure(message)
                    }
                },
            )

        runCatching {
            biometricPrompt.authenticate(promptInfo)
        }.onFailure {
            onFailure(getString(R.string.app_lock_error_generic))
        }
    }

    private fun resolveSupportedAuthenticators(): Int? {
        val biometricManager = BiometricManager.from(this)
        val combined =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (biometricManager.canAuthenticate(combined) == BiometricManager.BIOMETRIC_SUCCESS) {
            return combined
        }
        if (
            biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        if (
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServiceManager.stopServices()
    }
}

@Composable
private fun AppLockGate(
    isConfigLoading: Boolean,
    isUnlockInProgress: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(AppSpacing.Large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            Text(
                text = stringResource(R.string.app_lock_gate_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.app_lock_gate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val statusMessage =
                when {
                    isConfigLoading -> stringResource(R.string.app_lock_status_loading)
                    isUnlockInProgress -> stringResource(R.string.app_lock_status_unlocking)
                    !errorMessage.isNullOrBlank() -> errorMessage
                    else -> stringResource(R.string.app_lock_status_waiting)
                }

            if (isConfigLoading || isUnlockInProgress) {
                CircularProgressIndicator()
            }

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!isConfigLoading && !isUnlockInProgress) {
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.app_lock_action_retry))
                }
            }
        }
    }
}
