package com.lomo.app

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lomo.domain.model.SecuritySessionState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

internal fun requestAppUnlock(activity: AppCompatActivity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
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

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
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
    val showRetryButton = !isConfigLoading
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(AppSpacing.Large),
            contentAlignment = Alignment.Center,
        ) {
            // Subtle ambient M3E halo backdrop
            Box(
                modifier =
                Modifier
                    .size(320.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f)),
            )
            Surface(
                modifier =
                Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                shape = AppShapes.ExtraExtraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = AppSpacing.ExtraSmall,
            ) {
                Column(
                    modifier =
                    Modifier.padding(
                        horizontal = AppSpacing.Large,
                        vertical = AppSpacing.ExtraLarge,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                ) {
                    AppLockGateContent(
                        statusMessage = appLockStatusMessage(isConfigLoading, isUnlockInProgress, errorMessage),
                        showError = showError,
                        showLoadingIndicator = showLoadingIndicator,
                        showRetryButton = showRetryButton,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppLockGateContent(
    statusMessage: String,
    showError: Boolean,
    showLoadingIndicator: Boolean,
    showRetryButton: Boolean,
    onRetry: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        // Security badge pill
        AppLockSecurityBadge()

        // Layered M3E Biometric Hero Emblem (also tappable to trigger unlock)
        AppLockBiometricEmblem(showError, showLoadingIndicator, showRetryButton, onRetry)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        ) {
            Text(
                text = stringResource(R.string.app_lock_gate_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.app_lock_gate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Animated status banner
        AppLockStatusBanner(statusMessage, showLoadingIndicator, showError)

        if (showRetryButton) {
            LomoButton(
                onClick = {
                    haptic.medium()
                    onRetry()
                },
                enabled = !showLoadingIndicator,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
            ) {
                LomoIcon(
                    icon = LomoIcons.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                Text(
                    text = stringResource(R.string.app_lock_action_retry),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
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
): String = when {
    isConfigLoading -> stringResource(R.string.app_lock_status_loading)
    isUnlockInProgress -> stringResource(R.string.app_lock_status_unlocking)
    !errorMessage.isNullOrBlank() -> errorMessage
    else -> stringResource(R.string.app_lock_status_waiting)
}

@Composable
private fun AppLockSecurityBadge() {
    Surface(
        shape = AppShapes.Full,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.MediumSmall, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LomoIcon(
                icon = LomoIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.settings_app_lock),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AppLockBiometricEmblem(
    showError: Boolean,
    showLoadingIndicator: Boolean,
    showRetryButton: Boolean,
    onRetry: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val componentMotion = LocalLomoMotion.current.component
    val canTapEmblem = showRetryButton && !showLoadingIndicator

    val innerEmblemColor by animateColorAsState(
        targetValue =
        if (showError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "AppLockEmblemContainer",
    )
    val innerEmblemTint by animateColorAsState(
        targetValue =
        if (showError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "AppLockEmblemTint",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(vertical = AppSpacing.ExtraSmall),
    ) {
        Box(
            modifier =
            Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(innerEmblemColor.copy(alpha = 0.22f)),
        )
        Box(
            modifier =
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(innerEmblemColor.copy(alpha = 0.42f)),
        )
        Surface(
            shape = AppShapes.ExtraLargeIncreased,
            color = innerEmblemColor,
            modifier =
            Modifier
                .size(80.dp)
                .then(
                    if (canTapEmblem) {
                        Modifier.clickable(role = Role.Button) {
                            haptic.medium()
                            onRetry()
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                LomoIcon(
                    icon = LomoIcons.Fingerprint,
                    contentDescription = null,
                    tint = innerEmblemTint,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

@Composable
private fun AppLockStatusBanner(statusMessage: String, showLoadingIndicator: Boolean, showError: Boolean) {
    val layoutMotion = LocalLomoMotion.current.layout

    Surface(
        shape = AppShapes.Large,
        color =
        if (showError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = Triple(showLoadingIndicator, showError, statusMessage),
            transitionSpec = {
                fadeIn(layoutMotion.fastEffectsSpec()) togetherWith
                    fadeOut(layoutMotion.fastEffectsSpec())
            },
            label = "AppLockStatusTransition",
        ) { (loading, isError, message) ->
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.MediumSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                if (loading) {
                    ExpressiveContainedLoadingIndicator()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (isError) {
                        LomoIcon(
                            icon = LomoIcons.Error,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isError) FontWeight.Medium else FontWeight.Normal,
                        color =
                        if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
