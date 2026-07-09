package com.lomo.app.feature.share

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private const val TRANSFER_BANNER_RESIZE_DURATION_MILLIS = 180
private val TRANSFER_BANNER_ICON_SIZE = 20.dp
private val TRANSFER_BANNER_LOADING_SIZE = 18.dp
private val TRANSFER_BANNER_PROGRESS_HEIGHT = 24.dp
private const val TRANSFER_BANNER_PROGRESS_ALPHA = 0.2f

private data class TransferBannerState(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector?,
    val text: String,
)

@Composable
fun TransferStateBanner(
    state: ShareTransferState,
    isTechnicalMessage: (String) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bannerState = transferBannerState(state, isTechnicalMessage) ?: return

    Surface(
        modifier = modifier,
        color = bannerState.containerColor,
        shape = AppShapes.Medium,
        onClick = {
            if (state is ShareTransferState.Success || state is ShareTransferState.Error) {
                onDismiss()
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(TRANSFER_BANNER_RESIZE_DURATION_MILLIS))
                    .padding(AppSpacing.Medium),
        ) {
            TransferStateBannerHeader(bannerState = bannerState)
            if (state is ShareTransferState.Transferring) {
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                ExpressiveContainedLoadingIndicator(
                    progress = { state.progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(TRANSFER_BANNER_PROGRESS_HEIGHT),
                    indicatorColor = bannerState.contentColor,
                    containerColor = bannerState.contentColor.copy(alpha = TRANSFER_BANNER_PROGRESS_ALPHA),
                    shape = AppShapes.ExtraSmall,
                )
            }
        }
    }
}

@Composable
private fun transferBannerState(
    state: ShareTransferState,
    isTechnicalMessage: (String) -> Boolean,
): TransferBannerState? =
    when (state) {
        is ShareTransferState.Sending ->
            TransferBannerState(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = null,
                text = stringResource(R.string.share_status_connecting),
            )
        is ShareTransferState.WaitingApproval ->
            TransferBannerState(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = null,
                text = stringResource(R.string.share_status_waiting_approval, state.deviceName),
            )
        is ShareTransferState.Transferring ->
            TransferBannerState(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = null,
                text = stringResource(R.string.share_status_transferring),
            )
        is ShareTransferState.Success ->
            TransferBannerState(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = Icons.Filled.CheckCircle,
                text = stringResource(R.string.share_status_sent, state.deviceName),
            )
        is ShareTransferState.Error ->
            TransferBannerState(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Filled.Error,
                text = ShareErrorPresenter.message(state.error, isTechnicalMessage),
            )
        else -> null
    }

@Composable
private fun TransferStateBannerHeader(bannerState: TransferBannerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (bannerState.icon != null) {
            Icon(
                imageVector = bannerState.icon,
                contentDescription = null,
                tint = bannerState.contentColor,
                modifier = Modifier.size(TRANSFER_BANNER_ICON_SIZE),
            )
        } else {
            ExpressiveLoadingIndicator(
                modifier = Modifier.size(TRANSFER_BANNER_LOADING_SIZE),
                color = bannerState.contentColor,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = bannerState.text,
            style = MaterialTheme.typography.bodyMedium,
            color = bannerState.contentColor,
        )
    }
}
