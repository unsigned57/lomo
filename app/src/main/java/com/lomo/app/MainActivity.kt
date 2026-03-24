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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.theme.applyAppNightMode
import com.lomo.app.util.LocalShareUtils
import com.lomo.app.util.ShareUtils
import com.lomo.domain.repository.LanShareService
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LomoTheme
import com.lomo.ui.theme.MotionTokens
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var audioPlayerController: AudioPlayerController

    @Inject lateinit var shareUtils: ShareUtils

    @Inject lateinit var shareServiceManager: LanShareService

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition(::shouldKeepSplashScreenVisible)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setMainContent()
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
            Intent.ACTION_SEND_MULTIPLE,
            -> {
                handleShareIntent(intent)
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

    private fun handleShareIntent(intent: Intent) {
        val type = intent.type.orEmpty()
        if (type.startsWith("text/")) {
            extractSharedTexts(intent).forEach(viewModel.handleSharedText)
            return
        }
        if (type.startsWith("image/")) {
            extractSharedImageUris(intent).forEach(viewModel.handleSharedImage)
            return
        }
        // Fallback for mixed or missing MIME type.
        extractSharedTexts(intent).forEach(viewModel.handleSharedText)
        extractSharedImageUris(intent).forEach(viewModel.handleSharedImage)
    }

    private fun extractSharedTexts(intent: Intent): List<String> {
        val texts = mutableListOf<String>()
        val extras = intent.extras
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            if (text.isNotBlank()) {
                texts += text
            }
        }
        extras?.getStringArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
            if (text.isNotBlank()) {
                texts += text
            }
        }
        extras?.getCharSequenceArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
            val normalized = text?.toString()
            if (!normalized.isNullOrBlank()) {
                texts += normalized
            }
        }
        return texts.distinct()
    }

    private fun extractSharedImageUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        IntentCompat
            .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let(uris::add)
        IntentCompat
            .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.forEach(uris::add)
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
                                BiometricPrompt.ERROR_CANCELED,
                                -> getString(R.string.app_lock_error_canceled)

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
        return SUPPORTED_AUTHENTICATOR_OPTIONS.firstOrNull { authenticators ->
            biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServiceManager.stopServices()
    }

    private fun shouldKeepSplashScreenVisible(): Boolean =
        viewModel.uiState.value is MainViewModel.MainScreenState.Loading || viewModel.appLockEnabled.value == null

    private fun setMainContent() {
        setContent {
            MainActivityScreen(
                viewModel = viewModel,
                audioPlayerController = audioPlayerController,
                shareUtils = shareUtils,
                shareServiceManager = shareServiceManager,
                onThemeModeChanged = { applyAppNightMode(this@MainActivity, it) },
                onRequestUnlock = ::requestAppUnlock,
            )
        }
    }

    companion object {
        const val ACTION_NEW_MEMO = "com.lomo.app.ACTION_NEW_MEMO"
        const val ACTION_OPEN_MEMO = "com.lomo.app.ACTION_OPEN_MEMO"
        const val EXTRA_MEMO_ID = "memo_id"
    }
}

@Composable
private fun MainActivityScreen(
    viewModel: MainViewModel,
    audioPlayerController: AudioPlayerController,
    shareUtils: ShareUtils,
    shareServiceManager: LanShareService,
    onThemeModeChanged: (com.lomo.domain.model.ThemeMode) -> Unit,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
) {
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockUiState =
        rememberAppLockUiState(
            appLockEnabled = appLockEnabled,
            onRequestUnlock = onRequestUnlock,
        )

    LaunchedEffect(appPreferences.themeMode) {
        onThemeModeChanged(appPreferences.themeMode)
    }

    MainActivityRoot(
        appPreferences = appPreferences,
        appLockEnabled = appLockEnabled,
        appLockUiState = appLockUiState,
        viewModel = viewModel,
        audioPlayerController = audioPlayerController,
        shareUtils = shareUtils,
        shareServiceManager = shareServiceManager,
    )
}

