package com.lomo.data.diagnostics

/*
 * Behavior Contract:
 * - Unit under test: RingBufferEngineDiagnosticsRecorder
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: keep an in-memory newest-first ring of diagnostic events whose error partition
 *   cannot be evicted by routine Committed traffic, stamp timestamps via injected clock, and reset
 *   cleanly on clear().
 *
 * Scenarios:
 * - Given an unstamped event, when record() runs, then the recorder stamps recordedAtMillis from its clock.
 * - Given earlier Rejected and Stalled events followed by a burst of Committed events, when recorded,
 *   then all error events stay retained instead of being evicted by routine info traffic.
 * - Given more error events than errorCapacity, when recorded, then only the newest errorCapacity errors remain.
 * - Given populated partitions, when clear() runs, then events becomes empty.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RingBufferEngineDiagnosticsRecorderTest : DataFunSpec() {
    init {
        fun rejected(code: String): EngineDiagnosticEvent.Rejected = EngineDiagnosticEvent.Rejected(
            label = "workspace.open",
            durationMillis = 12L,
            failure = EngineCommandFailure(
                category = EngineFailureCategory.VALIDATION,
                code = code,
                retryDisposition = EngineRetryDisposition.NEVER,
                operationId = null,
                jobId = null,
                diagnostic = "failure:$code",
            ),
        )

        fun committed(rev: Long): EngineDiagnosticEvent.Committed = EngineDiagnosticEvent.Committed(
            label = "memo.sync",
            durationMillis = 4L,
            coreRevision = rev,
            scopes = listOf("memos"),
        )

        test("stamps events using injected clock and preserves earlier errors across info bursts") {
            var now = 1_000L
            val recorder = RingBufferEngineDiagnosticsRecorder(
                capacity = 6,
                errorCapacity = 3,
                clock = {
                    now += 10L
                    now
                },
            )

            recorder.record(rejected("invalid_calendar_input"))
            recorder.record(EngineDiagnosticEvent.Stalled(label = "workspace.open", durationMillis = 500L))
            repeat(8) { idx ->
                recorder.record(committed(idx.toLong()))
            }

            val snapshot = recorder.events.value
            snapshot.shouldHaveSize(5)
            snapshot.count { it is EngineDiagnosticEvent.Rejected || it is EngineDiagnosticEvent.Stalled } shouldBe 2
            snapshot.filterIsInstance<EngineDiagnosticEvent.Rejected>().single().failure.code shouldBe
                "invalid_calendar_input"
            val isDescending =
                snapshot.zipWithNext().all { (newer, older) ->
                    newer.recordedAtMillis >= older.recordedAtMillis
                }
            isDescending shouldBe true

            recorder.record(rejected("err_3"))
            recorder.record(rejected("err_4"))

            val afterErrorOverflow = recorder.events.value
            val retainedCodes = afterErrorOverflow.filterIsInstance<EngineDiagnosticEvent.Rejected>().map {
                it.failure.code
            }
            retainedCodes shouldBe listOf("err_4", "err_3")
            afterErrorOverflow.any { it is EngineDiagnosticEvent.Stalled } shouldBe true
        }

        test("clear resets both error and info partitions") {
            var now = 2_000L
            val recorder = RingBufferEngineDiagnosticsRecorder(
                capacity = 4,
                errorCapacity = 2,
                clock = {
                    now += 5L
                    now
                },
            )

            recorder.record(rejected("boom"))
            recorder.record(committed(1L))
            recorder.events.value.shouldHaveSize(2)

            recorder.clear()
            recorder.events.value.shouldBeEmpty()
        }
    }
}
