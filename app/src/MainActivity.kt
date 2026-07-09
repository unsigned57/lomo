package com.lomo.app

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.util.activityKoinViewModel
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.ui.benchmark.BenchmarkAnchorConfig
import com.lomo.ui.benchmark.LocalBenchmarkAnchorConfig
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LomoTheme
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.theme.TypographyScales
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val audioPlayerController: AudioPlayerController by inject()
    private val shareServiceManager: LanShareService by inject()
    private val securitySessionController: SecuritySessionController by inject()
    private val trustedLaunchIntents: TrustedLaunchIntents by inject()
    private val externalAppCommandStore: ExternalAppCommandStore by inject()

    private val viewModel: MainViewModel by viewModel()
    private var currentUiMode by mutableIntStateOf(Configuration.UI_MODE_NIGHT_UNDEFINED)
    private var nextPendingLaunchCommandId = 0L
    private var pendingLaunchCommands by
        mutableStateOf<ImmutableList<PendingLaunchCommand>>(persistentListOf())
    private val shareServicesStarted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        currentUiMode = resources.configuration.uiMode

        splashScreen.setKeepOnScreenCondition(::shouldKeepSplashScreenVisible)
        enableEdgeToEdge()
        handleInitialIntent(
            intent = intent,
            savedInstanceState = savedInstanceState,
        )
        setMainContent()
        // Network service bootstrap is not required for first frame rendering.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (shareServicesStarted.compareAndSet(false, true)) {
                    shareServiceManager.startServices()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        currentUiMode = resources.configuration.uiMode
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        currentUiMode = newConfig.uiMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        trustedLaunchIntents.extractTrustedExternalAppCommand(intent)?.let(externalAppCommandStore::enqueue)
        extractPendingLaunchActions(intent = intent).forEach(::enqueuePendingLaunchAction)
    }

    private fun handleInitialIntent(
        intent: Intent?,
        savedInstanceState: Bundle?,
    ) {
        val activityInstanceState =
            if (savedInstanceState == null) {
                ActivityInstanceState.Fresh
            } else {
                ActivityInstanceState.Restored
        }
        if (shouldProcessInitialLaunchIntent(activityInstanceState = activityInstanceState, intent = intent)) {
            trustedLaunchIntents.extractTrustedExternalAppCommand(intent)?.let(externalAppCommandStore::enqueue)
        }
        extractInitialPendingLaunchActions(
            activityInstanceState = activityInstanceState,
            intent = intent,
        ).forEach(::enqueuePendingLaunchAction)
    }

    private fun enqueuePendingLaunchAction(action: PendingLaunchAction) {
        val command =
            PendingLaunchCommand(
                id = nextPendingLaunchCommandId++,
                action = action,
            )
        pendingLaunchCommands = (pendingLaunchCommands + command).toImmutableList()
    }

    private fun consumePendingLaunchCommands(commandIds: List<Long>) {
        if (commandIds.isEmpty()) {
            return
        }
        pendingLaunchCommands = pendingLaunchCommands.filterNot { it.id in commandIds.toSet() }.toImmutableList()
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServiceManager.stopServices()
    }

    private fun shouldKeepSplashScreenVisible(): Boolean =
        viewModel.uiState.value is MainViewModel.MainScreenState.Loading

    private fun setMainContent() {
        setContent {
            MainActivityScreen(
                audioPlayerController = audioPlayerController,
                shareServiceManager = shareServiceManager,
                currentUiMode = currentUiMode,
                onRequestUnlock = { onSuccess, onFailure ->
                    requestAppUnlock(
                        activity = this@MainActivity,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                },
                onCredentialReadsAuthorized = securitySessionController::markCredentialReadsAuthorized,
                onCredentialReadsLocked = securitySessionController::markCredentialReadsLocked,
                pendingLaunchCommands = pendingLaunchCommands,
                onPendingLaunchCommandsConsumed = ::consumePendingLaunchCommands,
            )
        }
    }

    companion object {
        const val ACTION_EXTERNAL_APP_COMMAND = "com.lomo.app.ACTION_EXTERNAL_APP_COMMAND"
        const val ACTION_OPEN_MEMO = "com.lomo.app.ACTION_OPEN_MEMO"
        const val EXTRA_MEMO_ID = "memo_id"
    }
}

private fun requestAppUnlock(
    activity: AppCompatActivity,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val authenticators = resolveSupportedAuthenticators(activity)
    if (authenticators == null) {
        onFailure(activity.getString(R.string.app_lock_error_unavailable))
        return
    }

    val promptInfo =
        runCatching {
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(activity.getString(R.string.app_lock_prompt_title))
                .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(authenticators)
                .apply {
                    if (authenticators == BiometricManager.Authenticators.BIOMETRIC_WEAK) {
                        setNegativeButtonText(activity.getString(R.string.action_cancel))
                    }
                }.build()
        }.getOrElse {
            onFailure(activity.getString(R.string.app_lock_error_generic))
            return
        }

    val biometricPrompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
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
                            -> activity.getString(R.string.app_lock_error_canceled)

                            else -> errString.toString().ifBlank { activity.getString(R.string.app_lock_error_generic) }
                        }
                    onFailure(message)
                }
            },
        )

    runCatching {
        biometricPrompt.authenticate(promptInfo)
    }.onFailure {
        onFailure(activity.getString(R.string.app_lock_error_generic))
    }
}

