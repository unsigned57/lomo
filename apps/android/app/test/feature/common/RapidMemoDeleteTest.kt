package com.lomo.app.feature.common

import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.testing.fakes.FakeEngineDiagnosticsRecorder
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.ExitAnimationRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: rapid row deletes admit independent identities and suppress duplicate in-flight taps; owner: app; P1.
 * Scenarios:
 * - Given two rows and a suspended mapping, when the first is tapped twice and the second once,
 *   then both identities are deleted once and no not-found error is displayed.
 * Observable outcomes: deleted identity set, empty error state and committed exit entries.
 * TDD proof: target/lomo/reports/manual/four-bugs/duplicate-red.log reports Failed to delete memo: memo not found;
 * GREEN: same spec passes with admission before asynchronous mapping.
 * Excludes: Rust document CAS, animation duration and Android gestures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RapidMemoDeleteTest :
    FunSpec({
        test("given rapid taps before mapping finishes then duplicate deletion cannot reject a different row") {
            runTest {
                val mapping = CompletableDeferred<Unit>()
                val deleted = mutableSetOf<String>()
                val error = MutableStateFlow<String?>(null)
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = MemoCollectionErrors(error, FakeEngineDiagnosticsRecorder()),
                    draftId = DraftId("rapid-delete"),
                    scope = this,
                    capabilities = MemoCollectionCapabilities.DeletableTodo(
                        deleteMemo = { memo, _ -> check(deleted.add(memo.id)) { "memo not found" } },
                        toggleTodo = { _, _ -> error("No todo action") },
                    ),
                    mapToUiModel = {
                        mapping.await()
                        mockk<MemoUiModel>()
                    },
                )
                fun memo(id: String) =
                    Memo(id = id, timestamp = 1L, content = id, rawContent = id, dateKey = "2026_11_04")
                actions.delete(memo("first"), null)
                actions.delete(memo("first"), null)
                actions.delete(memo("second"), null)
                runCurrent()
                mapping.complete(Unit)
                runCurrent()
                deleted shouldBe setOf("first", "second")
                error.value shouldBe null
                registry.entries.value.values.all { it.mutationCommitted } shouldBe true
            }
        }
    })
