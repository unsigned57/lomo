package com.lomo.app.feature.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ColorScheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.text.LocalSearchHighlightQuery
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlin.math.roundToInt

private data class SearchScreenUiSnapshot(
    val query: String,
    val showLoading: Boolean,
    val searchResults: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    val dateFormat: String,
    val timeFormat: String,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrderForSearch: ImmutableList<String>,
    val inputToolbarToolOrder: ImmutableList<String>,
    val rootDirectory: String?,
    val imageDirectory: String?,
    val imageMap: ImmutableMap<String, android.net.Uri>,
    val deletingMemoIds: ImmutableSet<String>,
    val errorMessage: String?,
)

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
    val uiState = collectSearchScreenUiSnapshot(viewModel)
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val searchResultListState = rememberLazyListState()
    val resultListActive =
        uiState.query.isNotEmpty() &&
            !uiState.showLoading &&
            uiState.searchResults.isNotEmpty()

    SearchScreenEffects(
        errorMessage = uiState.errorMessage,
        focusRequester = focusRequester,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    MemoInteractionHost(
        shareCardShowTime = uiState.shareCardShowTime,
        shareCardShowSignature = uiState.shareCardShowSignature,
        shareCardSignatureText = uiState.shareCardSignatureText,
        dateFormat = uiState.dateFormat,
        timeFormat = uiState.timeFormat,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        rootPath = uiState.rootDirectory,
        imageDirectory = uiState.imageDirectory,
        imageMap = uiState.imageMap,
        onLanShare = if (lanShareEnabled) onNavigateToShare else null,
        memoActionAutoReorderEnabled = uiState.memoActionAutoReorderEnabled,
        memoActionOrder = uiState.memoActionOrderForSearch,
        onMemoActionInvoked = viewModel::recordMemoActionUsage,
        onMemoActionOrderChanged = viewModel.updateMemoActionOrder,
        inputToolbarToolOrder = uiState.inputToolbarToolOrder,
        onInputToolbarToolOrderChanged = viewModel.updateInputToolbarToolOrder,
        onJump = { state ->
            handleMemoJumpToMain(
                state = state,
                requestFocusMemo = onRequestFocusMemo,
                navigateToMain = onNavigateToMain,
            )
        },
        showJump = true,
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
            focusRequester = focusRequester,
            keyboardController = keyboardController,
            snackbarHostState = snackbarHostState,
            resultListState = searchResultListState,
            resultListActive = resultListActive,
        ) { padding ->
            SearchScreenContent(
                query = uiState.query,
                showLoading = uiState.showLoading,
                searchResults = uiState.searchResults,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
                doubleTapEditEnabled = uiState.doubleTapEditEnabled,
                freeTextCopyEnabled = uiState.freeTextCopyEnabled,
                deletingMemoIds = uiState.deletingMemoIds,
                listState = searchResultListState,
                padding = padding,
                onOpenEditor = openEditor,
                onShowMenu = onShowSearchMenu,
                onDeleteAnimationSettled = viewModel::onDeleteAnimationSettled,
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
    val rootDirectory by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    return SearchScreenUiSnapshot(
        query = query,
        showLoading = showLoading,
        searchResults = searchResults.toImmutableList(),
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrderForSearch = appPreferences.memoActionOrderFor(MemoActionOrderScopes.SEARCH),
        inputToolbarToolOrder = appPreferences.inputToolbarToolOrder,
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = remember(imageMap) { imageMap.toImmutableMap() },
        deletingMemoIds = remember(deletingMemoIds) { deletingMemoIds.toImmutableSet() },
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
                    onQueryChange = onQueryChange,
                    onBackClick = onBackClick,
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
    onQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
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
                                contentDescription =
                                    androidx.compose.ui.res.stringResource(R.string.cd_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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

@Composable
private fun searchResultListContentPadding(screenPadding: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = screenPadding.calculateStartPadding(layoutDirection) + AppSpacing.Medium,
        top = screenPadding.calculateTopPadding() + AppSpacing.Medium,
        end = screenPadding.calculateEndPadding(layoutDirection) + AppSpacing.Medium,
        bottom = screenPadding.calculateBottomPadding() + AppSpacing.Medium,
    )
}

private val SEARCH_FLOATING_CONTENT_TOP_PADDING = 80.dp
private const val SEARCH_INPUT_FOCUSED_CORNER_DP = 20
private const val SEARCH_INPUT_RESTING_CORNER_DP = 32

internal data class SearchInputMorphTargets(
    val containerColor: Color,
    val leadingIconTint: Color,
    val cornerRadius: Dp,
    val tonalElevation: Dp,
) {
    companion object {
        fun fromFocus(
            isFocused: Boolean,
            colorScheme: ColorScheme,
        ): SearchInputMorphTargets =
            if (isFocused) {
                SearchInputMorphTargets(
                    containerColor = colorScheme.surfaceContainerHighest,
                    leadingIconTint = colorScheme.primary,
                    cornerRadius = SEARCH_INPUT_FOCUSED_CORNER_DP.dp,
                    tonalElevation = 6.dp,
                )
            } else {
                SearchInputMorphTargets(
                    containerColor = colorScheme.surfaceContainerHigh,
                    leadingIconTint = colorScheme.onSurfaceVariant,
                    cornerRadius = SEARCH_INPUT_RESTING_CORNER_DP.dp,
                    tonalElevation = 3.dp,
                )
            }
    }
}

internal data class SearchInputMorph(
    val containerColor: Color,
    val leadingIconTint: Color,
    val shape: Shape,
    val tonalElevation: Dp,
)

@Composable
internal fun rememberSearchInputMorph(isFocused: Boolean): SearchInputMorph {
    val colorScheme = MaterialTheme.colorScheme
    val targets =
        remember(isFocused, colorScheme) {
            SearchInputMorphTargets.fromFocus(isFocused = isFocused, colorScheme = colorScheme)
        }
    val containerColor by animateColorAsState(
        targetValue = targets.containerColor,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputContainerColor",
    )
    val leadingIconTint by animateColorAsState(
        targetValue = targets.leadingIconTint,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputLeadingIconTint",
    )
    val cornerRadius by animateDpAsState(
        targetValue = targets.cornerRadius,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputCornerRadius",
    )
    val tonalElevation by animateDpAsState(
        targetValue = targets.tonalElevation,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputTonalElevation",
    )
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    return SearchInputMorph(
        containerColor = containerColor,
        leadingIconTint = leadingIconTint,
        shape = shape,
        tonalElevation = tonalElevation,
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
private fun SearchScreenContent(
    query: String,
    showLoading: Boolean,
    searchResults: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    deletingMemoIds: ImmutableSet<String>,
    listState: LazyListState,
    padding: PaddingValues,
    onOpenEditor: (com.lomo.domain.model.Memo) -> Unit,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onDeleteAnimationSettled: (String) -> Unit,
) {
    val resultListContentPadding = searchResultListContentPadding(padding)
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            query.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_desc),
                    modifier = Modifier.padding(padding),
                )
            }

            showLoading -> {
                SearchLoadingState(modifier = Modifier.padding(padding))
            }

            searchResults.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_no_results_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_no_results_desc),
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalSearchHighlightQuery provides query,
                ) {
                    MemoCardList(
                        memos = searchResults,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onMemoEdit = onOpenEditor,
                        onShowMenu = onShowMenu,
                        animation = MemoCardListAnimation.None,
                        deletingMemoIds = deletingMemoIds,
                        onDeleteAnimationSettled = onDeleteAnimationSettled,
                        listState = listState,
                        contentPadding = resultListContentPadding,
                    )
                }
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
