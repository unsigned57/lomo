package com.lomo.data.git

/*
 * Behavior Contract:
 * - Type: test fixture
 * - Capability: provide pre-configured GitCredentialStrategy instances seeded with injected
 *   CredentialSecretReadResult outcomes and an Authorized session policy.
 * - Scenarios: Given a CredentialSecretReadResult and an authorized session, when factory functions are called, then configured GitCredentialStrategy instances are returned.
 * - Observable outcomes: factory functions return configured GitCredentialStrategy instances
 * - TDD proof: used as building blocks by GitCredentialStrategyTest; no standalone observable change
 * - Excludes: JGit authentication, network credential providers, UI integration.
 */

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object AuthorizedCredentialReadSessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        CredentialReadAuthorization.Authorized
}

internal fun gitCredentialStrategy(token: String?): GitCredentialStrategy =
    gitCredentialStrategy(
        if (token != null) {
            CredentialSecretReadResult.Present(token)
        } else {
            CredentialSecretReadResult.Missing
        },
    )

internal fun gitCredentialStrategy(result: CredentialSecretReadResult): GitCredentialStrategy =
    GitCredentialStrategy(
        credentialRepository = GitTestCredentialRepository(result),
        securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
    )

private class GitTestCredentialRepository(
    private val result: CredentialSecretReadResult,
) : CredentialRepository {
    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(CredentialState(provider = provider, fields = emptyList()))

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        CredentialState(provider = provider, fields = emptyList())

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult =
        if (field == CredentialField.GIT_TOKEN) result else CredentialSecretReadResult.Missing

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) = Unit
}
