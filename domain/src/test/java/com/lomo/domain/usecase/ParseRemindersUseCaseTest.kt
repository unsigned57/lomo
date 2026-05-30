/*
 * Behavior Contract:
 * - Unit under test: ReminderMarker + ParseRemindersUseCase
 * - Behavior focus: parse the `@YYYY-MM-DD-HH:MM[xN][iM][rR][.done|.k]` token from memo content into structured ReminderMarker values.
 * - Observable outcomes: list of markers with dueAt, repeatCount, firedCount, intervalMinutes, recurrence, done, tokenRange, raw text.
 * - TDD proof: fails because parser does not extract recurrence and intervalMinutes.
 * - Excludes: scheduling, notifications.
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


import com.lomo.domain.model.ReminderMarker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ParseRemindersUseCaseTest : FunSpec({
    val parse = ParseRemindersUseCase()

    test("returns empty list when content has no reminder marker") {
        parse("hello world") shouldBe emptyList()
    }

    test("parses single-shot reminder without repeat or state") {
        val results = parse("buy milk @2026-05-20-09:00 today")

        results shouldHaveSize 1
        val marker = results.single()
        marker.dueAt shouldBe LocalDateTime.of(2026, 5, 20, 9, 0)
        marker.repeatCount shouldBe 1
        marker.firedCount shouldBe 0
        marker.done shouldBe false
        marker.raw shouldBe "@2026-05-20-09:00"
    }

    test("parses repeat count when xN suffix is present") {
        val marker = parse("@2026-05-20-09:00x3").single()
        marker.repeatCount shouldBe 3
        marker.firedCount shouldBe 0
        marker.done shouldBe false
    }

    test("parses fired progress when xN.k suffix is present") {
        val marker = parse("@2026-05-20-09:00x3.2").single()
        marker.repeatCount shouldBe 3
        marker.firedCount shouldBe 2
        marker.done shouldBe false
    }

    test("parses done state on single-shot reminder") {
        val marker = parse("@2026-05-20-09:00.done").single()
        marker.repeatCount shouldBe 1
        marker.firedCount shouldBe 0
        marker.done shouldBe true
    }

    test("parses done state on repeating reminder") {
        val marker = parse("@2026-05-20-09:00x3.done").single()
        marker.repeatCount shouldBe 3
        marker.done shouldBe true
    }

    test("captures token range for in-text rewrite") {
        val content = "before @2026-05-20-09:00x2 after"
        val marker = parse(content).single()
        content.substring(marker.tokenRange.first, marker.tokenRange.last + 1) shouldBe "@2026-05-20-09:00x2"
    }

    test("parses multiple markers in order") {
        val results = parse("first @2026-05-20-09:00 then @2026-06-01-10:30x2.1")
        results shouldHaveSize 2
        results[0].dueAt shouldBe LocalDateTime.of(2026, 5, 20, 9, 0)
        results[1].dueAt shouldBe LocalDateTime.of(2026, 6, 1, 10, 30)
        results[1].firedCount shouldBe 1
    }

    test("does not match incomplete or malformed timestamps") {
        parse("@2026-5-20-09:00") shouldBe emptyList()
        parse("@2026-05-20 09:00") shouldBe emptyList()
        parse("@2026-05-20-9:00") shouldBe emptyList()
    }

    test("does not collide with markdown link or image syntax") {
        val results = parse("see [docs](https://example.com) and ![](a.png) but @2026-05-20-09:00")
        results shouldHaveSize 1
        results.single().raw shouldBe "@2026-05-20-09:00"
    }

    test("isExhausted true when done") {
        val marker = parse("@2026-05-20-09:00.done").single()
        marker.isExhausted shouldBe true
    }

    test("isExhausted true when fired equals repeat") {
        val marker = parse("@2026-05-20-09:00x3.3").single()
        marker.isExhausted shouldBe true
    }

    test("isExhausted false when fired below repeat") {
        val marker = parse("@2026-05-20-09:00x3.1").single()
        marker.isExhausted shouldBe false
    }

    test("parses interval from iN suffix") {
        val marker = parse("@2026-05-20-09:00x3i5").single()
        marker.repeatCount shouldBe 3
        marker.intervalMinutes shouldBe 5
        marker.recurrence shouldBe com.lomo.domain.model.Recurrence.NONE
    }

    test("parses recurrence from rd or rw suffix") {
        val daily = parse("@2026-05-20-09:00rd").single()
        daily.recurrence shouldBe com.lomo.domain.model.Recurrence.DAILY

        val weekly = parse("@2026-05-20-09:00rw").single()
        weekly.recurrence shouldBe com.lomo.domain.model.Recurrence.WEEKLY
    }

    test("parses complex token with interval, recurrence and progress state") {
        val marker = parse("@2026-05-20-09:00x3i15rd.2").single()
        marker.repeatCount shouldBe 3
        marker.intervalMinutes shouldBe 15
        marker.recurrence shouldBe com.lomo.domain.model.Recurrence.DAILY
        marker.firedCount shouldBe 2
        marker.done shouldBe false
    }

    test("ReminderMarker serializes back to canonical token with recurrence and interval") {
        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 3,
            firedCount = 0,
            done = false,
            intervalMinutes = 15,
            recurrence = com.lomo.domain.model.Recurrence.DAILY
        ) shouldBe "@2026-05-20-09:00x3i15rd"

        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 1,
            firedCount = 0,
            done = false,
            intervalMinutes = 10,
            recurrence = com.lomo.domain.model.Recurrence.WEEKLY
        ) shouldBe "@2026-05-20-09:00rw"
    }

    test("ReminderMarker serializes back to canonical token") {
        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 1,
            firedCount = 0,
            done = false,
        ) shouldBe "@2026-05-20-09:00"
        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 3,
            firedCount = 0,
            done = false,
        ) shouldBe "@2026-05-20-09:00x3"
        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 3,
            firedCount = 2,
            done = false,
        ) shouldBe "@2026-05-20-09:00x3.2"
        ReminderMarker.canonicalToken(
            dueAt = LocalDateTime.of(2026, 5, 20, 9, 0),
            repeatCount = 3,
            firedCount = 3,
            done = true,
        ) shouldBe "@2026-05-20-09:00x3.done"
    }
})
