/*
 * Behavior Contract:
 * - Unit under test: GitRemoteUrlUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for GitRemoteUrlUseCaseTest.
 * - Boundary: boundary and edge cases for GitRemoteUrlUseCaseTest.
 * - Failure: failure and error scenarios for GitRemoteUrlUseCaseTest.
 * - Must-not-happen: invariants are never violated for GitRemoteUrlUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of GitRemoteUrlUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

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


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class GitRemoteUrlUseCaseTest : DomainFunSpec() {
    private val policy = GitRemoteUrlUseCase()
    init {
        test("isValid rejects blank — an empty URL is not a valid remote endpoint") {
            // Clearing stays an explicit dialog affordance; the validator never calls blank valid.
            (policy.isValid("")) shouldBe false
            (policy.isValid("   ")) shouldBe false
        }

        test("isValid accepts https remote with repository path") {
            (policy.isValid("https://github.com/unsigned57/lomo.git")) shouldBe true
        }

        test("isValid rejects non-https or missing repo path") {
            (policy.isValid("http://github.com/unsigned57/lomo.git")) shouldBe false
            (policy.isValid("https://github.com")) shouldBe false
        }

        test("isValid rejects userinfo and ssh-shaped endpoints") {
            (policy.isValid("https://alice:s3cr3t@github.com/org/repo.git")) shouldBe false
            (policy.isValid("https://alice@github.com/org/repo.git")) shouldBe false
            (policy.isValid("git@github.com:org/repo.git")) shouldBe false
            (policy.isValid("ssh://git@github.com/org/repo.git")) shouldBe false
            (policy.isValid("file:///srv/repo.git")) shouldBe false
        }

        test("isValidBranch accepts ordinary short ref names") {
            (policy.isValidBranch("main")) shouldBe true
            (policy.isValidBranch("master")) shouldBe true
            (policy.isValidBranch("dev-2.x")) shouldBe true
        }

        test("isValidBranch rejects empty and git-ref-unsafe names") {
            (policy.isValidBranch("")) shouldBe false
            (policy.isValidBranch("   ")) shouldBe false
            (policy.isValidBranch("feature/x")) shouldBe false
            (policy.isValidBranch("-wip")) shouldBe false
            (policy.isValidBranch(".hidden")) shouldBe false
            (policy.isValidBranch("trailing.")) shouldBe false
            (policy.isValidBranch("wip.lock")) shouldBe false
            (policy.isValidBranch("WIP.LOCK")) shouldBe false
            (policy.isValidBranch("a..b")) shouldBe false
            (policy.isValidBranch("a@{b")) shouldBe false
            (policy.isValidBranch("a?b")) shouldBe false
            (policy.isValidBranch("a*b")) shouldBe false
            (policy.isValidBranch("a[b")) shouldBe false
            (policy.isValidBranch("a~b")) shouldBe false
            (policy.isValidBranch("a^b")) shouldBe false
            (policy.isValidBranch("a:b")) shouldBe false
            (policy.isValidBranch("a\\b")) shouldBe false
        }

        test("normalize trims and removes trailing slash") {
            policy.normalize(" https://example.com/org/repo.git/ ") shouldBe "https://example.com/org/repo.git"
        }
    }
}
