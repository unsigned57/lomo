package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.CalendarHeatmapThresholds
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: calendar heatmap threshold settings editor policy.
 * - Owning layer: app/settings
 * - Priority tier: P1
 * - Capability: model settings-dialog threshold input, validation, and range presentation through
 *   one app-layer policy before Compose renders controls.
 *
 * Scenarios:
 * - Given persisted thresholds, when editor inputs are created, then all three editable boundaries
 *   are shown as numeric text.
 * - Given valid boundary input, when editor state is resolved, then Save is enabled with the typed
 *   CalendarHeatmapThresholds value.
 * - Given non-numeric, out-of-range, or non-increasing input, when editor state is resolved, then
 *   Save is disabled with an explicit validation reason.
 * - Given default thresholds, when range rows are presented, then the UI has the complete empty,
 *   light, medium, darker, and darkest ranges.
 *
 * Observable outcomes:
 * - Editor input text, parsed thresholds, validation error, can-save flag, and range-row model.
 *
 * TDD proof:
 * - RED: this focused test does not compile until the settings editor policy exists.
 *
 * Excludes:
 * - Compose dialog rendering, string-resource localization, repository writes, and heatmap canvas
 *   drawing.
 */
class CalendarHeatmapThresholdsEditorTest : AppFunSpec() {
    init {
        test("given persisted thresholds when editor input is created then boundary text is populated") {
            val thresholds = CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)

            thresholds.toCalendarHeatmapThresholdEditorInput() shouldBe
                CalendarHeatmapThresholdEditorInput(
                    level1Max = "2",
                    level2Max = "5",
                    level3Max = "9",
                )
        }

        test("given valid editor input when resolved then save is enabled with typed thresholds") {
            val state =
                resolveCalendarHeatmapThresholdEditorState(
                    CalendarHeatmapThresholdEditorInput(
                        level1Max = "2",
                        level2Max = "5",
                        level3Max = "9",
                    ),
                )

            assertSoftly(state) {
                canSave shouldBe true
                validationError shouldBe null
                thresholds shouldBe CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)
            }
        }

        test("given invalid editor input when resolved then save is disabled with explicit error") {
            mapOf(
                CalendarHeatmapThresholdEditorInput("", "3", "6") to
                    CalendarHeatmapThresholdValidationError.NON_NUMERIC,
                CalendarHeatmapThresholdEditorInput("0", "3", "6") to
                    CalendarHeatmapThresholdValidationError.OUT_OF_RANGE,
                CalendarHeatmapThresholdEditorInput("2", "2", "6") to
                    CalendarHeatmapThresholdValidationError.NOT_STRICTLY_INCREASING,
            ).forEach { (input, expectedError) ->
                val state = resolveCalendarHeatmapThresholdEditorState(input)

                assertSoftly(state) {
                    canSave shouldBe false
                    thresholds shouldBe null
                    validationError shouldBe expectedError
                }
            }
        }

        test("given default thresholds when range rows are presented then complete intensity ranges are exposed") {
            calendarHeatmapThresholdRangeRows(CalendarHeatmapThresholds.default()) shouldContainExactly
                listOf(
                    CalendarHeatmapThresholdRangeRow(
                        range = CalendarHeatmapThresholdCountRange.Empty,
                        tone = CalendarHeatmapThresholdTone.Blank,
                    ),
                    CalendarHeatmapThresholdRangeRow(
                        range = CalendarHeatmapThresholdCountRange.Single(1),
                        tone = CalendarHeatmapThresholdTone.Light,
                    ),
                    CalendarHeatmapThresholdRangeRow(
                        range = CalendarHeatmapThresholdCountRange.Bounded(start = 2, endInclusive = 3),
                        tone = CalendarHeatmapThresholdTone.Medium,
                    ),
                    CalendarHeatmapThresholdRangeRow(
                        range = CalendarHeatmapThresholdCountRange.Bounded(start = 4, endInclusive = 6),
                        tone = CalendarHeatmapThresholdTone.Darker,
                    ),
                    CalendarHeatmapThresholdRangeRow(
                        range = CalendarHeatmapThresholdCountRange.Unbounded(start = 7),
                        tone = CalendarHeatmapThresholdTone.Darkest,
                    ),
                )
        }
    }
}
