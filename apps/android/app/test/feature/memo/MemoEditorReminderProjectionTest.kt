package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.input.inputEditorVisibleText
import com.lomo.ui.component.input.removeInputTextRange
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/*
 * Behavior Contract:
 * Capability: Rust-recognized reminders become cancellable editor date chips; owner: app; priority: P1.
 * Scenarios:
 * - Given Chinese/emoji source and a recognized reminder, when projected, then UTF-8 spans hide the exact UTF-16 token.
 * - Given a reminder capsule, when cancelled then undone, then only the reminder is removed and exact source returns.
 * - Given a token in code, when projected, then it remains text without a reminder capsule.
 * Observable outcomes: formatted date, visible text, cancellation bytes and undo restoration.
 * TDD proof: device reminder_capsules RED reports missing benchmark_input_reminder_badge after confirmation.
 * Excludes: parsing grammar, permission dialogs and AlarmManager scheduling.
 */
class MemoEditorReminderProjectionTest : AppFunSpec() {
    init {
        test("given Unicode source when a reminder is cancelled then offsets and undo preserve the rest") {
            val prefix = "你好😀 "
            val token = "@2026-11-07-10:30"
            val suffix = " 正文"
            val source = prefix + token + suffix
            val all = MarkdownSourceSpan(0uL, source.encodeToByteArray().size.toULong())
            val tokenSpan = MarkdownSourceSpan(
                prefix.encodeToByteArray().size.toULong(),
                (prefix + token).encodeToByteArray().size.toULong(),
            )
            val document = MarkdownRenderDocument(
                all.endByte,
                "",
                emptyList(),
                emptyList(),
                listOf(
                    MarkdownRenderBlock.Paragraph(all, listOf(MarkdownRenderInline.Reminder(tokenSpan, token))),
                ),
            )
            val chips = projectMemoEditorReminders(
                source,
                document,
                { LocalDateTime.of(2026, 11, 7, 10, 30) },
                "yyyy-MM-dd",
                "HH:mm",
            )
            chips.single().label shouldBe "2026-11-07 10:30"
            inputEditorVisibleText(source, chips.map { it.range }) shouldBe prefix.trimEnd() + suffix
            val controller = MemoEditorController()
            controller.openForCreate(source)
            controller.updateInputValue(removeInputTextRange(controller.inputValue, source, chips.single().range))
            controller.inputValue.text shouldBe prefix.trimEnd() + suffix
            controller.undo()
            controller.inputValue.text shouldBe source
            controller.redo()
            controller.inputValue.text shouldBe prefix.trimEnd() + suffix
        }
        test("given reminder-like code when projecting then no date decoder is called") {
            val source = "@2026-11-07-10:30"
            val span = MarkdownSourceSpan(0uL, source.length.toULong())
            val document = MarkdownRenderDocument(
                span.endByte,
                "",
                emptyList(),
                emptyList(),
                listOf(MarkdownRenderBlock.CodeBlock(span, null, source)),
            )
            projectMemoEditorReminders(
                source,
                document,
                { error("Code is not reminder metadata") },
                "yyyy-MM-dd",
                "HH:mm",
            ) shouldBe emptyList()
        }
    }
}
