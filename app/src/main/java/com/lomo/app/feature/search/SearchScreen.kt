package com.lomo.app.feature.search

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.MemoListFilter
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlin.math.roundToInt

import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onRequestFocusMemo: (String) -> Unit = {},
    onNavigateToMain: () -> Unit = onBackClick,
    lanShareEnabled: Boolean = true,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val pagedItems = viewModel.pagedUiMemos.collectAsLazyPagingItems()
    val uiState = collectSearchScreenUiSnapshot(viewModel, pagedItems)
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val searchResultListState = rememberLazyListState()
    val editorController = rememberMemoEditorController()
    val resultListActive =
        uiState.query.isNotEmpty() &&
            !uiState.showLoading &&
            pagedItems.itemCount > 0

    SearchScreenEffects(
        errorMessage = uiState.errorMessage,
        focusRequester = focusRequester,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )
    val memoMenuCommandHandler =
        rememberSearchMemoMenuCommandHandler(
            uiState = uiState,
            editorController = editorController,
            viewModel = viewModel,
            lanShareEnabled = lanShareEnabled,
            onNavigateToShare = onNavigateToShare,
            onRequestFocusMemo = onRequestFocusMemo,
            onNavigateToMain = onNavigateToMain,
        )

    val editorSurface = searchMemoEditorSurface(uiState = uiState, viewModel = viewModel)

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
    ) { showMenu, openEditor ->
        val onShowSearchMenu =
            rememberSearchMenuHandler(
                focusManager = focusManager,
                keyboardController = keyboardController,
                showMenu = showMenu,
            )
        var isFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
        SearchScreenScaffold(
            query = uiState.query,
            isFilterActive = uiState.searchFilter.isActive,
            onBackClick = onBackClick,
            onQueryChange = viewModel::onSearchQueryChanged,
            onOpenFilter = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                isFilterSheetVisible = true
            },
            haptic = haptic,
            focusRequester = focusRequester,
            keyboardController = keyboardController,
            snackbarHostState = snackbarHostState,
            resultListState = searchResultListState,
            resultListActive = resultListActive,
        ) { paddingValues ->
            SearchScreenContent(
                pagedItems = pagedItems,
                query = uiState.query,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
                doubleTapEditEnabled = uiState.doubleTapEditEnabled,
                freeTextCopyEnabled = uiState.freeTextCopyEnabled,
                exitAnimationRegistry = viewModel.exitAnimationRegistry,
                listState = searchResultListState,
                padding = paddingValues,
                onOpenEditor = openEditor,
                onShowMenu = onShowSearchMenu,
                onTodoClick = viewModel::toggleTodo,
            )
        }
        if (isFilterSheetVisible) {
            com.lomo.app.feature.common.MemoFilterSheetHost(
                visible = true,
                controller = viewModel.searchFilterController,
                onDismiss = { isFilterSheetVisible = false },
            )
        }
    }
}

