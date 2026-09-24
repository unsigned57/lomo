package com.lomo.data.engine

import com.lomo.data.engine.lan.LanChunkSend
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanSendItemPlan

internal fun LanDiscoveryFacts.toBridge(): com.lomo.nativebridge.LanDiscoverySnapshotDto =
    com.lomo.nativebridge.LanDiscoverySnapshotDto(
        revision = revision,
        peers =
            peers.map { peer ->
                com.lomo.nativebridge.LanDiscoveredPeerDto(
                    deviceId = peer.deviceId,
                    displayName = peer.displayName,
                    host = peer.host,
                    port = peer.port,
                    protocolVersion = peer.protocolVersion,
                )
            },
    )

internal fun LanChunkSend.toBridge(): com.lomo.nativebridge.LanChunkSendDto =
    com.lomo.nativebridge.LanChunkSendDto(
        sessionId = sessionId,
        batchId = batchId,
        itemIndex = itemIndex,
        attachmentSlot = attachmentSlot,
        chunkIndex = chunkIndex,
        plaintext = plaintext,
    )

internal fun LanSendItemPlan.toBridge(): com.lomo.nativebridge.LanSendItemDto =
    com.lomo.nativebridge.LanSendItemDto(
        timestampMs = timestampMs,
        contentDigest = contentDigest,
        contentBytes = contentBytes,
        title = title,
        attachments =
            attachments.map { attachment ->
                com.lomo.nativebridge.LanAttachmentDto(
                    slot = attachment.slot,
                    sourceReference = attachment.sourceReference,
                    name = attachment.name,
                    digest = attachment.digest,
                    sizeBytes = attachment.sizeBytes,
                )
            },
    )
