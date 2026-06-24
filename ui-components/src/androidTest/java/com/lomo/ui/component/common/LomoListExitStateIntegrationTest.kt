package com.lomo.ui.component.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Behavior Contract:
 * - Unit under test: rememberLomoListExitState Compose integration with ExitAnimationRegistry.
 * - Owning layer: ui-components common.
 * - Priority tier: P1.
 * - Capability: retain exiting items in the Compose render list when they are removed from allItems, and settle them when onExitSettled is called.
 *
 * Scenarios:
 * - Given a list of items and a registry, when an item is marked exiting and removed from the source list, then it is still retained in the Compose render list at its anchored position.
 * - Given a retained exiting item, when onExitSettled is called, then the item is removed from the Compose render list.
 *
 * Observable outcomes:
 * - The items and their exit states inside the resolved render list during Compose state updates.
 *
 * TDD proof:
 * - RED: Fails when we make onExitSettled a no-op (or don't call settleExit), so the exiting item is never removed from the render list.
 * - GREEN: Fails initially (before registry.settleExit runs), and passes once settleExit is called and registry emits the updated entries.
 *
 * Excludes:
 * - Layout placement specs, animations, and main feed item renderer details.
 */
@RunWith(AndroidJUnit4::class)
class LomoListExitStateIntegrationTest {
    private data class TestItem(val id: String, val value: String = "")

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exitingItems_areRetainedAndSettleCorrectly() {
        val registry = ExitAnimationRegistry<TestItem>()
        var itemsState by mutableStateOf(listOf(TestItem("a"), TestItem("b"), TestItem("c")))
        var resolvedRenderList: List<LomoListExitRenderEntry<TestItem>> = emptyList()
        var settleExitAction: ((String) -> Unit)? = null

        composeRule.setContent {
            val exitState = rememberLomoListExitState(
                registry = registry,
                allItems = itemsState.toImmutableList(),
                itemKey = { it.id }
            )
            resolvedRenderList = exitState.renderList
            settleExitAction = exitState.onExitSettled
        }

        composeRule.waitForIdle()
        assertEquals(3, resolvedRenderList.size)
        assertTrue(resolvedRenderList.all { !it.isExiting })

        // Begin exit on item 'b' and update items list (remove 'b')
        composeRule.runOnIdle {
            registry.beginExit("b", TestItem("b"), "a")
            itemsState = listOf(TestItem("a"), TestItem("c"))
        }

        composeRule.waitForIdle()
        // 'b' must be retained at index 1
        assertEquals(3, resolvedRenderList.size)
        assertEquals("a", resolvedRenderList[0].item.id)
        assertEquals("b", resolvedRenderList[1].item.id)
        assertTrue(resolvedRenderList[1].isExiting)
        assertEquals("c", resolvedRenderList[2].item.id)

        // Settle the exit on 'b'
        composeRule.runOnIdle {
            settleExitAction?.invoke("b")
        }

        composeRule.waitForIdle()
        // 'b' must now be completely gone
        assertEquals(2, resolvedRenderList.size)
        assertEquals("a", resolvedRenderList[0].item.id)
        assertEquals("c", resolvedRenderList[1].item.id)
    }
}
