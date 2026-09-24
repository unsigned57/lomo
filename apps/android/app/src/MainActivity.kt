package com.lomo.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.obscuresRecents
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.ui.benchmark.BenchmarkAnchorConfig
import com.lomo.ui.benchmark.LocalBenchmarkAnchorConfig
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import com.lomo.ui.theme.LomoTheme
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.theme.TypographyScales
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val audioPlayerController: AudioPlayerController by inject()
    private val shareServiceManager: LanShareService by inject()
    private val securitySessionController: SecuritySessionController by inject()
    private val securitySessionPolicy: SecuritySessionPolicy by inject()
    private val trustedLaunchIntents: TrustedLaunchIntents by inject()
    private val externalAppCommandStore: ExternalAppCommandStore by inject()

    private val viewModel: MainViewModel by viewModel()
    private var currentUiMode by mutableIntStateOf(Configuration.UI_MODE_NIGHT_UNDEFINED)
    private val pendingLaunchCommandQueue = PendingLaunchCommandQueue()
    private val shareServicesStarted = AtomicBoolean(false)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_LAUNCH_COMMANDS, pendingLaunchCommandQueue.snapshotJson())
    }

    private fun restorePendingLaunchCommands(savedInstanceState: Bundle?) {
        pendingLaunchCommandQueue.restore(
            savedInstanceState?.getString(KEY_PENDING_LAUNCH_COMMANDS),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        currentUiMode = resources.configuration.uiMode

        splashScreen.setKeepOnScreenCondition(::shouldKeepSplashScreenVisible)
        enableEdgeToEdge()
        // The Activity is the explicit engine start requester: Application.onCreate never opens
        // native, and tile/widget wakes resolve without mounting the workspace.
        viewModel.requestEngineStart()
        restorePendingLaunchCommands(savedInstanceState)
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
        consumeTrustedLaunchExtraction(
            trustedLaunchIntents.extractTrustedExternalAppCommand(intent),
            externalAppCommandStore::enqueue,
        )
        extractPendingLaunchActions(intent = intent).forEach(pendingLaunchCommandQueue::enqueue)
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
            consumeTrustedLaunchExtraction(
                trustedLaunchIntents.extractTrustedExternalAppCommand(intent),
                externalAppCommandStore::enqueue,
            )
        }
        extractInitialPendingLaunchActions(
            activityInstanceState = activityInstanceState,
            intent = intent,
        ).forEach(pendingLaunchCommandQueue::enqueue)
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
                onAuthenticated = securitySessionController::recordAuthenticated,
                onRefreshSession = {
                    lifecycleScope.launch {
                        securitySessionController.refresh()
                    }
                },
                securitySessionPolicy = securitySessionPolicy,
                pendingLaunchCommands = pendingLaunchCommandQueue.commands,
                onPendingLaunchCommandConsumed = pendingLaunchCommandQueue::consume,
            )
        }
    }

    companion object {
        const val ACTION_EXTERNAL_APP_COMMAND = "com.lomo.app.ACTION_EXTERNAL_APP_COMMAND"
        const val ACTION_OPEN_MEMO = "com.lomo.app.ACTION_OPEN_MEMO"
        const val EXTRA_MEMO_ID = "memo_id"
        private const val KEY_PENDING_LAUNCH_COMMANDS = "lomo.pendingLaunchCommands"
    }
}

