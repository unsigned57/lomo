/*
 * Behavior Contract:
 * - Unit under test: MemoContentFlags
 * - Behavior focus: detect presence of TODO checkboxes, attachments (image / audio), and URLs in memo markdown.
 * - Observable outcomes: boolean output per input string.
 * - TDD proof: fails because MemoContentFlags does not yet exist.
 * - Excludes: filter orchestration, repository persistence.
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


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoContentFlagsTest : FunSpec({
    test("containsTodo returns false for plain text") {
        MemoContentFlags.containsTodo("nothing here") shouldBe false
    }
    test("containsTodo matches unchecked markdown box at line start") {
        MemoContentFlags.containsTodo("- [ ] buy milk") shouldBe true
    }
    test("containsTodo matches checked markdown box lowercase") {
        MemoContentFlags.containsTodo("- [x] done item") shouldBe true
    }
    test("containsTodo matches checked markdown box uppercase") {
        MemoContentFlags.containsTodo("- [X] done item") shouldBe true
    }
    test("containsTodo matches asterisk bullet form") {
        MemoContentFlags.containsTodo("* [ ] item") shouldBe true
    }
    test("containsTodo matches indented form") {
        MemoContentFlags.containsTodo("    - [ ] indented") shouldBe true
    }
    test("containsTodo matches todo in any line of multiline content") {
        MemoContentFlags.containsTodo("hello\n- [ ] write tests\nmore") shouldBe true
    }
    test("containsTodo ignores bracket pair without bullet") {
        MemoContentFlags.containsTodo("[ ] not a todo") shouldBe false
    }

    test("containsAttachment returns false for plain text") {
        MemoContentFlags.containsAttachment("just text") shouldBe false
    }
    test("containsAttachment matches markdown image syntax") {
        MemoContentFlags.containsAttachment("![alt](images/x.png)") shouldBe true
    }
    test("containsAttachment matches wiki image syntax") {
        MemoContentFlags.containsAttachment("![[foo.jpg]]") shouldBe true
    }
    test("containsAttachment matches audio link by extension") {
        MemoContentFlags.containsAttachment("voice memo [play](recordings/clip.m4a)") shouldBe true
    }
    test("containsAttachment matches mp3 audio link") {
        MemoContentFlags.containsAttachment("song [a](a.mp3)") shouldBe true
    }
    test("containsAttachment does not classify regular link as attachment") {
        MemoContentFlags.containsAttachment("see [docs](https://example.com)") shouldBe false
    }

    test("containsUrl returns false for plain text") {
        MemoContentFlags.containsUrl("just words") shouldBe false
    }
    test("containsUrl matches http url") {
        MemoContentFlags.containsUrl("link http://example.com here") shouldBe true
    }
    test("containsUrl matches https url") {
        MemoContentFlags.containsUrl("see https://example.com/path?q=1") shouldBe true
    }
    test("containsUrl matches url inside markdown link") {
        MemoContentFlags.containsUrl("[docs](https://example.com)") shouldBe true
    }
    test("containsUrl ignores ftp scheme") {
        MemoContentFlags.containsUrl("ftp://example.com/file") shouldBe false
    }
})
