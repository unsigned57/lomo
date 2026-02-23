package com.lomo.app.feature.search

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoEditorSheetHost
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query: String by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchUiModels.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val shareCardStyle = appPreferences.shareCardStyle
    val shareCardShowTime = appPreferences.shareCardShowTime
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()

    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val editorController = rememberMemoEditorController()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    com.lomo.ui.component.menu.MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                editorController.openForEdit(memo)
            }
        },
        onDelete = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                viewModel.deleteMemo(memo)
            }
        },
        onShare = { state ->
            val memo = state.memo as? Memo
            com.lomo.app.util.ShareUtils.shareMemoAsImage(
                context = context,
                content = state.content,
                style = shareCardStyle,
                showTime = shareCardShowTime,
                timestamp = memo?.timestamp,
                tags = memo?.tags.orEmpty(),
                activeDayCount = activeDayCount,
            )
        },
        onLanShare = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                onNavigateToShare(memo.content, memo.timestamp)
            }
        },
    ) { showMenu ->
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        val interactionSource = remember { MutableInteractionSource() }
                        BasicTextField(
                            value = query,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { keyboardController?.hide() },
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            interactionSource = interactionSource,
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text =
                                            androidx.compose.ui.res
                                                .stringResource(R.string.search_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.medium()
                            onBackClick()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription =
                                    androidx.compose.ui.res
                                        .stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                haptic.medium()
                                viewModel.onSearchQueryChanged("")
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription =
                                        androidx.compose.ui.res
                                            .stringResource(R.string.cd_clear_search),
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
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
                            title =
                                androidx.compose.ui.res
                                    .stringResource(R.string.search_empty_initial_title),
                            description =
                                androidx.compose.ui.res
                                    .stringResource(R.string.search_empty_initial_desc),
                        )
                    }

                    searchResults.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title =
                                androidx.compose.ui.res
                                    .stringResource(R.string.search_no_results_title),
                            description =
                                androidx.compose.ui.res
                                    .stringResource(R.string.search_no_results_desc),
                        )
                    }

                    else -> {
                        MemoCardList(
                            memos = searchResults,
                            dateFormat = dateFormat,
                            timeFormat = timeFormat,
                            doubleTapEditEnabled = doubleTapEditEnabled,
                            onMemoEdit = editorController::openForEdit,
                            onShowMenu = showMenu,
                            animation = MemoCardListAnimation.FadeIn,
                            contentPadding = PaddingValues(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }

        MemoEditorSheetHost(
            controller = editorController,
            imageDirectory = imageDirectory,
            onSaveImage = viewModel::saveImage,
            onSubmit = viewModel::updateMemo,
            availableTags = emptyList(),
        )
    }
}