@Composable
private fun MainActivityScreen(
    audioPlayerController: AudioPlayerController,
    shareServiceManager: LanShareService,
    currentUiMode: Int,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onAuthenticated: () -> Unit,
    onRefreshSession: () -> Unit,
    securitySessionPolicy: SecuritySessionPolicy,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandConsumed: (Long) -> Unit,
    viewModel: MainViewModel = injectedKoinViewModel(),
) {
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val session by securitySessionPolicy.observe().collectAsStateWithLifecycle()
    val appLockUiState =
        rememberAppLockUiState(
            session = session,
            onRequestUnlock = onRequestUnlock,
            onAuthenticated = onAuthenticated,
            onRefreshSession = onRefreshSession,
        )
    val foregroundEntryId = rememberActivityForegroundEntryId()
    ApplyRecentsObscure(obscure = session.obscuresRecents())

    MainActivityRoot(
        appPreferences = appPreferences,
        session = session,
        appLockUiState = appLockUiState,
        foregroundEntryId = foregroundEntryId,
        pendingLaunchCommands = pendingLaunchCommands,
        onPendingLaunchCommandConsumed = onPendingLaunchCommandConsumed,
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
private fun MainActivityRoot(
    appPreferences: AppPreferencesState,
    session: SecuritySessionState,
    appLockUiState: AppLockUiState,
    foregroundEntryId: Long,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandConsumed: (Long) -> Unit,
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
    val storageFailureMessage =
        if (session is SecuritySessionState.StorageFailure) {
            stringResource(R.string.app_lock_error_preference_unreadable)
        } else {
            null
        }
    LomoTheme(
        themeMode = appPreferences.themeMode.value,
        colorSource = appPreferences.colorSource,
        fontFamily = appPreferences.customFontFamily,
        typographyScales = typographyScales,
        currentUiMode = currentUiMode,
    ) {
        androidx.activity.compose.ReportDrawnWhen { !appLockUiState.isGateVisible }
        com.lomo.ui.util.ProvideAppHapticFeedback(enabled = appPreferences.hapticFeedbackEnabled) {
            DispatchPendingLaunchCommands(
                session = session,
                pendingLaunchCommands = pendingLaunchCommands,
                onPendingLaunchCommandConsumed = onPendingLaunchCommandConsumed,
            )
            AnimatedContent(
                targetState = appLockUiState.isGateVisible,
                label = "AppLockGateTransition",
                transitionSpec = { MotionTokens.enterContent togetherWith MotionTokens.exitContent },
            ) { isLockGateVisible ->
                if (isLockGateVisible) {
                    AppLockGate(
                        isConfigLoading = appLockUiState.isConfigLoading,
                        isUnlockInProgress = appLockUiState.isUnlockInProgress,
                        errorMessage = storageFailureMessage ?: appLockUiState.errorMessage,
                        onRetry = {
                            if (!appLockUiState.isUnlockInProgress && !appLockUiState.isConfigLoading) {
                                appLockUiState.requestUnlock()
                            }
                        },
                    )
                } else {
                    UnlockedAppRoot(
                        foregroundEntryId = foregroundEntryId,
                        pendingLaunchCommands = pendingLaunchCommands,
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
    audioPlayerController: AudioPlayerController,
    shareServiceManager: LanShareService,
) {
    val context = LocalContext.current
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
    session: SecuritySessionState,
    pendingLaunchCommands: ImmutableList<PendingLaunchCommand>,
    onPendingLaunchCommandConsumed: (Long) -> Unit,
    viewModel: MainViewModel = activityKoinViewModel(),
) {
    if (pendingLaunchCommands.isEmpty()) {
        return
    }
    val mount by viewModel.mount.collectAsStateWithLifecycle()
    val (workspaceState, _) = entryWorkspaceStateFor(mount)
    val appLock = entryAppLockStateFor(session)
    // Dispatch binds the security session, the mount epoch and the command id: an epoch change
    // re-resolves every remaining command against the new workspace, and each command is
    // acknowledged immediately after its own dispatch so a cancelled pass never re-fires it.
    val mountEpoch = mount.location?.raw
    LaunchedEffect(pendingLaunchCommands, workspaceState, appLock, mountEpoch) {
        val readiness =
            EntryFlowReadiness(
                appLock = appLock,
                configuredCapabilities = EntryCapability.entries.toSet(),
                workspace = workspaceState,
            )
        pendingLaunchCommands.forEach { command ->
            val dispatched =
                dispatchPendingLaunchCommand(
                    command = command,
                    readiness = readiness,
                ) { action ->
                    when (action) {
                        is PendingLaunchAction.SharedText -> viewModel.handleSharedText(action.text)
                        is PendingLaunchAction.SharedImage -> viewModel.handleSharedImage(action.uri)
                        is PendingLaunchAction.OpenMemo -> viewModel.requestOpenMemo(action.memoId)
                    }
                }
            if (dispatched) {
                onPendingLaunchCommandConsumed(command.id)
            }
        }
    }
}
