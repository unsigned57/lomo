package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SyncBackendType.fromStorageValue
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: a persisted backend selection parses into its exact enum fact; an unrecognized
 *   value surfaces as UNKNOWN (unavailable) rather than collapsing into NONE and silently
 *   canceling the user's configured sync.
 *
 * Scenarios:
 * - Given each defined backend string, when parsed, then the matching enum is returned.
 * - Given an unrecognized string, when parsed, then UNKNOWN is returned.
 * - Given an absent or blank value, when parsed, then NONE (the never-selected state) is
 *   returned.
 *
 * Observable outcomes: parsed enum identity.
 *
 * TDD proof:
 * - Fails before the fix because UNKNOWN and fromStorageValue do not exist.
 *
 * Excludes: persistence IO, scheduler behavior.
 */
class SyncBackendTypeTest : DomainFunSpec() {
    init {
        test("given defined backend strings when parsed then exact enum is returned") {
            mapOf(
                "none" to SyncBackendType.NONE,
                "git" to SyncBackendType.GIT,
                "webdav" to SyncBackendType.WEBDAV,
                "s3" to SyncBackendType.S3,
                "inbox" to SyncBackendType.INBOX,
            ).forEach { (raw, expected) ->
                SyncBackendType.fromStorageValue(raw) shouldBe expected
            }
        }

        test("given an unrecognized backend string when parsed then unknown is returned") {
            SyncBackendType.fromStorageValue("bogus-backend") shouldBe SyncBackendType.UNKNOWN
            SyncBackendType.fromStorageValue("GIT2") shouldBe SyncBackendType.UNKNOWN
        }

        test("given absent or blank storage value when parsed then none is returned") {
            SyncBackendType.fromStorageValue(null) shouldBe SyncBackendType.NONE
            SyncBackendType.fromStorageValue("") shouldBe SyncBackendType.NONE
            SyncBackendType.fromStorageValue("   ") shouldBe SyncBackendType.NONE
        }

        test("given a backend enum when stored then the storage value round-trips") {
            SyncBackendType.entries
                .filter { it != SyncBackendType.UNKNOWN }
                .forEach { backend ->
                    SyncBackendType.fromStorageValue(backend.storageValue()) shouldBe backend
                }
        }
    }
}
