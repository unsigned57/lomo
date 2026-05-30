package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: StorageFilenameFormats and StorageTimestampFormats.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: external Markdown daily-note storage format compatibility.
 *
 * Scenarios:
 * - Given Lomo, Thino, Obsidian, and sync-export daily-note filenames, when the storage contract parses
 *   them, then the canonical local date is recovered from the .md filename.
 * - Given an extensionless date stem is passed to the external filename parser, when the storage contract
 *   parses it, then the stem is rejected so only real Markdown filenames cross the external boundary.
 * - Given Thino and Obsidian-style memo bullet headers, when the storage contract parses them, then the
 *   timestamp token and memo body are split without dropping tags, wiki links, or media references.
 * - Given unsupported decorated daily-note filenames, when the storage contract parses them, then they
 *   are rejected instead of being silently treated as a supported date.
 *
 * Observable outcomes:
 * - Parsed LocalDate values, rejected filenames, parsed memo header time tokens, and parsed content parts.
 *
 * TDD proof:
 * - RED command: `./gradlew :domain:test --tests '*Storage*'`.
 * - RED symptom: `StorageFormatCompatibilityFixtureTest > given extensionless daily note stems when
 *   parsed as filenames then they are rejected` failed; 19 tests completed, 1 failed, because
 *   `parseFilenameOrNull("2026_03_24")` returned a LocalDate instead of rejecting the stem.
 * - GREEN command: `./gradlew :domain:test --tests '*Storage*'`.
 * - GREEN result: `BUILD SUCCESSFUL` after the parser rejected extensionless stems.
 *
 * Excludes:
 * - File IO, repository refresh orchestration, attachment existence checks, sync transport behavior, and UI rendering.
 */
class StorageFormatCompatibilityFixtureTest : DomainFunSpec() {
    init {
        test("given external daily note filenames when parsed then supported local dates are recovered") {
            val expectedDate = LocalDate.of(2026, 5, 23)
            val fixtures =
                listOf(
                    ExternalFilenameFixture("Lomo default", "2026_05_23.md"),
                    ExternalFilenameFixture("Thino daily note", "2026-05-23.md"),
                    ExternalFilenameFixture("Obsidian dotted daily note", "2026.05.23.md"),
                    ExternalFilenameFixture("Compact export", "20260523.md"),
                    ExternalFilenameFixture("Month-first import", "05-23-2026.md"),
                )

            fixtures.forEach { fixture ->
                withClue(fixture.label) {
                    StorageFilenameFormats.parseFilenameOrNull(fixture.filename) shouldBe expectedDate
                }
            }
        }

        test("given extensionless daily note stems when parsed as filenames then they are rejected") {
            val expectedDate = LocalDate.of(2026, 3, 24)

            StorageFilenameFormats.parseFilenameOrNull("2026_03_24.md") shouldBe expectedDate
            StorageFilenameFormats.parseFilenameOrNull("2026_03_24") shouldBe null
            StorageFilenameFormats.parseFilenameOrNull("2026-03-24") shouldBe null
        }

        test("given decorated daily note filenames when parsed then unsupported names are rejected") {
            val unsupported =
                listOf(
                    "Daily 2026-05-23.md",
                    "2026-05-23.backup.md",
                    "2026/05/23.md",
                    "2026-02-29.md",
                )

            unsupported.forEach { filename ->
                withClue(filename) {
                    StorageFilenameFormats.parseFilenameOrNull(filename) shouldBe null
                }
            }
        }

        test("given external memo header lines when parsed then timestamps and markdown body are preserved") {
            val fixtures =
                listOf(
                    ExternalHeaderFixture(
                        label = "Thino seconds header with tag and media",
                        line = "- 17:56:16 Captured with #tag and ![[photo.png]]",
                        expectedTimePart = "17:56:16",
                        expectedContentPart = "Captured with #tag and ![[photo.png]]",
                    ),
                    ExternalHeaderFixture(
                        label = "Obsidian minute header with wiki link",
                        line = "- 9:05 [[Project]] follow-up",
                        expectedTimePart = "9:05",
                        expectedContentPart = "[[Project]] follow-up",
                    ),
                    ExternalHeaderFixture(
                        label = "Tabbed external editor spacing",
                        line = "-\t08:07\tbody keeps spacing after header",
                        expectedTimePart = "08:07",
                        expectedContentPart = "body keeps spacing after header",
                    ),
                )

            fixtures.forEach { fixture ->
                withClue(fixture.label) {
                    val parsed = StorageTimestampFormats.parseMemoHeaderLine(fixture.line)

                    parsed?.timePart shouldBe fixture.expectedTimePart
                    parsed?.contentPart shouldBe fixture.expectedContentPart
                }
            }
        }
    }
}

private data class ExternalFilenameFixture(
    val label: String,
    val filename: String,
)

private data class ExternalHeaderFixture(
    val label: String,
    val line: String,
    val expectedTimePart: String,
    val expectedContentPart: String,
)
