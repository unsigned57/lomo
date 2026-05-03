package com.lomo.app.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: SearchScreen dedicated search route wiring.
 * - Behavior focus: the search route must be a single in-page Material 3 Expressive search
 *   surface. The top search field must stay editable on entry, leave system back to the route
 *   owner, and move in lockstep with result-list scroll instead of opening a second full-screen
 *   search dialog.
 * - Observable outcomes: SearchScreen source renders SearchBarDefaults.InputField as a content
 *   overlay rather than in Scaffold.topBar, drives floating offset from the result list's consumed
 *   scroll deltas, renders a floating Surface around the input,
 *   routes focus through SearchInputMorphTargets, retains status-bar padding and benchmark
 *   anchors, and does not use full-screen/docked search expansion APIs that consume back first.
 * - Red phase: Fails before the production changes because SearchScreen first used a
 *   dialog-backed expanded search surface, then still placed the M3E capsule in Scaffold.topBar,
 *   and later used Material3 SearchBarScrollBehavior to consume nested scroll independently from
 *   the memo list, making the capsule move faster than the cards.
 * - Excludes: Compose runtime measurement, pixel rendering, animation frames, IME timing, and
 *   result-list item rendering.
 *
 * Test Change Justification:
 * - Reason category: product contract correction after regression. The previous full-screen
 *   contained search view made the dedicated route behave like two stacked search pages.
 * - Old behavior/assertion being replaced: assertions requiring SearchBar,
 *   ExpandedFullScreenContainedSearchBar, rememberContainedSearchBarState, and
 *   SearchBarValue.Expanded.
 * - Why old assertion is no longer correct: those assertions require a dialog-backed expanded
 *   search view, whose internal back handling causes the reported two-back exit behavior.
 * - Coverage preserved by: this file still rejects handwritten BasicTextField search boxes and
 *   old DockedSearchBar/local focus morph helpers, while adding explicit coverage for one-page
 *   back behavior and synchronized scroll-driven capsule movement. SearchBarHidePolicyTest
 *   preserves the short-result-list and one-to-one consumed-delta behavior with pure outputs.
 * - Why this is not fitting the test to the implementation: the current implementation still uses
 *   the now-forbidden full-screen contained search APIs and lacks the required nested-scroll
 *   wiring, so these assertions fail before the fix.
 */
class SearchScreenContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val searchScreenSource =
        appModuleRoot.resolve("src/main/java/com/lomo/app/feature/search/SearchScreen.kt")

    @Test
    fun `search screen uses a single in page material search field instead of a second search dialog`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must keep SearchBarDefaults.InputField as the editable in-page search
            control so entering the route focuses one field, not a second search dialog.
            """.trimIndent(),
            content.contains("SearchBarDefaults.InputField("),
        )
        assertTrue(
            """
            SearchScreen must use Material3 search input colors for the standalone input field.
            """.trimIndent(),
            content.contains("SearchBarDefaults.inputFieldColors("),
        )
        assertTrue(
            """
            SearchScreen must render the search input inside its own floating Surface, not as a
            full-width app-bar background.
            """.trimIndent(),
            content.contains("Surface("),
        )
        assertFalse(
            "SearchScreen must not declare a hand-rolled BasicTextField search box.",
            content.contains("BasicTextField("),
        )
        assertFalse(
            "SearchScreen must not reintroduce a private SearchQueryField composable.",
            content.contains("private fun SearchQueryField("),
        )
        assertFalse(
            """
            SearchScreen must not use AppBarWithSearch on this dedicated search route.
            AppBarWithSearch's Collapsed state intercepts InputField clicks to drive
            expansion, which leaves the input non-editable when the route initialises with
            SearchBarValue.Collapsed; the soft keyboard never opens. AppBarWithSearch is
            the right M3E component for a main-screen top app bar with a collapsible
            search trigger, not for a dedicated search route.
            """.trimIndent(),
            content.contains("AppBarWithSearch("),
        )
        assertFalse(
            "SearchScreen must not keep the old top-only DockedSearchBar implementation.",
            content.contains("DockedSearchBar("),
        )
        assertFalse(
            """
            SearchScreen must not use ExpandedFullScreenContainedSearchBar on this dedicated route.
            It opens a second dialog-backed search surface whose internal back handler makes users
            press Back once to collapse the dialog and again to leave the route.
            """.trimIndent(),
            content.contains("ExpandedFullScreenContainedSearchBar("),
        )
        assertFalse(
            "SearchScreen must not initialize a SearchBarState as Expanded for this route.",
            content.contains("SearchBarValue.Expanded"),
        )
        assertFalse(
            "SearchScreen must not remember contained full-screen search state.",
            content.contains("rememberContainedSearchBarState("),
        )
    }

    @Test
    fun `search capsule floats as a content overlay instead of being wrapped by scaffold top bar`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertFalse(
            """
            SearchScreen must not put the floating capsule in Scaffold.topBar. topBar reserves a
            full-width app-bar strip, which makes the capsule look wrapped by a bar while scrolling.
            """.trimIndent(),
            content.contains("topBar = {"),
        )
        assertTrue(
            """
            SearchScreen must render the search capsule inside the content Box overlay so only the
            capsule surface moves above the results.
            """.trimIndent(),
            content.contains("FloatingSearchBar("),
        )
        assertTrue(
            """
            The floating search capsule must be aligned at the top center of the content overlay.
            """.trimIndent(),
            content.contains(".align(Alignment.TopCenter)"),
        )
        assertTrue(
            """
            Content must receive explicit top padding for the floating capsule because Scaffold no
            longer reserves topBar height.
            """.trimIndent(),
            content.contains("floatingSearchContentPadding("),
        )
    }

    @Test
    fun `search screen drives focused and resting morph targets from input focus`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must observe the real input focus state so click-to-type and end-input
            transitions can drive the M3E capsule morph.
            """.trimIndent(),
            content.contains("collectIsFocusedAsState("),
        )
        assertTrue(
            """
            SearchScreen must route focus through SearchInputMorphTargets.fromFocus so shape, tone,
            and emphasis are unit-testable outside Compose animation timing.
            """.trimIndent(),
            content.contains("SearchInputMorphTargets.fromFocus("),
        )
        assertTrue(
            """
            SearchScreen must animate the outer capsule shape rather than keeping a static shape.
            """.trimIndent(),
            content.contains("animateDpAsState("),
        )
        assertTrue(
            """
            SearchScreen must animate the outer capsule surface tone for the M3E focus reaction.
            """.trimIndent(),
            content.contains("animateColorAsState("),
        )
        assertTrue(
            """
            SearchScreen must make the Material3 input container transparent so the custom floating
            Surface owns the visible capsule shape.
            """.trimIndent(),
            content.contains("focusedContainerColor = Color.Transparent") &&
                content.contains("unfocusedContainerColor = Color.Transparent"),
        )
    }

    @Test
    fun `search screen top bar applies status bar padding to avoid overlap`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must apply status-bar inset padding around the SearchBar container.
            The floating SearchBar container owns its own status-bar inset padding because the
            route no longer relies on Scaffold.topBar to reserve system-bar space.
            """.trimIndent(),
            content.contains("statusBarsPadding()"),
        )
    }

    @Test
    fun `search screen wires floating search movement to consumed result scroll`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen content must attach a synchronized search-bar nested-scroll connection so
            the capsule moves exactly as far as memo cards are consumed by the result list.
            """.trimIndent(),
            content.contains("rememberSearchBarSynchronizedScrollConnection(") &&
                content.contains("nestedScroll(searchBarSynchronizedScrollConnection)"),
        )
        assertFalse(
            """
            SearchScreen must not attach SearchBarScrollBehavior.nestedScrollConnection directly.
            The direct connection lets the search bar consume scroll separately from memo cards.
            """.trimIndent(),
            content.contains("nestedScroll(searchBarScrollBehavior.nestedScrollConnection)"),
        )
        assertFalse(
            """
            SearchScreen must not use SearchBarScrollBehavior.searchBarScrollBehavior() for this
            floating capsule. That modifier moves at Material3 app-bar speed instead of the memo
            list's consumed-scroll speed.
            """.trimIndent(),
            content.contains("searchBarScrollBehavior()"),
        )
        assertTrue(
            """
            SearchScreen and MemoCardList must share one LazyListState so the hide gate can read
            real result-list scrollability.
            """.trimIndent(),
            content.contains("val searchResultListState = rememberLazyListState()") &&
                content.contains("resultListState = searchResultListState") &&
                content.contains("listState = searchResultListState"),
        )
        assertTrue(
            """
            SearchScreen floating container must offset by the synchronized search-bar offset.
            """.trimIndent(),
            content.contains("searchBarOffsetPx = searchBarOffsetPx") &&
                content.contains("IntOffset(0, -searchBarOffsetPx.roundToInt())"),
        )
        assertTrue(
            """
            SearchScreen must measure the full floating search container before status-bar and
            outer padding are applied. Otherwise the max synchronized offset only covers the inner
            capsule and leaves part of the floating bar stuck at the top.
            """.trimIndent(),
            content.contains(
                ".offset { IntOffset(0, -searchBarOffsetPx.roundToInt()) } .fillMaxWidth() " +
                    ".onSizeChanged { size -> onMaxOffsetPxChange(size.height.toFloat()) } " +
                    ".statusBarsPadding() .padding(",
            ),
        )
    }

    @Test
    fun `search screen preserves benchmark anchors on input and clear action`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must keep the search input benchmark anchor on the Material3 input field.
            """.trimIndent(),
            content.contains("BenchmarkAnchorContract.SEARCH_INPUT"),
        )
        assertTrue(
            """
            SearchScreen must keep the clear-action benchmark anchor on the clear IconButton.
            """.trimIndent(),
            content.contains("BenchmarkAnchorContract.SEARCH_CLEAR"),
        )
    }

    @Test
    fun `search screen removes obsolete docked focus morph helpers`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertFalse(
            """
            SearchInputMotionTargets belongs to the old DockedSearchBar focus morph. The contained
            M3E search view should use SearchBarState and SearchBarDefaults.containedColors instead.
            """.trimIndent(),
            content.contains("SearchInputMotionTargets"),
        )
        assertFalse(
            "SearchScreen must not keep its old rememberSearchInputMotion helper.",
            content.contains("rememberSearchInputMotion("),
        )
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
