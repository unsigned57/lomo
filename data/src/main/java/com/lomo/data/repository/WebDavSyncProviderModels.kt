package com.lomo.data.repository

import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncOutcome
import com.lomo.domain.model.WebDavSyncReason

data class LocalWebDavFile(
    val path: String,
    val lastModified: Long,
    val size: Long? = null,
    val localFingerprint: String? = null,
)

data class RemoteWebDavFile(
    val path: String,
    val etag: String?,
    val lastModified: Long?,
    val size: Long? = null,
)

data class WebDavSyncAction(
    val path: String,
    val direction: WebDavSyncDirection,
    val reason: WebDavSyncReason,
)

data class WebDavSyncPlan(
    val actions: List<WebDavSyncAction>,
    val pendingChanges: Int,
)

internal fun WebDavSyncAction.toOutcome(): WebDavSyncOutcome =
    WebDavSyncOutcome(
        path = path,
        direction = direction,
        reason = reason,
    )
