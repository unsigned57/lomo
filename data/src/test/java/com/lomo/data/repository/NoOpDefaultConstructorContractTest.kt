/*
 * Test Contract:
 * - Unit under test: sync-related repository/runtime constructors that currently expose silent fallback collaborators.
 * - Behavior focus: incremental-sync-critical constructors must require explicit collaborator wiring instead of Kotlin default-argument overloads that silently downgrade to NoOp/disabled implementations.
 * - Observable outcomes: target classes expose no synthetic default-argument constructor backed by DefaultConstructorMarker.
 * - Red phase: Fails before the fix because MediaRepositoryImpl, S3SyncExecutor, S3SyncRepositoryContext, and MemoMutationRuntime still publish Kotlin default-argument constructors.
 * - Excludes: Hilt code generation, S3/WebDAV transport behavior, and mutation/sync business logic.
 */
package com.lomo.data.repository

import org.junit.Assert.assertFalse
import org.junit.Test

class NoOpDefaultConstructorContractTest {
    @Test
    fun `incremental sync critical constructors do not expose default argument overloads`() {
        listOf(
            MediaRepositoryImpl::class.java,
            S3SyncExecutor::class.java,
            S3SyncRepositoryContext::class.java,
            MemoMutationRuntime::class.java,
        ).forEach { targetClass ->
            assertFalse(
                "${targetClass.simpleName} should require explicit collaborators",
                targetClass.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                },
            )
        }
    }
}
