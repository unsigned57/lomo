package com.lomo.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.StoredCredentialStatus

@Composable
internal fun credentialStatusSubtitle(
    status: StoredCredentialStatus,
    @StringRes configuredResId: Int,
    @StringRes missingResId: Int,
): String =
    stringResource(
        when (status) {
            StoredCredentialStatus.Present -> configuredResId
            StoredCredentialStatus.Missing -> missingResId
            StoredCredentialStatus.Unreadable -> R.string.settings_credential_unreadable
            StoredCredentialStatus.Invalid -> R.string.settings_credential_invalid
        },
    )
