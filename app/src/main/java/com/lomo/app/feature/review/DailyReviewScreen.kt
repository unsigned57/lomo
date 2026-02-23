package com.lomo.app.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.app.feature.memo.MemoEditorSheetHost
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: DailyReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val shareCardStyle = appPreferences.shareCardStyle
    val shareCardShowTime = appPreferences.shareCardShowTime
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val editorController = rememberMemoEditorController()

    MemoMenuBinder(
        shareCardStyle = shareCardStyle,
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        onEditMemo = editorController::openForEdit,
        onDeleteMemo = viewModel::deleteMemo,
        onLanShare = onNavigateToShare,
    ) { showMenu ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            androidx.compose.ui.res
                                .stringResource(R.string.sidebar_daily_review),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription =
                                    androidx.compose.ui.res
                                        .stringResource(R.string.back),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { scaffoldPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = uiState) {
                    is UiState.Loading -> {
                        androidx.compose.material3.CircularProgressIndicator()
                    }

                    is UiState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    is UiState.Success -> {
                        val memos = state.data
                        if (memos.isEmpty()) {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                title =
                                    androidx.compose.ui.res
                                        .stringResource(R.string.review_no_memos_title),
                                description =
                                    androidx.compose.ui.res
                                        .stringResource(R.string.review_no_memos_desc),
                            )
                        } else {
                            val pagerState = rememberPagerState(pageCount = { memos.size })

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                HorizontalPager(
                                    state = pagerState,
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    pageSpacing = 16.dp,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                ) { page ->
                                    val memo = memos[page]
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 32.dp)
                                                    .verticalScroll(rememberScrollState()),
                                        ) {
                                            MemoCardEntry(
                                                uiModel = memo,
                                                dateFormat = dateFormat,
                                                timeFormat = timeFormat,
                                                doubleTapEditEnabled = doubleTapEditEnabled,
                                                onMemoEdit = editorController::openForEdit,
                                                onShowMenu = showMenu,
                                                onImageClick = onNavigateToImage,
                                            )

                                            Text(
                                                text = "${page + 1} / ${memos.size}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 16.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {}
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
