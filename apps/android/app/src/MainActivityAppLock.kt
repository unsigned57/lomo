package com.lomo.app

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import com.lomo.domain.model.SecuritySessionState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.theme.AppSpacing

internal fun requestAppUnlock(
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
internal fun rememberAppLockUiState(
    session: SecuritySessionState,
    onRequestUnlock: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onAuthenticated: () -> Unit,
    onRefreshSession: () -> Unit,
): AppLockUiState {
    var hasRequestedAutoUnlock by remember { mutableStateOf(false) }
    var unlockPromptInProgress by remember { mutableStateOf(false) }
    var unlockErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        if (session !is SecuritySessionState.Locked) {
            hasRequestedAutoUnlock = session is SecuritySessionState.LockOff ||
                session is SecuritySessionState.Unlocked
            unlockPromptInProgress = false
            if (session !is SecuritySessionState.StorageFailure) {
                unlockErrorMessage = null
            }
        }
    }

    fun requestUnlock() {
        if (session is SecuritySessionState.StorageFailure) {
            onRefreshSession()
            return
        }
        unlockPromptInProgress = true
        unlockErrorMessage = null
        onRequestUnlock(
            {
                unlockPromptInProgress = false
                unlockErrorMessage = null
                onAuthenticated()
            },
            { message ->
                unlockPromptInProgress = false
                unlockErrorMessage = message
            },
        )
    }

    LaunchedEffect(
        session,
        hasRequestedAutoUnlock,
        unlockPromptInProgress,
    ) {
        if (
            shouldAutoRequestAppLockUnlock(
                session = session,
                hasRequestedAutoUnlock = hasRequestedAutoUnlock,
                unlockPromptInProgress = unlockPromptInProgress,
            )
        ) {
            hasRequestedAutoUnlock = true
            requestUnlock()
        }
    }

    return AppLockUiState(
        isGateVisible = resolveAppLockGateVisible(session),
        isUnlockInProgress = unlockPromptInProgress,
        isConfigLoading = resolveAppLockConfigLoading(session),
        errorMessage = unlockErrorMessage,
        requestUnlock = ::requestUnlock,
    )
}

internal data class AppLockUiState(
    val isGateVisible: Boolean,
    val isUnlockInProgress: Boolean,
    val isConfigLoading: Boolean,
    val errorMessage: String?,
    val requestUnlock: () -> Unit,
)

@Composable
internal fun AppLockGate(
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

@Composable
internal fun ApplyRecentsObscure(obscure: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(obscure) {
        val window = (view.context as android.app.Activity).window
        if (obscure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}

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
