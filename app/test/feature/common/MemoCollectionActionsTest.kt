/*
 * Behavior Contract:
 * - Unit under test: MemoCollectionActions
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: handle memo actions like delete, restore, deletePermanently, and clearTrash, safeguarding against crashes and reporting mapping failures cleanly.
 *
 * Scenarios:
 * - Given delete action, when mapToUiModel throws exception, then exception is caught and reported to errors.
 * - Given restore action, when mapToUiModel throws exception, then exception is caught and reported to errors.
 * - Given deletePermanently action, when mapToUiModel throws exception, then exception is caught and reported to errors.
 * - Given clearTrash action, when mapToUiModel throws exception, then exception is caught and reported to errors.
 * - Given an editor update, when the durable mutation is pending, then submit awaits it and reports
 *   success only after content replacement is committed.
 *
 * Observable outcomes:
 * - Errors state is updated with mapping failure without throwing an exception to the caller thread.
 *
 * TDD proof:
 * - Fails with unhandled exception on the caller thread before the fix when mapToUiModel throws.
 *
 * Excludes:
 * - DB operations, UI rendering.
 *
 * Test Change Justification:
 * - Reason category: API contract migration.
 * - Old behavior/assertion being replaced: clearTrash test setup passed a raw Triple of id, memo, and anchor.
 * - Why old assertion is no longer correct: clearTrash now accepts DeleteAnimationItem so the delete snapshot and anchor are modeled by one typed command.
 * - Coverage preserved by: the clearTrash scenario still asserts the observable mapping failure message.
 * - Why this is not fitting the test to the implementation: the assertion remains error reporting at the collection action boundary; only the input command shape changed.
 */

package com.lomo.app.feature.common

import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.ExitAnimationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MemoCollectionActionsTest : AppFunSpec() {
    init {
        test("given delete is called, when mapToUiModel throws, then exception is caught and reported to errors") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.DeletableTodo(
                    deleteMemo = {},
                    toggleTodo = { _, _ -> "updated" }
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = { throw RuntimeException("markdown render error") }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.delete(memo, null)
                runCurrent()
                errorMessage.value shouldBe "Failed to delete memo: markdown render error"
            }
        }

        test("given restore is called, when mapToUiModel throws, then exception is caught and reported to errors") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.Trash(
                    restoreMemo = {},
                    deletePermanently = {},
                    clearTrash = {}
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = { throw RuntimeException("markdown render error") }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.restore(memo, null)
                runCurrent()
                errorMessage.value shouldBe "Failed to restore memo: markdown render error"
            }
        }

        test("given deletePermanently is called, when mapToUiModel throws, then exception is caught and reported to errors") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.Trash(
                    restoreMemo = {},
                    deletePermanently = {},
                    clearTrash = {}
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = { throw RuntimeException("markdown render error") }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.deletePermanently(memo, null)
                runCurrent()
                errorMessage.value shouldBe "Failed to delete memo: markdown render error"
            }
        }

        test("given clearTrash is called, when mapToUiModel throws, then exception is caught and reported to errors") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.Trash(
                    restoreMemo = {},
                    deletePermanently = {},
                    clearTrash = {}
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = { throw RuntimeException("markdown render error") }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.clearTrash(
                    listOf(
                        DeleteAnimationItem(
                            id = "memo_1",
                            snapshot = memo,
                            anchoredAfterKey = null,
                        ),
                    ),
                )
                runCurrent()
                errorMessage.value shouldBe "Failed to clear trash: markdown render error"
            }
        }

        test("given delete is called on Trash collection, then error is reported") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.Trash(
                    restoreMemo = {},
                    deletePermanently = {},
                    clearTrash = {}
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = {
                        MemoUiModel(
                            memo = it,
                            processedContent = "Hello",
                            renderDocument = com.lomo.app.testing.fakes.emptyRenderDocument(),
                            tags = kotlinx.collections.immutable.persistentListOf()
                        )
                    }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.delete(memo, null)
                runCurrent()
                errorMessage.value shouldBe "Failed to delete memo: Cannot delete memo in Trash. Use restore or deletePermanently instead."
            }
        }

        test("given restore is called on non-Trash collection, then error is reported") {
            runTest {
                val registry = ExitAnimationRegistry<MemoUiModel>()
                val errorMessage = MutableStateFlow<String?>(null)
                val errors = MemoCollectionErrors(errorMessage)
                val capabilities = MemoCollectionCapabilities.DeletableTodo(
                    deleteMemo = {},
                    toggleTodo = { _, _ -> "updated" }
                )
                val actions = MemoCollectionActions(
                    exitAnimationRegistry = registry,
                    errors = errors,
                    capabilities = capabilities,
                    scope = this,
                    onMemoContentReplaced = null,
                    mapToUiModel = {
                        MemoUiModel(
                            memo = it,
                            processedContent = "Hello",
                            renderDocument = com.lomo.app.testing.fakes.emptyRenderDocument(),
                            tags = kotlinx.collections.immutable.persistentListOf()
                        )
                    }
                )

                val memo = Memo(
                    id = "memo_1",
                    timestamp = 1000L,
                    content = "Hello",
                    rawContent = "Hello",
                    dateKey = "2026_06_24"
                )

                actions.restore(memo, null)
                runCurrent()
                errorMessage.value shouldBe "Failed to restore memo: Cannot restore memo. Collection is not Trash."
            }
        }

        test("editor update awaits durable mutation before acknowledging commit") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val errorMessage = MutableStateFlow<String?>(null)
                val memo =
                    Memo(
                        id = "memo-submit",
                        timestamp = 1_000L,
                        content = "old",
                        rawContent = "old",
                        dateKey = "2026_08_09",
                    )
                var replacement: String? = null
                val actions =
                    MemoCollectionActions(
                        exitAnimationRegistry = ExitAnimationRegistry(),
                        errors = MemoCollectionErrors(errorMessage),
                        capabilities =
                            MemoCollectionCapabilities.Editable(
                                deleteMemo = {},
                                updateMemo = { _, _ -> gate.await() },
                                toggleTodo = { _, _ -> "updated" },
                                saveImage = { error("not used") },
                            ),
                        scope = this,
                        onMemoContentReplaced = { _, content -> replacement = content },
                        mapToUiModel = { error("not used") },
                    )

                val result =
                    async {
                        actions.submitMemoUpdate(
                            MemoEditorSubmissionId(20L),
                            memo,
                            "committed",
                        )
                    }
                runCurrent()

                result.isCompleted shouldBe false
                replacement shouldBe null

                gate.complete(Unit)
                result.await() shouldBe true
                replacement shouldBe "committed"
                errorMessage.value shouldBe null
            }
        }
    }
}