@Composable
private fun rememberAppLockUiState(
    appLockEnabled: Boolean?,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
): AppLockUiState {
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
        onRequestUnlock(
            {
                hasUnlockedThisLaunch = true
                unlockPromptInProgress = false
                unlockErrorMessage = null
            },
            { message ->
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
        if (
            shouldAutoRequestUnlock(
                appLockEnabled = appLockEnabled,
                hasUnlockedThisLaunch = hasUnlockedThisLaunch,
                hasRequestedAutoUnlock = hasRequestedAutoUnlock,
                unlockPromptInProgress = unlockPromptInProgress,
            )
        ) {
            hasRequestedAutoUnlock = true
            requestUnlock()
        }
    }

    return AppLockUiState(
        isGateVisible = appLockEnabled == true && !hasUnlockedThisLaunch,
        isUnlockInProgress = unlockPromptInProgress,
        errorMessage = unlockErrorMessage,
        requestUnlock = ::requestUnlock,
    )
}

@Composable
private fun MainActivityRoot(
    appPreferences: AppPreferencesState,
    appLockEnabled: Boolean?,
    appLockUiState: AppLockUiState,
    viewModel: MainViewModel,
    audioPlayerController: AudioPlayerController,
    shareUtils: ShareUtils,
    shareServiceManager: LanShareService,
) {
    LomoTheme(themeMode = appPreferences.themeMode.value) {
        androidx.activity.compose.ReportDrawnWhen { !appLockUiState.isGateVisible }
        com.lomo.ui.util.ProvideAppHapticFeedback(enabled = appPreferences.hapticFeedbackEnabled) {
            AnimatedContent(
                targetState = appLockUiState.isGateVisible,
                label = "AppLockGateTransition",
                transitionSpec = { MotionTokens.enterContent togetherWith MotionTokens.exitContent },
            ) { isLockGateVisible ->
                if (isLockGateVisible) {
                    AppLockGate(
                        isConfigLoading = false,
                        isUnlockInProgress = appLockUiState.isUnlockInProgress,
                        errorMessage = appLockUiState.errorMessage,
                        onRetry = {
                            if (appLockEnabled == true && !appLockUiState.isUnlockInProgress) {
                                appLockUiState.requestUnlock()
                            }
                        },
                    )
                } else {
                    UnlockedAppRoot(
                        viewModel = viewModel,
                        audioPlayerController = audioPlayerController,
                        shareUtils = shareUtils,
                        shareServiceManager = shareServiceManager,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockedAppRoot(
    viewModel: MainViewModel,
    audioPlayerController: AudioPlayerController,
    shareUtils: ShareUtils,
    shareServiceManager: LanShareService,
) {
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

@Composable
private fun AppLockGate(
    isConfigLoading: Boolean,
    isUnlockInProgress: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    val showError = !errorMessage.isNullOrBlank() && !isConfigLoading && !isUnlockInProgress
    val showLoadingIndicator = isConfigLoading || isUnlockInProgress
    val showRetryButton = !showLoadingIndicator
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Large),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = AppSpacing.ExtraSmall,
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.Large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                ) {
                    AppLockGateContent(
                        statusMessage = appLockStatusMessage(isConfigLoading, isUnlockInProgress, errorMessage),
                        statusColor =
                            if (showError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        showLoadingIndicator = showLoadingIndicator,
                        showRetryButton = showRetryButton,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppLockGateContent(
    statusMessage: String,
    statusColor: androidx.compose.ui.graphics.Color,
    showLoadingIndicator: Boolean,
    showRetryButton: Boolean,
    onRetry: () -> Unit,
) {
    Text(
        text = stringResource(R.string.app_lock_gate_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.app_lock_gate_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    if (showLoadingIndicator) {
        ExpressiveContainedLoadingIndicator()
    }

    Text(
        text = statusMessage,
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
        textAlign = TextAlign.Center,
    )

    if (showRetryButton) {
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(R.string.app_lock_action_retry),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private data class AppLockUiState(
    val isGateVisible: Boolean,
    val isUnlockInProgress: Boolean,
    val errorMessage: String?,
    val requestUnlock: () -> Unit,
)

private fun shouldAutoRequestUnlock(
    appLockEnabled: Boolean?,
    hasUnlockedThisLaunch: Boolean,
    hasRequestedAutoUnlock: Boolean,
    unlockPromptInProgress: Boolean,
): Boolean = appLockEnabled == true &&
    !hasUnlockedThisLaunch &&
    !hasRequestedAutoUnlock &&
    !unlockPromptInProgress

private val SUPPORTED_AUTHENTICATOR_OPTIONS =
    listOf(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.BIOMETRIC_WEAK,
    )

@Composable
private fun appLockStatusMessage(
    isConfigLoading: Boolean,
    isUnlockInProgress: Boolean,
    errorMessage: String?,
): String =
    when {
        isConfigLoading -> stringResource(R.string.app_lock_status_loading)
        isUnlockInProgress -> stringResource(R.string.app_lock_status_unlocking)
        !errorMessage.isNullOrBlank() -> errorMessage
        else -> stringResource(R.string.app_lock_status_waiting)
    }