private fun resolveSupportedAuthenticators(activity: AppCompatActivity): Int? {
    val biometricManager = BiometricManager.from(activity)
    return SUPPORTED_AUTHENTICATOR_OPTIONS.firstOrNull { authenticators ->
        biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

@Composable
private fun MainActivityScreen(
    audioPlayerController: AudioPlayerController,
    shareServiceManager: LanShareService,
    currentUiMode: Int,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onCredentialReadsAuthorized: () -> Unit,
    onCredentialReadsLocked: () -> Unit,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandsConsumed: (List<Long>) -> Unit,
    viewModel: MainViewModel = injectedKoinViewModel(),
) {
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockUiState =
        rememberAppLockUiState(
            appLockEnabled = appLockEnabled,
            onRequestUnlock = onRequestUnlock,
            onCredentialReadsAuthorized = onCredentialReadsAuthorized,
            onCredentialReadsLocked = onCredentialReadsLocked,
        )
    val foregroundEntryId = rememberActivityForegroundEntryId()

    MainActivityRoot(
        appPreferences = appPreferences,
        appLockEnabled = appLockEnabled,
        appLockUiState = appLockUiState,
        foregroundEntryId = foregroundEntryId,
        pendingLaunchCommands = pendingLaunchCommands,
        onPendingLaunchCommandsConsumed = onPendingLaunchCommandsConsumed,
        audioPlayerController = audioPlayerController,
        shareServiceManager = shareServiceManager,
        currentUiMode = currentUiMode,
    )
}

@Composable
private fun rememberActivityForegroundEntryId(): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    var foregroundEntryState by remember(lifecycleOwner) {
        mutableStateOf(ForegroundEntryPolicy.initialState(lifecycle.currentState))
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                foregroundEntryState =
                    ForegroundEntryPolicy.applyLifecycleEvent(
                        state = foregroundEntryState,
                        event = event,
                    )
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foregroundEntryState.entryId
}

@Composable
private fun rememberAppLockUiState(
    appLockEnabled: Boolean?,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onCredentialReadsAuthorized: () -> Unit,
    onCredentialReadsLocked: () -> Unit,
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
            onCredentialReadsAuthorized()
        } else if (appLockEnabled == true && !hasUnlockedThisLaunch) {
            onCredentialReadsLocked()
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
                onCredentialReadsAuthorized()
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
            shouldAutoRequestAppLockUnlock(
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
        isGateVisible = resolveAppLockGateVisible(
            appLockEnabled = appLockEnabled,
            hasUnlockedThisLaunch = hasUnlockedThisLaunch,
        ),
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
    foregroundEntryId: Long,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandsConsumed: (List<Long>) -> Unit,
    audioPlayerController: AudioPlayerController,
    shareServiceManager: LanShareService,
    currentUiMode: Int,
) {
    val typographyScales =
        TypographyScales(
            fontSizeScale = appPreferences.typographyFontSizeScale,
            lineHeightScale = appPreferences.typographyLineHeightScale,
            letterSpacingScale = appPreferences.typographyLetterSpacingScale,
            paragraphSpacingScale = appPreferences.typographyParagraphSpacingScale,
        )
    LomoTheme(
        themeMode = appPreferences.themeMode.value,
        colorSource = appPreferences.colorSource,
        customFontPath = appPreferences.customFontPath,
        typographyScales = typographyScales,
        currentUiMode = currentUiMode,
    ) {
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
                        foregroundEntryId = foregroundEntryId,
                        pendingLaunchCommands = pendingLaunchCommands,
                        onPendingLaunchCommandsConsumed = onPendingLaunchCommandsConsumed,
                        audioPlayerController = audioPlayerController,
                        shareServiceManager = shareServiceManager,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockedAppRoot(
    foregroundEntryId: Long,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandsConsumed: (List<Long>) -> Unit,
    audioPlayerController: AudioPlayerController,
    shareServiceManager: LanShareService,
) {
    val context = LocalContext.current
    DispatchPendingLaunchCommands(
        pendingLaunchCommands = pendingLaunchCommands,
        onPendingLaunchCommandsConsumed = onPendingLaunchCommandsConsumed,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAudioPlayerManager provides audioPlayerController,
        LocalBenchmarkAnchorConfig provides
            BenchmarkAnchorConfig(enabled = AppBuildInfo.isDebuggable(context)),
    ) {
        LomoAppRoot(
            shareServiceManager = shareServiceManager,
            foregroundEntryId = foregroundEntryId,
            suppressForegroundAutoInput = pendingLaunchCommands.isNotEmpty(),
        )
    }
}

@Composable
private fun DispatchPendingLaunchCommands(
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandsConsumed: (List<Long>) -> Unit,
    viewModel: MainViewModel = activityKoinViewModel(),
) {
    if (pendingLaunchCommands.isEmpty()) {
        return
    }
    LaunchedEffect(pendingLaunchCommands) {
        pendingLaunchCommands.forEach { command ->
            when (val action = command.action) {
                is PendingLaunchAction.SharedText -> viewModel.handleSharedText(action.text)
                is PendingLaunchAction.SharedImage -> viewModel.handleSharedImage(action.uri)
                is PendingLaunchAction.OpenMemo -> viewModel.requestOpenMemo(action.memoId)
            }
        }
        onPendingLaunchCommandsConsumed(pendingLaunchCommands.map(PendingLaunchCommand::id))
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
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
}

private data class AppLockUiState(
    val isGateVisible: Boolean,
    val isUnlockInProgress: Boolean,
    val errorMessage: String?,
    val requestUnlock: () -> Unit,
)

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