@Composable
private fun collectSearchScreenUiSnapshot(
    viewModel: SearchViewModel,
    pagedItems: LazyPagingItems<com.lomo.app.feature.main.MemoUiModel>
): SearchScreenUiSnapshot {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchFilter by viewModel.searchFilter.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val rootDirectory by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val showLoading = pagedItems.loadState.refresh is LoadState.Loading

    return SearchScreenUiSnapshot(
        query = query,
        showLoading = showLoading,
        searchFilter = searchFilter,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrderForSearch = appPreferences.memoActionOrderFor(MemoActionOrderScopes.SEARCH),
        inputToolbarToolOrder = appPreferences.inputToolbarToolOrder,
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = remember(imageMap) { imageMap.toImmutableMap() },
        errorMessage = errorMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenScaffold(
    query: String,
    isFilterActive: Boolean,
    onBackClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenFilter: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    snackbarHostState: SnackbarHostState,
    resultListState: LazyListState,
    resultListActive: Boolean,
    content: @Composable (PaddingValues) -> Unit,
) {
    var searchBarOffsetPx by remember { mutableFloatStateOf(0f) }
    var searchBarMaxOffsetPx by remember { mutableFloatStateOf(0f) }
    val searchBarSynchronizedScrollConnection =
        rememberSearchBarSynchronizedScrollConnection(
            listState = resultListState,
            resultListActive = resultListActive,
            currentOffsetPx = { searchBarOffsetPx },
            maxOffsetPx = { searchBarMaxOffsetPx },
            onOffsetPxChange = { searchBarOffsetPx = it },
        )
    val focusManager = LocalFocusManager.current

    LaunchedEffect(
        resultListActive,
        resultListState.canScrollBackward,
        resultListState.canScrollForward,
        searchBarMaxOffsetPx,
    ) {
        val resultListScrollable = resultListState.canScrollBackward || resultListState.canScrollForward
        if (!resultListActive || !resultListScrollable) {
            searchBarOffsetPx = 0f
        } else if (searchBarOffsetPx > searchBarMaxOffsetPx) {
            searchBarOffsetPx = searchBarMaxOffsetPx
        }
    }

    LaunchedEffect(keyboardController) {
        snapshotFlow { searchBarOffsetPx > 0f }
            .collect { isHiding ->
                if (isHiding) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            }
    }

    Scaffold(
        modifier = Modifier.benchmarkAnchorRoot(BenchmarkAnchorContract.SEARCH_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = { padding ->
            val floatingContentPadding = floatingSearchContentPadding(padding)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(searchBarSynchronizedScrollConnection),
            ) {
                content(floatingContentPadding)
                FloatingSearchBar(
                    query = query,
                    isFilterActive = isFilterActive,
                    onQueryChange = onQueryChange,
                    onBackClick = onBackClick,
                    onOpenFilter = onOpenFilter,
                    haptic = haptic,
                    focusRequester = focusRequester,
                    keyboardController = keyboardController,
                    searchBarOffsetPx = searchBarOffsetPx,
                    onMaxOffsetPxChange = { searchBarMaxOffsetPx = it },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatingSearchBar(
    query: String,
    isFilterActive: Boolean,
    onQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onOpenFilter: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    searchBarOffsetPx: Float,
    onMaxOffsetPxChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val morph = rememberSearchInputMorph(isFocused = isFocused)
    val focusManager = LocalFocusManager.current
    Box(
        modifier =
            modifier
                .offset { IntOffset(0, -searchBarOffsetPx.roundToInt()) }
                .fillMaxWidth()
                .onSizeChanged { size -> onMaxOffsetPxChange(size.height.toFloat()) }
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = morph.shape,
            color = morph.containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = morph.tonalElevation,
            shadowElevation = 0.dp,
        ) {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                },
                expanded = true,
                onExpandedChange = {},
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .benchmarkAnchor(BenchmarkAnchorContract.SEARCH_INPUT),
                placeholder = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.back),
                            tint = morph.leadingIconTint,
                        )
                    }
                },
                trailingIcon = {
                    SearchBarTrailingIcons(
                        query = query,
                        isFilterActive = isFilterActive,
                        haptic = haptic,
                        onClearQuery = { onQueryChange("") },
                        onOpenFilter = onOpenFilter,
                    )
                },
                colors =
                    SearchBarDefaults.inputFieldColors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
private fun floatingSearchContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = scaffoldPadding.calculateStartPadding(layoutDirection),
        top = scaffoldPadding.calculateTopPadding() + SEARCH_FLOATING_CONTENT_TOP_PADDING,
        end = scaffoldPadding.calculateEndPadding(layoutDirection),
        bottom = scaffoldPadding.calculateBottomPadding(),
    )
}

private val SEARCH_FLOATING_CONTENT_TOP_PADDING = 80.dp

@Composable
private fun SearchScreenEffects(
    errorMessage: String?,
    focusRequester: FocusRequester,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
}

@Composable
private fun rememberSearchMenuHandler(
    focusManager: androidx.compose.ui.focus.FocusManager,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    showMenu: (MemoMenuSelection) -> Unit,
): (MemoMenuSelection) -> Unit =
    remember(focusManager, keyboardController, showMenu) {
        { menuState ->
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            showMenu(menuState)
        }
    }
