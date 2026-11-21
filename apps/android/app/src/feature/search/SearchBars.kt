package com.lomo.app.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorSurface
import com.lomo.app.feature.memo.MemoMenuCommandHandler
import com.lomo.domain.model.MemoSearchMode
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.coroutines.flow.distinctUntilChanged

/** Material 3 Expressive full-screen search scaffold with floating search bar and floating mode capsule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchBars(
    inputState: TextFieldState,
    inputScroll: ScrollState,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    uiState: SearchScreenUiSnapshot,
    pagedItems: LazyPagingItems<MemoUiModel>,
    memoMenuCommandHandler: MemoMenuCommandHandler,
    editorController: MemoEditorController,
    editorSurface: MemoEditorSurface,
    snackbarHostState: SnackbarHostState,
    searchResultListState: LazyListState,
    onCollapse: () -> Unit,
    commands: SearchScreenCommands,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val layoutMotion = LocalLomoMotion.current.layout
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        // Navigation resumes this destination after entry; the input is attached by then.
        lifecycleOwner.lifecycle.withResumed {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    var isSearchFilterSheetVisible by rememberSaveable { mutableStateOf(false) }

    val (isBarsVisible, nestedScrollConnection) =
        rememberSearchScrollVisibility(
            searchResultListState = searchResultListState,
            query = uiState.query,
            isFilterSheetVisible = isSearchFilterSheetVisible,
        )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets =
        WindowInsets.safeDrawing.only(
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
        ),
    ) { innerPadding ->
        val isImeVisible = WindowInsets.isImeVisible
        val isCapsuleVisible =
            isSearchModeCapsuleVisible(
                isBarsVisible = isBarsVisible,
                isImeVisible = isImeVisible,
                hasQueryText = inputState.text.isNotEmpty(),
            )

        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .nestedScroll(nestedScrollConnection),
        ) {
            SearchExpandedContent(
                onNavigateToImage = onNavigateToImage,
                pagedItems = pagedItems,
                uiState = uiState,
                menuCommandHandler = memoMenuCommandHandler,
                editorController = editorController,
                editorSurface = editorSurface,
                snackbarHostState = snackbarHostState,
                searchResultListState = searchResultListState,
                isSearchFilterSheetVisible = isSearchFilterSheetVisible,
                onFilterSheetDismiss = { isSearchFilterSheetVisible = false },
                commands = commands,
            )

            AnimatedVisibility(
                visible = isBarsVisible,
                enter =
                slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = layoutMotion.defaultSpatialSpec(),
                ) + fadeIn(animationSpec = layoutMotion.defaultEffectsSpec()),
                exit =
                slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = layoutMotion.defaultSpatialSpec(),
                ) + fadeOut(animationSpec = layoutMotion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                FloatingSearchTopBar(
                    inputState = inputState,
                    inputScroll = inputScroll,
                    uiState = uiState,
                    focusRequester = focusRequester,
                    onCollapse = onCollapse,
                    onQueryChanged = commands.onQueryChanged,
                    onOpenFilter = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        isSearchFilterSheetVisible = true
                    },
                )
            }

            FloatingSearchModeCapsule(
                visible = isCapsuleVisible,
                searchMode = uiState.searchMode,
                onToggleSearchMode = commands.onToggleSearchMode,
            )
        }
    }
}

@Composable
private fun rememberSearchScrollVisibility(
    searchResultListState: LazyListState,
    query: String,
    isFilterSheetVisible: Boolean,
): Pair<Boolean, NestedScrollConnection> {
    var isBarsVisible by rememberSaveable { mutableStateOf(true) }

    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val deltaY = available.y
                    if (deltaY < -10f) {
                        isBarsVisible = false
                    } else if (deltaY > 10f) {
                        isBarsVisible = true
                    }
                    return Offset.Zero
                }
            }
        }

    // The scroll position changes every frame; observe it in a snapshot flow instead of
    // recomposing this function for each pixel scrolled.
    LaunchedEffect(searchResultListState) {
        snapshotFlow {
            searchResultListState.firstVisibleItemIndex == 0 &&
                searchResultListState.firstVisibleItemScrollOffset == 0
        }.distinctUntilChanged()
            .collect { atTop ->
                if (atTop) isBarsVisible = true
            }
    }

    LaunchedEffect(query, isFilterSheetVisible) {
        isBarsVisible = true
    }

    return isBarsVisible to nestedScrollConnection
}

@Composable
private fun BoxScope.FloatingSearchModeCapsule(
    visible: Boolean,
    searchMode: MemoSearchMode,
    onToggleSearchMode: () -> Unit,
) {
    val componentMotion = LocalLomoMotion.current.component
    val appearance = updateTransition(visible, label = "SearchModeCapsuleVisibility")
    val appearanceFraction by appearance.animateFloat(
        transitionSpec = {
            if (targetState) {
                componentMotion.defaultSpatialSpec()
            } else {
                componentMotion.fastSpatialSpec()
            }
        },
        label = "SearchModeCapsuleAppearance",
    ) { shown -> if (shown) 1f else 0f }

    if (appearance.currentState || appearance.targetState) {
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(bottom = AppSpacing.Medium),
        ) {
            SearchModeFloatingCapsule(
                searchMode = searchMode,
                onToggleSearchMode = { if (visible) onToggleSearchMode() },
                appearanceFraction = appearanceFraction,
            )
        }
    }
}

/** Floating pill search top bar designed per Material 3 Expressive guidelines. */
@Composable
private fun FloatingSearchTopBar(
    inputState: TextFieldState,
    inputScroll: ScrollState,
    uiState: SearchScreenUiSnapshot,
    focusRequester: FocusRequester,
    onCollapse: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(
                horizontal = AppSpacing.Medium,
                vertical = AppSpacing.Small,
            ),
    ) {
        Surface(
            shape = AppShapes.Full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
            modifier =
            Modifier
                .fillMaxWidth()
                .benchmarkAnchorRoot(BenchmarkAnchorContract.SEARCH_INPUT),
        ) {
            SearchBarInputField(
                query = uiState.query,
                inputState = inputState,
                inputScroll = inputScroll,
                expanded = true,
                interactive = true,
                onQueryChanged = onQueryChanged,
                onBack = onCollapse,
                onOpenFilter = onOpenFilter,
                isFilterActive = uiState.searchFilter.isActive || uiState.searchFilter.hasSortOverride,
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}
