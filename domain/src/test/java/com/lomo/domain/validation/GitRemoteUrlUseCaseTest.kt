/*
 * Test Contract:
 * - Unit under test: GitRemoteUrlUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for GitRemoteUrlUseCaseTest.
 * - Boundary: boundary and edge cases for GitRemoteUrlUseCaseTest.
 * - Failure: failure and error scenarios for GitRemoteUrlUseCaseTest.
 * - Must-not-happen: invariants are never violated for GitRemoteUrlUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of GitRemoteUrlUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class GitRemoteUrlUseCaseTest : DomainFunSpec() {
    private val policy = GitRemoteUrlUseCase()
    init {
        test("isValid accepts blank for clearing config") {
            (policy.isValid("")) shouldBe true
            (policy.isValid("   ")) shouldBe true
        }
    }
    init {
        test("isValid accepts https remote with repository path") {
            (policy.isValid("https://github.com/unsigned57/lomo.git")) shouldBe true
        }
    }
    init {
        test("isValid rejects non-https or missing repo path") {
            (policy.isValid("http://github.com/unsigned57/lomo.git")) shouldBe false
            (policy.isValid("https://github.com")) shouldBe false
        }
    }
    init {
        test("normalize trims and removes trailing slash") {
            policy.normalize(" https://example.com/org/repo.git/ ") shouldBe "https://example.com/org/repo.git"
        }
    }
}
