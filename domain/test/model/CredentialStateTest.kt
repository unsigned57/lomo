package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: Credential state domain model
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: represent stored credential presence and read/validation failures without depending on provider code.
 *
 * Scenarios:
 * - Given a missing credential, when configuration state is queried, then it is not configured.
 * - Given a present credential, when configuration state is queried, then it is configured.
 * - Given an unreadable credential, when configuration state is queried, then it is not treated as missing or configured.
 * - Given an invalid credential, when configuration state is queried, then it remains distinguishable from missing.
 * - Given optional S3 stored credentials are unreadable, when aggregate state is queried, then provider readiness remains present while health is unreadable.
 *
 * Observable outcomes:
 * - StoredCredentialStatus values, their configured/missing flags, and provider readiness/health status.
 *
 * TDD proof:
 * - Fails before the fix because StoredCredentialStatus does not exist.
 *
 * Excludes:
 * - Android Keystore I/O, DataStore migration, provider UI coordinator state, and credential value material.
 */
class CredentialStateTest : DomainFunSpec() {
    init {
        test("given stored credential statuses when queried then configured and missing are explicit") {
            StoredCredentialStatus.Missing.isConfigured.shouldBeFalse()
            StoredCredentialStatus.Missing.isMissing.shouldBeTrue()

            StoredCredentialStatus.Present.isConfigured.shouldBeTrue()
            StoredCredentialStatus.Present.isMissing.shouldBeFalse()

            StoredCredentialStatus.Unreadable.isConfigured.shouldBeFalse()
            StoredCredentialStatus.Unreadable.isMissing.shouldBeFalse()

            StoredCredentialStatus.Invalid.isConfigured.shouldBeFalse()
            StoredCredentialStatus.Invalid.isMissing.shouldBeFalse()
        }

        test("given provider credential state when any required field is unavailable then provider is not configured") {
            val state =
                CredentialState(
                    provider = CredentialProvider.WEBDAV,
                    fields =
                        listOf(
                            CredentialFieldState(CredentialField.WEBDAV_USERNAME, StoredCredentialStatus.Present),
                            CredentialFieldState(CredentialField.WEBDAV_PASSWORD, StoredCredentialStatus.Unreadable),
                        ),
                )

            state.status shouldBe StoredCredentialStatus.Unreadable
            state.isConfigured.shouldBeFalse()
        }

        test("given optional s3 stored secret is unreadable when status is queried then readiness remains present but health is unreadable") {
            val state =
                CredentialState(
                    provider = CredentialProvider.S3,
                    fields =
                        listOf(
                            CredentialFieldState(CredentialField.S3_ACCESS_KEY_ID, StoredCredentialStatus.Present),
                            CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, StoredCredentialStatus.Present),
                            CredentialFieldState(CredentialField.S3_SESSION_TOKEN, StoredCredentialStatus.Unreadable),
                            CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD, StoredCredentialStatus.Missing),
                            CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD2, StoredCredentialStatus.Missing),
                        ),
                )

            state.readinessStatus shouldBe StoredCredentialStatus.Present
            state.healthStatus shouldBe StoredCredentialStatus.Unreadable
            state.status shouldBe StoredCredentialStatus.Unreadable
            state.isConfigured.shouldBeTrue()
        }
    }
}
