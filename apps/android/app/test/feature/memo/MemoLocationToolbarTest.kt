package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputEditorCommand
import com.lomo.ui.component.input.InputToolbarActionId
import com.lomo.ui.component.input.InputToolbarToolPlacement
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: coordinate insertion remains discoverable in more tools; owner: app; P1.
 * Scenarios:
 * - Given an editor session, when tools are resolved, then location is available in the secondary tray.
 * Observable outcomes: discoverable action, placement and dispatched command identity.
 * TDD proof: ui-followup/toolbar-red.log fails before location is registered; toolbar-green.log passes.
 * Excludes: Android location providers and device permission dialogs.
 */
class MemoLocationToolbarTest : AppFunSpec() {
    init {
        test("given an editor when tools resolve then coordinates remain in more tools") {
            val location = InputToolbarActionId("location")
            val actions = memoEditorToolbarTools(recording = false)
            (location in actions) shouldBe true
            val tool = memoEditorToolbarToolMetadata(actions, true, true, true).single { it.id == location }
            tool.placement shouldBe InputToolbarToolPlacement.SecondaryTray
            tool.command shouldBe InputEditorCommand.Action(location)
        }
    }
}
