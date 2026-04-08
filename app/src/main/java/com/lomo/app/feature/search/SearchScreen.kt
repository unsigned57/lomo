package com.lomo.app.feature.search

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.R
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private data class SearchScreenUiSnapshot(
    val query: String,
    val showLoading: Boolean,
    val searchResults: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    val dateFormat: String,
    val timeFormat: String,
    val shareCardShowTime: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val activeDayCount: Int,
    val imageDirectory: String?,
    val errorMessage: String?,
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState = collectSearchScreenUiSnapshot(viewModel)
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    SearchScreenEffects(
        errorMessage = uiState.errorMessage,
        focusRequester = focusRequester,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    MemoInteractionHost(
        shareCardShowTime = uiState.shareCardShowTime,
        activeDayCount = uiState.activeDayCount,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        imageDirectory = uiState.imageDirectory,
        onLanShare = onNavigateToShare,
    ) { showMenu, openEditor ->
        val onShowSearchMenu =
            rememberSearchMenuHandler(
                focusManager = focusManager,
                keyboardController = keyboardController,
                showMenu = showMenu,
            )
        SearchScreenScaffold(
            query = uiState.query,
            onBackClick = onBackClick,
            onQueryChange = viewModel::onSearchQueryChanged,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            focusRequester = focusRequester,
            keyboardController = keyboardController,
            snackbarHostState = snackbarHostState,
        ) { padding ->
            SearchScreenContent(
                query = uiState.query,
                showLoading = uiState.showLoading,
                searchResults = uiState.searchResults,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
                doubleTapEditEnabled = uiState.doubleTapEditEnabled,
                freeTextCopyEnabled = uiState.freeTextCopyEnabled,
                padding = padding,
                onOpenEditor = openEditor,
                onShowMenu = onShowSearchMenu,
            )
        }
    }
}

@Composable
private fun collectSearchScreenUiSnapshot(viewModel: SearchViewModel): SearchScreenUiSnapshot {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showLoading by viewModel.showLoading.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchUiModels.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    return SearchScreenUiSnapshot(
        query = query,
        showLoading = showLoading,
        searchResults = searchResults.toImmutableList(),
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        shareCardShowTime = appPreferences.shareCardShowTime,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        activeDayCount = activeDayCount,
        imageDirectory = imageDirectory,
        errorMessage = errorMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenScaffold(
    query: String,
    onBackClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    snackbarHostState: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier =
            Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .benchmarkAnchorRoot(BenchmarkAnchorContract.SEARCH_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SearchTopBar(
                query = query,
                onQueryChange = onQueryChange,
                onBackClick = onBackClick,
                haptic = haptic,
                scrollBehavior = scrollBehavior,
                focusRequester = focusRequester,
                keyboardController = keyboardController,
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    TopAppBar(
        title = {
            SearchQueryField(
                query = query,
                onQueryChange = onQueryChange,
                focusRequester = focusRequester,
                keyboardController = keyboardController,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = {
                    haptic.medium()
                    onBackClick()
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = {
                        haptic.medium()
                        onQueryChange("")
                    },
                    modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SEARCH_CLEAR),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_clear_search),
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        scrollBehavior = scrollBehavior,
    )
}

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
    showMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
): (com.lomo.ui.component.menu.MemoMenuState) -> Unit =
    remember(focusManager, keyboardController, showMenu) {
        { menuState ->
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            showMenu(menuState)
        }
    }

@Composable
private fun SearchQueryField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    val interactionSource = remember { MutableInteractionSource() }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .benchmarkAnchor(BenchmarkAnchorContract.SEARCH_INPUT),
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            if (query.isEmpty()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            innerTextField()
        },
    )
}

@Composable
private fun SearchScreenContent(
    query: String,
    showLoading: Boolean,
    searchResults: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    padding: PaddingValues,
    onOpenEditor: (com.lomo.domain.model.Memo) -> Unit,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        when {
            query.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_desc),
                )
            }

            showLoading -> {
                SearchLoadingState()
            }

            searchResults.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_no_results_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_no_results_desc),
                )
            }

            else -> {
                MemoCardList(
                    memos = searchResults,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onMemoEdit = onOpenEditor,
                    onShowMenu = onShowMenu,
                    animation = MemoCardListAnimation.None,
                    contentPadding =
                        PaddingValues(
                            top = AppSpacing.Medium,
                            start = AppSpacing.Medium,
                            end = AppSpacing.Medium,
                            bottom = AppSpacing.Medium,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SearchLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveLoadingIndicator(modifier = Modifier.size(56.dp))
    }
}
