package com.lomo.data.engine

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.markdown.MarkdownRenderContractException
import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.LanServicePhaseDto
import com.lomo.nativebridge.LanServiceSnapshotDto
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.ShutdownOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/*
 * Behavior Contract:
 * Capability: the native render boundary converts every Rust refusal into a typed domain failure;
 *   owning layer: data engine port; priority: P0.
 * Scenarios:
 * - Given Rust refuses a render for its presentation budget (resource_limit /
 *   render_document_too_large), when the port renders, then MarkdownRenderContractException with
 *   that code surfaces — the same type the domain IR check raises for node-limit violations, so
 *   editor/preview consumers show their Failed state instead of crashing.
 * - Given Rust refuses a render for any other category, when the port renders, then a typed
 *   EngineCommandFailureException keeps the category, code and diagnostic.
 * Observable outcomes: thrown exception type, code and category.
 * TDD proof: RED 2026-11-10 (`./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.engine.BoltFfiNativeEnginePortRenderFailureTest'`): the raw
 *   generated EngineError.Failure escaped — on emulator-5554 opening Edit on a memo beyond 8192
 *   render nodes crashed the main thread with it.
 * Excludes: Rust render budget semantics, Compose editor layout, workspace session routing.
 */
class BoltFfiNativeEnginePortRenderFailureTest : DataFunSpec() {
    init {
        test("given a presentation budget refusal when rendering then a render contract failure carries its code") {
            val port = portRefusingRender("resource_limit", "render_document_too_large")
            try {
                shouldThrow<MarkdownRenderContractException> { port.renderMarkdown("body", 1u) }
                    .code shouldBe "render_document_too_large"
            } finally {
                port.close()
            }
        }

        test("given a non-budget refusal when rendering then a typed engine command failure surfaces") {
            val port = portRefusingRender("internal", "engine_state_unavailable")
            try {
                val failure = shouldThrow<EngineCommandFailureException> { port.renderMarkdown("body", 1u) }.failure
                failure.category shouldBe EngineFailureCategory.INTERNAL
                failure.code shouldBe "engine_state_unavailable"
                failure.diagnostic shouldBe "refused"
            } finally {
                port.close()
            }
        }
    }
}

private fun portRefusingRender(category: String, code: String): BoltFfiNativeEnginePort {
    val engine = mockk<LomoEngine>()
    every { engine.renderMarkdown(any()) } throws
        EngineError.Failure(
            EngineFailure(
                category = category,
                code = code,
                retryDisposition = "never",
                operationId = null,
                jobId = null,
                diagnostic = "refused",
            ),
        )
    every { engine.stopLanService() } returns LanServiceSnapshotDto(LanServicePhaseDto.STOPPED, null)
    every { engine.shutdown(any()) } returns ShutdownOutcome.COMPLETED
    every { engine.close() } returns Unit
    return BoltFfiNativeEnginePort(engine)
}
