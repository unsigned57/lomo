package com.lomo.data.git

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.model.CredentialSecretReadResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: GitCredentialStrategy
 * - Owning layer: data/git credential boundary adapter
 * - Priority tier: P0
 * - Capability: convert unified Git credential read results into typed Git credential outcomes without manufacturing blank or anonymous provider state.
 *
 * Scenarios:
 * - Given the Git token is missing, when credential providers are requested, then PAT_REQUIRED is thrown as a typed credential failure.
 * - Given the Git token is unreadable, when credential providers are requested, then CREDENTIAL_UNREADABLE is thrown.
 * - Given credential reads are unauthorized, when credential providers are requested, then CREDENTIAL_UNAUTHORIZED is thrown.
 *
 * Observable outcomes:
 * - GitSyncFailureException codes at the strategy boundary.
 *
 * TDD proof:
 * - RED: the missing-token scenario returns null before the fix, collapsing Missing into blank/no provider state.
 *
 * Excludes:
 * - JGit network authentication, repository workflow result mapping, settings UI rendering.
 */
class GitCredentialStrategyTest : DataFunSpec() {
    init {
        test("given git token is missing when providers are requested then missing is a typed credential failure") {
            runTest {
                val strategy = gitCredentialStrategy(CredentialSecretReadResult.Missing)

                val error =
                    shouldThrow<GitSyncFailureException> {
                        strategy.credentialProviders()
                    }

                error.code shouldBe GitSyncErrorCode.PAT_REQUIRED
            }
        }

        test("given git token is unreadable when providers are requested then unreadable is a typed credential failure") {
            runTest {
                val strategy = gitCredentialStrategy(CredentialSecretReadResult.Unreadable)

                val error =
                    shouldThrow<GitSyncFailureException> {
                        strategy.credentialProviders()
                    }

                error.code shouldBe GitSyncErrorCode.CREDENTIAL_UNREADABLE
            }
        }

        test("given git credential read is unauthorized when providers are requested then unauthorized is a typed credential failure") {
            runTest {
                val strategy =
                    gitCredentialStrategy(
                        CredentialSecretReadResult.Unauthorized(
                            CredentialReadDenialReason.SecuritySessionLocked,
                        ),
                    )

                val error =
                    shouldThrow<GitSyncFailureException> {
                        strategy.credentialProviders()
                    }

                error.code shouldBe GitSyncErrorCode.CREDENTIAL_UNAUTHORIZED
            }
        }
    }
}
