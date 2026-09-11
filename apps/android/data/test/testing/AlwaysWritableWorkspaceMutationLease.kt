package com.lomo.data.repository

import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.repository.WorkspaceMutationLease

/**
 * Ready, admitting lease for unit tests that exercise non-authority behavior.
 *
 * Production types must receive an explicit [WorkspaceMutationLease]; they must not silently
 * default open, so this stays a test-only construction with real drain semantics.
 */
internal fun alwaysWritableWorkspaceMutationLease(): WorkspaceMutationLease =
    ProcessWorkspaceMutationLease(engineReadinessRepository = FakeEngineReadinessRepository())
