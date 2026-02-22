package com.lomo.app.feature.search

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.util.formatAsDateTime
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query: String by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchUiModels.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val shareCardStyle by viewModel.shareCardStyle.collectAsStateWithLifecycle()
    val shareCardShowTime by viewModel.shareCardShowTime.collectAsStateWithLifecycle()
    val doubleTapEditEnabled by viewModel.doubleTapEditEnabled.collectAsStateWithLifecycle()
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()

    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var showInputSheet by remember { mutableStateOf(false) }
    var editingMemo by remember { mutableStateOf<Memo?>(null) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun appendImageMarkdown(path: String) {
        val markdown = "![image]($path)"
        val current = inputText.text
        val newText = if (current.isEmpty()) markdown else "$current\n$markdown"
        inputText = TextFieldValue(newText, TextRange(newText.length))
    }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.saveImage(it, ::appendImageMarkdown) }
        }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            val file = pendingCameraFile
            val uri = pendingCameraUri
            if (isSuccess && uri != null) {
                viewModel.saveImage(
                    uri = uri,
                    onResult = { path ->
                        appendImageMarkdown(path)
                        runCatching { file?.delete() }
                        pendingCameraFile = null
                        pendingCameraUri = null
                    },
                    onError = {
                        runCatching { file?.delete() }
                        pendingCameraFile = null
                        pendingCameraUri = null
                    },
                )
            } else {
                runCatching { file?.delete() }
                pendingCameraFile = null
                pendingCameraUri = null
            }
        }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    com.lomo.ui.component.menu.MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                editingMemo = memo
                inputText = TextFieldValue(memo.content, TextRange(memo.content.length))
                showInputSheet = true
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
                        LazyColumn(
                            contentPadding = PaddingValues(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = searchResults,
                                key = { it.memo.id },
                                contentType = { "memo" },
                            ) { uiModel ->
                                val memo = uiModel.memo
                                Box(
                                    modifier =
                                        Modifier
                                            .animateItem(
                                                fadeInSpec =
                                                    keyframes {
                                                        durationMillis = 1000
                                                        0f at 0
                                                        0f at com.lomo.ui.theme.MotionTokens.DurationLong2
                                                        1f at 1000 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                                    },
                                                fadeOutSpec = snap(),
                                                placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                            ),
                                ) {
                                    MemoCard(
                                        content = memo.content,
                                        processedContent = uiModel.processedContent,
                                        precomputedNode = uiModel.markdownNode,
                                        timestamp = memo.timestamp,
                                        dateFormat = dateFormat,
                                        timeFormat = timeFormat,
                                        tags = uiModel.tags,
                                        onDoubleClick =
                                            if (doubleTapEditEnabled) {
                                                {
                                                    editingMemo = memo
                                                    inputText = TextFieldValue(memo.content, TextRange(memo.content.length))
                                                    showInputSheet = true
                                                }
                                            } else {
                                                null
                                            },
                                        onMenuClick = {
                                            showMenu(
                                                com.lomo.ui.component.menu.MemoMenuState(
                                                    wordCount = memo.content.length,
                                                    createdTime = memo.timestamp.formatAsDateTime(dateFormat, timeFormat),
                                                    content = memo.content,
                                                    memo = memo,
                                                ),
                                            )
                                        },
                                        menuContent = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showInputSheet) {
            com.lomo.ui.component.input.InputSheet(
                inputValue = inputText,
                onInputValueChange = { inputText = it },
                onDismiss = {
                    showInputSheet = false
                    editingMemo = null
                    inputText = TextFieldValue("")
                },
                onSubmit = { content ->
                    editingMemo?.let { viewModel.updateMemo(it, content) }
                    showInputSheet = false
                    editingMemo = null
                    inputText = TextFieldValue("")
                },
                onImageClick = {
                    if (imageDirectory == null) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.settings_not_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                    } else {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                },
                onCameraClick = {
                    if (imageDirectory == null) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.settings_not_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                    } else {
                        runCatching {
                            val (file, uri) =
                                com.lomo.app.util.CameraCaptureUtils
                                    .createTempCaptureUri(context)
                            pendingCameraFile = file
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }.onFailure {
                            runCatching { pendingCameraFile?.delete() }
                            pendingCameraFile = null
                            pendingCameraUri = null
                        }
                    }
                },
                availableTags = emptyList(),
            )
        }
    }
}
