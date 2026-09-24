package com.lomo.data.local.datastore

/*
 * Behavior Contract:
 * - Unit under test: GitSyncPreferenceMigration (B06/T41 branch persistence backfill).
 * - Owning layer: data (DataStore produceMigrations).
 * - Priority tier: P1.
 * - Capability: installs that already persisted a Git remote before the branch was configurable
 *   receive an explicit `main` branch; fresh installs and already-migrated stores stay untouched.
 *
 * Scenarios:
 * - Given a stored remote and no branch key, when migration runs, then GIT_BRANCH becomes "main".
 * - Given a stored branch value, then the migration never overwrites it.
 * - Given no remote, then nothing is written.
 *
 * Observable outcomes: Preferences content for GIT_BRANCH.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.local.datastore.GitSyncPreferenceMigrationTest'
 * - RED: no branch key existed — the branch was an implicit code constant.
 *
 * Excludes: Keystore credential migration (owned by GitEndpointSecurityMigration).
 */

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class GitSyncPreferenceMigrationTest : FunSpec({
    test("existing git remote without a branch receives the explicit default") {
        runTest {
            val prefs =
                mutablePreferencesOf(
                    LomoDataStoreKeys.GIT_REMOTE_URL to "https://example.com/repo.git",
                )

            GitSyncPreferenceMigration.shouldMigrate(prefs) shouldBe true
            val migrated = GitSyncPreferenceMigration.migrate(prefs)

            migrated[LomoDataStoreKeys.GIT_BRANCH] shouldBe "main"
            migrated[LomoDataStoreKeys.GIT_REMOTE_URL] shouldBe "https://example.com/repo.git"
        }
    }

    test("a stored branch is never overwritten") {
        runTest {
            val prefs =
                mutablePreferencesOf(
                    LomoDataStoreKeys.GIT_REMOTE_URL to "https://example.com/repo.git",
                    LomoDataStoreKeys.GIT_BRANCH to "master",
                )

            GitSyncPreferenceMigration.shouldMigrate(prefs) shouldBe false
        }
    }

    test("no remote means nothing to migrate") {
        runTest {
            GitSyncPreferenceMigration.shouldMigrate(emptyPreferences()) shouldBe false
        }
    }

    test("remote without value stays absent when migration was never needed") {
        runTest {
            val migrated = GitSyncPreferenceMigration.migrate(emptyPreferences())

            migrated[LomoDataStoreKeys.GIT_BRANCH].shouldBeNull()
        }
    }
})
