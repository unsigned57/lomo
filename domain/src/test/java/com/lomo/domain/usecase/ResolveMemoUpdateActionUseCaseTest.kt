package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ResolveMemoUpdateActionUseCase
 * - Behavior focus: blank content maps to trashing while non-blank content stays on the update path.
 * - Observable outcomes: returned MemoUpdateAction for blank and non-blank editor content.
 * - Excludes: downstream deletion/update effects and editor UI behavior.
 */
class ResolveMemoUpdateActionUseCaseTest {
    private val useCase = ResolveMemoUpdateActionUseCase()

    @Test
    fun `invoke returns move-to-trash for blank input`() {
        assertEquals(MemoUpdateAction.MOVE_TO_TRASH, useCase("   "))
    }

    @Test
    fun `invoke returns update-content for non-blank input`() {
        assertEquals(MemoUpdateAction.UPDATE_CONTENT, useCase("keep this memo"))
    }
}
