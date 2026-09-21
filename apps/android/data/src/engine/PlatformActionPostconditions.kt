package com.lomo.data.engine

import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ExpectedFingerprint
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WriteMode

/**
 * Durable postcondition arbitration for platform write and move actions.
 *
 * Split from [AndroidPlatformActionAccess] because these are pure decisions over already-observed
 * snapshots: replay returns [ActionOutcome.AlreadySatisfied] only when the independently observed
 * durable postcondition matches, and a mismatched expected fingerprint fails closed before any
 * bytes move.
 */
internal object PlatformActionPostconditions {
    fun alreadySatisfiedWrite(
        action: PlatformAction.WriteFromExchange,
        existing: PlatformDocumentSnapshot?,
    ): ActionOutcome? {
        if (existing == null) return null
        val matchesArtifact =
            existing.length == action.artifact.length && existing.digest == action.artifact.digest
        return when (val expected = action.expectedTarget) {
            is ExpectedFingerprint.Absent ->
                if (matchesArtifact) {
                    ActionOutcome.AlreadySatisfied(
                        PlatformActionOutput.WriteComplete(metadata = existing.toMetadata()),
                    )
                } else {
                    null
                }
            is ExpectedFingerprint.Match -> {
                if (existing.toEvidence() != expected.evidence) return null
                if (!matchesArtifact) return null
                ActionOutcome.AlreadySatisfied(
                    PlatformActionOutput.WriteComplete(metadata = existing.toMetadata()),
                )
            }
        }
    }

    fun assertWritePostcondition(
        action: PlatformAction.WriteFromExchange,
        existing: PlatformDocumentSnapshot?,
    ) {
        val reason =
            when (val expected = action.expectedTarget) {
                is ExpectedFingerprint.Absent ->
                    if (existing != null && action.mode == WriteMode.CREATE) {
                        "Create refused because the target already exists"
                    } else {
                        null
                    }
                is ExpectedFingerprint.Match ->
                    when {
                        existing == null -> "Expected target fingerprint but document is absent"
                        existing.toEvidence() != expected.evidence ->
                            "Target fingerprint does not match the expected postcondition"
                        else -> null
                    }
            }
        if (reason != null) {
            throw postconditionMismatch(reason)
        }
    }

    fun alreadySatisfiedMove(
        action: PlatformAction.Move,
        source: PlatformDocumentSnapshot?,
        target: PlatformDocumentSnapshot?,
    ): ActionOutcome? {
        if (source != null || target == null) return null
        return when (val expected = action.expectedTarget) {
            is ExpectedFingerprint.Absent ->
                ActionOutcome.AlreadySatisfied(
                    PlatformActionOutput.MoveComplete(metadata = target.toMetadata()),
                )
            is ExpectedFingerprint.Match ->
                if (target.toEvidence() == expected.evidence) {
                    ActionOutcome.AlreadySatisfied(
                        PlatformActionOutput.MoveComplete(metadata = target.toMetadata()),
                    )
                } else {
                    null
                }
        }
    }

    fun assertMovePrecondition(
        action: PlatformAction.Move,
        source: PlatformDocumentSnapshot?,
        target: PlatformDocumentSnapshot?,
    ) {
        val reason =
            when {
                source == null -> "Move source is absent without a satisfied target"
                action.expectedSource is ExpectedFingerprint.Match &&
                    source.toEvidence() != (action.expectedSource as ExpectedFingerprint.Match).evidence ->
                    "Move source fingerprint mismatch"
                action.expectedTarget is ExpectedFingerprint.Absent && target != null ->
                    "Move target already exists"
                action.expectedTarget is ExpectedFingerprint.Match &&
                    target != null &&
                    target.toEvidence() != (action.expectedTarget as ExpectedFingerprint.Match).evidence ->
                    "Move target fingerprint mismatch"
                else -> null
            }
        if (reason != null) {
            throw postconditionMismatch(reason)
        }
    }
}
