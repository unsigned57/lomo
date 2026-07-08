/*
 * Behavior Contract:
 * - Unit under test: sync-related repository/runtime constructors that currently expose silent fallback collaborators.
 * - Behavior focus: incremental-sync-critical constructors must require explicit collaborator wiring instead of Kotlin default-argument overloads that silently downgrade to NoOp/disabled implementations.
 * - Observable outcomes: target classes expose no synthetic default-argument constructor backed by DefaultConstructorMarker.
 * - TDD proof: Fails before the fix because MediaRepositoryImpl, S3SyncExecutor, S3SyncRepositoryContext, and MemoMutationRuntime still publish Kotlin default-argument constructors.
 * - Excludes: DI code generation, S3/WebDAV transport behavior, and mutation/sync business logic.
 */
package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse

class NoOpDefaultConstructorContractTest : DataFunSpec() {
    init {
        test("incremental sync critical constructors do not expose default argument overloads") { `incremental sync critical constructors do not expose default argument overloads`() }
    }


    private fun `incremental sync critical constructors do not expose default argument overloads`() {
        listOf(
            MediaRepositoryImpl::class.java,
            S3SyncExecutor::class.java,
            S3SyncRepositoryContext::class.java,
            MemoMutationRuntime::class.java,
        ).forEach { targetClass ->
            withClue("${targetClass.simpleName} should require explicit collaborators") { (targetClass.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }).shouldBeFalse() }
        }
    }
}
