package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: MemoMenuCommandHandler.
 * - Owning layer: app/feature/memo.
 * - Priority tier: P1.
 * - Capability: convert memo menu presentation state into one canonical command-dispatch surface.
 *
 * Scenarios:
 * - Given an app-owned memo selection, when every menu command is invoked, then the matching command
 *   leaves through the handler with the memo-derived payload.
 * - Given the main-list pin context or the jump context, when the context action runs, then the pin
 *   toggle (inverting the memo's pinned state) or the jump leaves through the handler.
 *
 * Observable outcomes:
 * - Ordered command events recorded by a fake sink, including share-card payload fields.
 *
 * TDD proof:
 * - Fails before the fix because app/feature/memo has no MemoMenuCommandHandler owner; command
 *   dispatch is spread across MemoMenuBinder and screen-level callback parameters.
 *
 * Excludes:
 * - Compose sheet rendering, clipboard implementation, Android share intents, and InputSheet behavior.
 *
 * Production scope: apps/android/app/src/feature/memo/MemoMenuCommandHandler.kt
 *
 * Test Change Justification:
 * - Reason category: memo menu redesign replaced optional pin/jump callbacks with one required
 *   context action and removed usage-based action reorder.
 * - Old behavior/assertion being replaced: togglePin and jump were separate optional commands on
 *   one handler; usage and order changes were dispatched as ActionUsage/ActionOrder events.
 * - Why old assertion is no longer correct: a screen now supplies exactly one context action, so a
 *   single handler cannot expose both; the reorder feature and its commands were deleted.
 * - Coverage preserved by: every remaining command event is still asserted in order, and both context
 *   actions are asserted with the same payloads as before (pin inversion and jump selection).
 * - Why this is not fitting the test to the implementation: the assertions check the user-visible
 *   command each context produces, not how the handler stores callbacks.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 *
 * Test Change Justification:
 * - Reason category: copy command moved to the app-owned complete-content boundary.
 * - Old behavior/assertion being replaced: the command sequence did not include clipboard copy.
 * - Why old assertion is no longer correct: copy now resolves the selected memo through this handler.
 * - Coverage preserved by: all original command events remain asserted, with copy added in order.
 * - Why this is not fitting the test to the implementation: the new event checks the selected memo
 *   identity and label delivered to the copy boundary.
 */
class MemoMenuCommandHandlerTest : AppFunSpec() {
    init {
        test("given memo state when commands are invoked then dispatches memo menu commands through one handler") {
            val events = mutableListOf<MenuEvent>()
            val handler = recordingHandler(events)
            val memo = memo(isPinned = false)
            val selection =
                memoMenuSelection(
                    memo = memo,
                    dateFormat = "yyyy-MM-dd",
                    timeFormat = "HH:mm",
                    imageUrls = listOf("images/a.png"),
                )

            handler.edit(selection)
            handler.delete(selection)
            handler.copy(selection, "Memo")
            handler.shareCard(selection)
            handler.shareText(selection)
            handler.lanShare(selection)
            handler.runContextAction(selection)
            handler.versionHistory(selection)

            events shouldBe
                listOf(
                    MenuEvent.Edit("memo-1"),
                    MenuEvent.Delete("memo-1"),
                    MenuEvent.Copy("memo-1", "Memo"),
                    MenuEvent.ShareCard(
                        content = "body",
                        timestamp = 1234L,
                        tags = listOf("daily"),
                        resolvedImagePaths = listOf("images/a.png"),
                        showTime = true,
                        showSignature = false,
                        signatureText = "sig",
                    ),
                    MenuEvent.ShareText("body"),
                    MenuEvent.LanShare(content = "body", timestamp = 1234L),
                    MenuEvent.TogglePin(memoId = "memo-1", pinned = true),
                    MenuEvent.VersionHistory("memo-1"),
                )
        }

        test("given the jump context when the context action runs then the selection jumps") {
            val events = mutableListOf<MenuEvent>()
            val handler =
                recordingHandler(
                    events = events,
                    contextAction = MemoMenuContextAction.Jump { selection ->
                        events += MenuEvent.Jump(
                            selection.memo.id,
                        )
                    },
                )
            val selection = memoMenuSelection(
                memo = memo(isPinned = true),
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
            )

            handler.runContextAction(selection)

            events shouldBe listOf(MenuEvent.Jump("memo-1"))
        }

        test("given a pinned memo in the pin context when the context action runs then it is unpinned") {
            val events = mutableListOf<MenuEvent>()
            val handler = recordingHandler(events)
            val selection = memoMenuSelection(
                memo = memo(isPinned = true),
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
            )

            handler.runContextAction(selection)

            events shouldBe listOf(MenuEvent.TogglePin(memoId = "memo-1", pinned = false))
        }
    }

    private fun recordingHandler(
        events: MutableList<MenuEvent>,
        contextAction: MemoMenuContextAction =
            MemoMenuContextAction.TogglePin { memo, pinned -> events += MenuEvent.TogglePin(memo.id, pinned) },
    ): MemoMenuCommandHandler = MemoMenuCommandHandler(
        presentationState =
        MemoMenuPresentationState(
            shareCardShowTime = true,
            shareCardShowSignature = false,
            shareCardSignatureText = "sig",
            customFontPath = null,
        ),
        onEditMemo = { memoId -> events += MenuEvent.Edit(memoId) },
        onDeleteMemo = { memo, _ -> events += MenuEvent.Delete(memo.id) },
        onShareCard = { request ->
            events +=
                MenuEvent.ShareCard(
                    content = request.content,
                    timestamp = request.timestamp,
                    tags = request.tags,
                    resolvedImagePaths = request.resolvedImagePaths,
                    showTime = request.showTime,
                    showSignature = request.showSignature,
                    signatureText = request.signatureText,
                )
        },
        onShareText = { request -> events += MenuEvent.ShareText(request.content) },
        onCopyMemo = { selection, label -> events += MenuEvent.Copy(selection.memo.id, label) },
        onLanShare = { request -> events += MenuEvent.LanShare(request.content, request.timestamp) },
        contextAction = contextAction,
        onVersionHistory = { selection -> events += MenuEvent.VersionHistory(selection.memo.id) },
    )

    private fun memo(isPinned: Boolean): Memo = Memo(
        id = "memo-1",
        timestamp = 1234L,
        content = "body",
        rawContent = "body",
        dateKey = "2026_05_25",
        localDate = LocalDate.of(2026, 5, 25),
        tags = persistentListOf("daily"),
        imageUrls = listOf("images/a.png"),
        isPinned = isPinned,
    )

    private sealed interface MenuEvent {
        data class Edit(val memoId: String) : MenuEvent

        data class Delete(val memoId: String) : MenuEvent

        data class Copy(val memoId: String, val label: String) : MenuEvent

        data class ShareCard(
            val content: String,
            val timestamp: Long?,
            val tags: List<String>,
            val resolvedImagePaths: List<String>,
            val showTime: Boolean,
            val showSignature: Boolean,
            val signatureText: String,
        ) : MenuEvent

        data class ShareText(val content: String) : MenuEvent

        data class LanShare(val content: String, val timestamp: Long) : MenuEvent

        data class TogglePin(val memoId: String, val pinned: Boolean) : MenuEvent

        data class Jump(val memoId: String) : MenuEvent

        data class VersionHistory(val memoId: String) : MenuEvent
    }
}
