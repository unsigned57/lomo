package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.collections.immutable.persistentListOf

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
 * - Given action usage and action order changes, when the menu reports them, then both changes leave
 *   through the handler command surface.
 *
 * Observable outcomes:
 * - Ordered command events recorded by a fake sink, including share-card payload fields and action order.
 *
 * TDD proof:
 * - Fails before the fix because app/feature/memo has no MemoMenuCommandHandler owner; command
 *   dispatch is spread across MemoMenuBinder and screen-level callback parameters.
 *
 * Excludes:
 * - Compose sheet rendering, clipboard implementation, Android share intents, and InputSheet behavior.
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
            handler.shareCard(selection)
            handler.shareText(selection)
            handler.lanShare(selection)
            handler.togglePin(selection)
            handler.jump(selection)
            handler.versionHistory(selection)

            events shouldBe
                listOf(
                    MenuEvent.Edit("memo-1"),
                    MenuEvent.Delete("memo-1"),
                    MenuEvent.ShareCard(
                        content = "body",
                        timestamp = 1234L,
                        tags = listOf("daily"),
                        resolvedImagePaths = listOf("images/a.png"),
                        geoLocation = "1.0,2.0",
                        showTime = true,
                        showSignature = false,
                        signatureText = "sig",
                    ),
                    MenuEvent.ShareText("body"),
                    MenuEvent.LanShare(content = "body", timestamp = 1234L),
                    MenuEvent.TogglePin(memoId = "memo-1", pinned = true),
                    MenuEvent.Jump("memo-1"),
                    MenuEvent.VersionHistory("memo-1"),
                )
        }

        test("given action usage and order changes when reported then handler dispatches them from its command surface") {
            val events = mutableListOf<MenuEvent>()
            val handler = recordingHandler(events)

            handler.recordActionUsage(MemoActionId.DELETE)
            handler.changeActionOrder(listOf(MemoActionId.EDIT, MemoActionId.DELETE))

            events shouldBe
                listOf(
                    MenuEvent.ActionUsage(MemoActionId.DELETE),
                    MenuEvent.ActionOrder(listOf(MemoActionId.EDIT, MemoActionId.DELETE)),
                )
        }
    }

    private fun recordingHandler(events: MutableList<MenuEvent>): MemoMenuCommandHandler =
        MemoMenuCommandHandler(
            presentationState =
                MemoMenuPresentationState(
                    shareCardShowTime = true,
                    shareCardShowSignature = false,
                    shareCardSignatureText = "sig",
                    customFontPath = null,
                ),
            onEditMemo = { memo -> events += MenuEvent.Edit(memo.id) },
            onDeleteMemo = { memo -> events += MenuEvent.Delete(memo.id) },
            onShareCard = { request ->
                events +=
                    MenuEvent.ShareCard(
                        content = request.content,
                        timestamp = request.timestamp,
                        tags = request.tags,
                        resolvedImagePaths = request.resolvedImagePaths,
                        geoLocation = request.geoLocation,
                        showTime = request.showTime,
                        showSignature = request.showSignature,
                        signatureText = request.signatureText,
                    )
            },
            onShareText = { request -> events += MenuEvent.ShareText(request.content) },
            onLanShare = { request -> events += MenuEvent.LanShare(request.content, request.timestamp) },
            onTogglePin = { request -> events += MenuEvent.TogglePin(request.memo.id, request.pinned) },
            onJump = { selection -> events += MenuEvent.Jump(selection.memo.id) },
            onVersionHistory = { selection -> events += MenuEvent.VersionHistory(selection.memo.id) },
            onMemoActionInvoked = { actionId -> events += MenuEvent.ActionUsage(actionId) },
            onMemoActionOrderChanged = { actionIds -> events += MenuEvent.ActionOrder(actionIds) },
        )

    private fun memo(isPinned: Boolean): Memo =
        Memo(
            id = "memo-1",
            timestamp = 1234L,
            content = "body",
            rawContent = "body",
            dateKey = "2026_05_25",
            localDate = LocalDate.of(2026, 5, 25),
            tags = persistentListOf("daily"),
            imageUrls = listOf("images/a.png"),
            isPinned = isPinned,
            geoLocation = "1.0,2.0",
        )

    private sealed interface MenuEvent {
        data class Edit(val memoId: String) : MenuEvent

        data class Delete(val memoId: String) : MenuEvent

        data class ShareCard(
            val content: String,
            val timestamp: Long?,
            val tags: List<String>,
            val resolvedImagePaths: List<String>,
            val geoLocation: String?,
            val showTime: Boolean,
            val showSignature: Boolean,
            val signatureText: String,
        ) : MenuEvent

        data class ShareText(val content: String) : MenuEvent

        data class LanShare(
            val content: String,
            val timestamp: Long,
        ) : MenuEvent

        data class TogglePin(
            val memoId: String,
            val pinned: Boolean,
        ) : MenuEvent

        data class Jump(val memoId: String) : MenuEvent

        data class VersionHistory(val memoId: String) : MenuEvent

        data class ActionUsage(val actionId: MemoActionId) : MenuEvent

        data class ActionOrder(val actionIds: List<MemoActionId>) : MenuEvent
    }
}
