package com.lomo.app.feature.settings

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.GitSyncState
import com.lomo.ui.component.dialog.SelectionDialog
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val rootDir by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDir by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val voiceDir by viewModel.voiceDirectory.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticFeedbackEnabled.collectAsStateWithLifecycle()
    val checkUpdates by viewModel.checkUpdatesOnStartup.collectAsStateWithLifecycle()
    val showInputHints by viewModel.showInputHints.collectAsStateWithLifecycle()
    val doubleTapEditEnabled by viewModel.doubleTapEditEnabled.collectAsStateWithLifecycle()
    val filenameFormat by viewModel.storageFilenameFormat.collectAsStateWithLifecycle()
    val timestampFormat by viewModel.storageTimestampFormat.collectAsStateWithLifecycle()
    val shareCardStyle by viewModel.shareCardStyle.collectAsStateWithLifecycle()
    val shareCardShowTime by viewModel.shareCardShowTime.collectAsStateWithLifecycle()
    val shareCardShowBrand by viewModel.shareCardShowBrand.collectAsStateWithLifecycle()
    val lanShareE2eEnabled by viewModel.lanShareE2eEnabled.collectAsStateWithLifecycle()
    val lanSharePairingConfigured by viewModel.lanSharePairingConfigured.collectAsStateWithLifecycle()
    val lanShareDeviceName by viewModel.lanShareDeviceName.collectAsStateWithLifecycle()
    val pairingCodeError by viewModel.pairingCodeError.collectAsStateWithLifecycle()

    val gitSyncEnabled by viewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val gitRemoteUrl by viewModel.gitRemoteUrl.collectAsStateWithLifecycle()
    val gitPatConfigured by viewModel.gitPatConfigured.collectAsStateWithLifecycle()
    val gitAuthorName by viewModel.gitAuthorName.collectAsStateWithLifecycle()
    val gitAuthorEmail by viewModel.gitAuthorEmail.collectAsStateWithLifecycle()
    val gitAutoSyncEnabled by viewModel.gitAutoSyncEnabled.collectAsStateWithLifecycle()
    val gitAutoSyncInterval by viewModel.gitAutoSyncInterval.collectAsStateWithLifecycle()
    val gitLastSyncTime by viewModel.gitLastSyncTime.collectAsStateWithLifecycle()
    val gitSyncState by viewModel.gitSyncState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val haptic = LocalAppHapticFeedback.current

    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFilenameDialog by remember { mutableStateOf(false) }
    var showTimestampDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showShareCardStyleDialog by remember { mutableStateOf(false) }

    var showLanPairingDialog by remember { mutableStateOf(false) }
    var lanPairingCodeInput by remember { mutableStateOf("") }
    var lanPairingCodeVisible by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var deviceNameInput by remember { mutableStateOf("") }

    var showGitRemoteUrlDialog by remember { mutableStateOf(false) }
    var gitRemoteUrlInput by remember { mutableStateOf("") }
    var showGitPatDialog by remember { mutableStateOf(false) }
    var gitPatInput by remember { mutableStateOf("") }
    var gitPatVisible by remember { mutableStateOf(false) }
    var showGitAuthorNameDialog by remember { mutableStateOf(false) }
    var gitAuthorNameInput by remember { mutableStateOf("") }
    var showGitAuthorEmailDialog by remember { mutableStateOf(false) }
    var gitAuthorEmailInput by remember { mutableStateOf("") }
    var showGitSyncIntervalDialog by remember { mutableStateOf(false) }

    val dateFormats = listOf("yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "yyyy/MM/dd")
    val timeFormats = listOf("HH:mm", "hh:mm a", "HH:mm:ss", "hh:mm:ss a")
    val themeModes = listOf("system", "light", "dark")
    val shareCardStyles = listOf("warm", "clean", "dark")
    val filenameFormats = listOf("yyyy_MM_dd", "yyyy-MM-dd", "yyyy.MM.dd", "yyyyMMdd", "MM-dd-yyyy")
    val timestampFormats = listOf("HH:mm:ss", "HH:mm")
    val gitSyncIntervals = listOf("30min", "1h", "6h", "12h", "24h")

    val themeModeLabels =
        mapOf(
            "system" to stringResource(R.string.settings_system),
            "light" to stringResource(R.string.settings_light_mode),
            "dark" to stringResource(R.string.settings_dark_mode),
        )
    val shareCardStyleLabels =
        mapOf(
            "warm" to stringResource(R.string.share_card_style_warm),
            "clean" to stringResource(R.string.share_card_style_clean),
            "dark" to stringResource(R.string.share_card_style_dark),
        )
    val languageLabels =
        mapOf(
            "system" to stringResource(R.string.settings_system),
            "en" to stringResource(R.string.settings_english),
            "zh-CN" to stringResource(R.string.settings_simplified_chinese),
            "zh-Hans-CN" to stringResource(R.string.settings_simplified_chinese),
        )
    val gitSyncIntervalLabels =
        mapOf(
            "30min" to stringResource(R.string.settings_git_sync_interval_30min),
            "1h" to stringResource(R.string.settings_git_sync_interval_1h),
            "6h" to stringResource(R.string.settings_git_sync_interval_6h),
            "12h" to stringResource(R.string.settings_git_sync_interval_12h),
            "24h" to stringResource(R.string.settings_git_sync_interval_24h),
        )

    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val currentLanguageTag =
        if (!currentLocales.isEmpty) {
            currentLocales[0]?.toLanguageTag() ?: "system"
        } else {
            "system"
        }

    val rootLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
                viewModel.updateRootUri(it.toString())
            }
        }

    val imageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
                viewModel.updateImageUri(it.toString())
            }
        }

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
                viewModel.updateVoiceUri(it.toString())
            }
        }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AnimatedContent(
        targetState = currentLanguageTag,
        transitionSpec = {
            (
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationLong2,
                            easing = MotionTokens.EasingEmphasized,
                        ),
                ) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationLong2,
                                easing = MotionTokens.EasingEmphasized,
                            ),
                    )
            ).togetherWith(
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationLong2,
                            easing = MotionTokens.EasingEmphasized,
                        ),
                ),
            )
        },
        label = "SettingsLanguageTransition",
    ) { languageTag ->
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onBackClick()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = AppSpacing.ScreenHorizontalPadding,
                            vertical = AppSpacing.MediumSmall,
                        ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
            ) {
                SettingsGroup(title = stringResource(R.string.settings_group_storage)) {
                    PreferenceItem(
                        title = stringResource(R.string.settings_memo_directory),
                        subtitle = rootDir.ifBlank { stringResource(R.string.settings_not_set) },
                        icon = Icons.Default.Folder,
                        onClick = { rootLauncher.launch(null) },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_image_storage),
                        subtitle = imageDir.ifBlank { stringResource(R.string.settings_not_set) },
                        icon = Icons.Outlined.PhotoLibrary,
                        onClick = { imageLauncher.launch(null) },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_voice_storage),
                        subtitle = voiceDir.ifBlank { stringResource(R.string.settings_not_set) },
                        icon = Icons.Default.Audiotrack,
                        onClick = { voiceLauncher.launch(null) },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_filename_format),
                        subtitle = filenameFormat,
                        icon = Icons.Outlined.Description,
                        onClick = { showFilenameDialog = true },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_timestamp_format),
                        subtitle = timestampFormat,
                        icon = Icons.Outlined.AccessTime,
                        onClick = { showTimestampDialog = true },
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_display)) {
                    PreferenceItem(
                        title = stringResource(R.string.settings_language),
                        subtitle = languageLabels[languageTag] ?: languageTag,
                        icon = Icons.Outlined.Language,
                        onClick = { showLanguageDialog = true },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_theme_mode),
                        subtitle = themeModeLabels[themeMode] ?: themeMode,
                        icon = Icons.Outlined.Brightness6,
                        onClick = { showThemeDialog = true },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_date_format),
                        subtitle = dateFormat,
                        icon = Icons.Outlined.CalendarToday,
                        onClick = { showDateDialog = true },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_time_format),
                        subtitle = timeFormat,
                        icon = Icons.Outlined.Schedule,
                        onClick = { showTimeDialog = true },
                    )
                }

                SettingsGroup(title = stringResource(R.string.share_lan_title)) {
                    SwitchPreferenceItem(
                        title = stringResource(R.string.share_e2e_enabled_title),
                        subtitle = stringResource(R.string.share_e2e_enabled_subtitle),
                        icon = Icons.Default.Lock,
                        checked = lanShareE2eEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateLanShareE2eEnabled(enabled)
                            if (enabled && !lanSharePairingConfigured) {
                                lanPairingCodeInput = ""
                                lanPairingCodeVisible = false
                                viewModel.clearPairingCodeError()
                                showLanPairingDialog = true
                            }
                        },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.settings_lan_share_pairing_code),
                        subtitle =
                            stringResource(
                                if (lanSharePairingConfigured) {
                                    R.string.settings_lan_share_pairing_configured
                                } else {
                                    R.string.settings_lan_share_pairing_not_set
                                },
                            ),
                        icon = Icons.Default.Lock,
                        onClick = {
                            lanPairingCodeVisible = false
                            viewModel.clearPairingCodeError()
                            showLanPairingDialog = true
                        },
                    )
                    SettingsDivider()
                    PreferenceItem(
                        title = stringResource(R.string.share_device_name_label),
                        subtitle = lanShareDeviceName.ifBlank { stringResource(R.string.settings_not_set) },
                        icon = Icons.Outlined.PhoneAndroid,
                        onClick = {
                            deviceNameInput = lanShareDeviceName
                            showDeviceNameDialog = true
                        },
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_share_card)) {
                    PreferenceItem(
                        title = stringResource(R.string.settings_share_card_style),
                        subtitle = shareCardStyleLabels[shareCardStyle] ?: shareCardStyle,
                        icon = Icons.Outlined.Description,
                        onClick = { showShareCardStyleDialog = true },
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_share_card_show_time),
                        subtitle = stringResource(R.string.settings_share_card_show_time_subtitle),
                        icon = Icons.Outlined.Schedule,
                        checked = shareCardShowTime,
                        onCheckedChange = { viewModel.updateShareCardShowTime(it) },
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_share_card_show_brand),
                        subtitle = stringResource(R.string.settings_share_card_show_brand_subtitle),
                        icon = Icons.Outlined.Info,
                        checked = shareCardShowBrand,
                        onCheckedChange = { viewModel.updateShareCardShowBrand(it) },
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_git_sync)) {
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_git_sync_enable),
                        subtitle = stringResource(R.string.settings_git_sync_enable_subtitle),
                        icon = Icons.Outlined.Sync,
                        checked = gitSyncEnabled,
                        onCheckedChange = { viewModel.updateGitSyncEnabled(it) },
                    )
                    if (gitSyncEnabled) {
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_remote_url),
                            subtitle = gitRemoteUrl.ifBlank { stringResource(R.string.settings_not_set) },
                            icon = Icons.Outlined.Link,
                            onClick = {
                                gitRemoteUrlInput = gitRemoteUrl
                                showGitRemoteUrlDialog = true
                            },
                        )
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_pat),
                            subtitle =
                                stringResource(
                                    if (gitPatConfigured) {
                                        R.string.settings_git_pat_configured
                                    } else {
                                        R.string.settings_git_pat_not_set
                                    },
                                ),
                            icon = Icons.Default.Lock,
                            onClick = {
                                gitPatInput = ""
                                gitPatVisible = false
                                showGitPatDialog = true
                            },
                        )
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_author_name),
                            subtitle = gitAuthorName.ifBlank { stringResource(R.string.settings_not_set) },
                            icon = Icons.Outlined.Person,
                            onClick = {
                                gitAuthorNameInput = gitAuthorName
                                showGitAuthorNameDialog = true
                            },
                        )
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_author_email),
                            subtitle = gitAuthorEmail.ifBlank { stringResource(R.string.settings_not_set) },
                            icon = Icons.Outlined.Email,
                            onClick = {
                                gitAuthorEmailInput = gitAuthorEmail
                                showGitAuthorEmailDialog = true
                            },
                        )
                        SettingsDivider()
                        SwitchPreferenceItem(
                            title = stringResource(R.string.settings_git_auto_sync),
                            subtitle = stringResource(R.string.settings_git_auto_sync_subtitle),
                            icon = Icons.Outlined.Schedule,
                            checked = gitAutoSyncEnabled,
                            onCheckedChange = { viewModel.updateGitAutoSyncEnabled(it) },
                        )
                        if (gitAutoSyncEnabled) {
                            SettingsDivider()
                            PreferenceItem(
                                title = stringResource(R.string.settings_git_sync_interval),
                                subtitle = gitSyncIntervalLabels[gitAutoSyncInterval] ?: gitAutoSyncInterval,
                                icon = Icons.Outlined.Schedule,
                                onClick = { showGitSyncIntervalDialog = true },
                            )
                        }
                        SettingsDivider()
                        PreferenceItem(
                            title = stringResource(R.string.settings_git_sync_now),
                            subtitle = gitSyncNowSubtitle(gitSyncState, gitLastSyncTime),
                            icon = Icons.Outlined.Sync,
                            onClick = {
                                if (gitSyncState !is GitSyncState.Syncing &&
                                    gitSyncState !is GitSyncState.Initializing
                                ) {
                                    viewModel.triggerGitSyncNow()
                                }
                            },
                        )
                    }
                }

                SettingsGroup(title = stringResource(R.string.settings_group_interaction)) {
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_haptic_feedback),
                        subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
                        icon = Icons.Default.Vibration,
                        checked = hapticEnabled,
                        onCheckedChange = { viewModel.updateHapticFeedback(it) },
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_show_input_hints),
                        subtitle = stringResource(R.string.settings_show_input_hints_subtitle),
                        icon = Icons.Outlined.Info,
                        checked = showInputHints,
                        onCheckedChange = { viewModel.updateShowInputHints(it) },
                    )
                    SettingsDivider()
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_double_tap_edit),
                        subtitle = stringResource(R.string.settings_double_tap_edit_subtitle),
                        icon = Icons.Outlined.Info,
                        checked = doubleTapEditEnabled,
                        onCheckedChange = { viewModel.updateDoubleTapEditEnabled(it) },
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_system)) {
                    SwitchPreferenceItem(
                        title = stringResource(R.string.settings_check_updates),
                        subtitle = stringResource(R.string.settings_check_updates_subtitle),
                        icon = Icons.Outlined.Schedule,
                        checked = checkUpdates,
                        onCheckedChange = { viewModel.updateCheckUpdatesOnStartup(it) },
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_about)) {
                    PreferenceItem(
                        title = stringResource(R.string.settings_github),
                        subtitle = stringResource(R.string.settings_github_subtitle),
                        icon = Icons.Outlined.Info,
                        onClick = { uriHandler.openUri("https://github.com/unsigned57/lomo") },
                    )
                }
            }
        }
    }

    if (showDateDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_date_format),
            options = dateFormats,
            currentSelection = dateFormat,
            onDismiss = { showDateDialog = false },
            onSelect = {
                viewModel.updateDateFormat(it)
                showDateDialog = false
            },
        )
    }

    if (showTimeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_time_format),
            options = timeFormats,
            currentSelection = timeFormat,
            onDismiss = { showTimeDialog = false },
            onSelect = {
                viewModel.updateTimeFormat(it)
                showTimeDialog = false
            },
        )
    }

    if (showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_theme),
            options = themeModes,
            currentSelection = themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                viewModel.updateThemeMode(it)
                showThemeDialog = false
            },
            labelProvider = { themeModeLabels[it] ?: it },
        )
    }

    if (showLanguageDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_language),
            options = listOf("system", "zh-CN", "en"),
            currentSelection = currentLanguageTag,
            onDismiss = { showLanguageDialog = false },
            onSelect = { tag ->
                val locales =
                    if (tag == "system") {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                AppCompatDelegate.setApplicationLocales(locales)
                showLanguageDialog = false
            },
            labelProvider = { languageLabels[it] ?: it },
        )
    }

    if (showFilenameDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_filename_format),
            options = filenameFormats,
            currentSelection = filenameFormat,
            onDismiss = { showFilenameDialog = false },
            onSelect = {
                viewModel.updateStorageFilenameFormat(it)
                showFilenameDialog = false
            },
        )
    }

    if (showTimestampDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_timestamp_format),
            options = timestampFormats,
            currentSelection = timestampFormat,
            onDismiss = { showTimestampDialog = false },
            onSelect = {
                viewModel.updateStorageTimestampFormat(it)
                showTimestampDialog = false
            },
        )
    }

    if (showShareCardStyleDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_select_share_card_style),
            options = shareCardStyles,
            currentSelection = shareCardStyle,
            onDismiss = { showShareCardStyleDialog = false },
            onSelect = {
                viewModel.updateShareCardStyle(it)
                showShareCardStyleDialog = false
            },
            labelProvider = { shareCardStyleLabels[it] ?: it },
        )
    }

    if (showLanPairingDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.clearPairingCodeError()
                showLanPairingDialog = false
            },
            title = { Text(stringResource(R.string.settings_lan_share_pairing_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_lan_share_pairing_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = lanPairingCodeInput,
                        onValueChange = {
                            lanPairingCodeInput = it
                            if (pairingCodeError != null) {
                                viewModel.clearPairingCodeError()
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_lan_share_pairing_hint)) },
                        visualTransformation =
                            if (lanPairingCodeVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            TextButton(onClick = { lanPairingCodeVisible = !lanPairingCodeVisible }) {
                                Text(
                                    text =
                                        if (lanPairingCodeVisible) {
                                            stringResource(R.string.share_password_hide)
                                        } else {
                                            stringResource(R.string.share_password_show)
                                        },
                                )
                            }
                        },
                        isError = pairingCodeError != null,
                        supportingText =
                            pairingCodeError?.let {
                                {
                                    Text(localizePairingCodeError(it))
                                }
                            },
                    )
                    if (lanSharePairingConfigured) {
                        TextButton(
                            onClick = {
                                viewModel.clearLanSharePairingCode()
                                showLanPairingDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.action_clear_pairing_code))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateLanSharePairingCode(lanPairingCodeInput)
                        if (lanPairingCodeInput.trim().length in 6..64) {
                            showLanPairingDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPairingCodeError()
                        showLanPairingDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeviceNameDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text(stringResource(R.string.share_device_name_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.share_device_name_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { deviceNameInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.share_device_name_label)) },
                    )
                    TextButton(
                        onClick = {
                            viewModel.updateLanShareDeviceName("")
                            showDeviceNameDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.share_device_name_use_system))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateLanShareDeviceName(deviceNameInput)
                        showDeviceNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showGitRemoteUrlDialog) {
        AlertDialog(
            onDismissRequest = { showGitRemoteUrlDialog = false },
            title = { Text(stringResource(R.string.settings_git_remote_url)) },
            text = {
                OutlinedTextField(
                    value = gitRemoteUrlInput,
                    onValueChange = { gitRemoteUrlInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_remote_url_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateGitRemoteUrl(gitRemoteUrlInput.trim())
                        showGitRemoteUrlDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGitRemoteUrlDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showGitPatDialog) {
        AlertDialog(
            onDismissRequest = { showGitPatDialog = false },
            title = { Text(stringResource(R.string.settings_git_pat_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_git_pat_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = gitPatInput,
                        onValueChange = { gitPatInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_git_pat_hint)) },
                        visualTransformation =
                            if (gitPatVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            TextButton(onClick = { gitPatVisible = !gitPatVisible }) {
                                Text(
                                    text =
                                        if (gitPatVisible) {
                                            stringResource(R.string.share_password_hide)
                                        } else {
                                            stringResource(R.string.share_password_show)
                                        },
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateGitPat(gitPatInput.trim())
                        showGitPatDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGitPatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showGitAuthorNameDialog) {
        AlertDialog(
            onDismissRequest = { showGitAuthorNameDialog = false },
            title = { Text(stringResource(R.string.settings_git_author_name)) },
            text = {
                OutlinedTextField(
                    value = gitAuthorNameInput,
                    onValueChange = { gitAuthorNameInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_author_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateGitAuthorName(gitAuthorNameInput.trim())
                        showGitAuthorNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGitAuthorNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showGitAuthorEmailDialog) {
        AlertDialog(
            onDismissRequest = { showGitAuthorEmailDialog = false },
            title = { Text(stringResource(R.string.settings_git_author_email)) },
            text = {
                OutlinedTextField(
                    value = gitAuthorEmailInput,
                    onValueChange = { gitAuthorEmailInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_git_author_email_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateGitAuthorEmail(gitAuthorEmailInput.trim())
                        showGitAuthorEmailDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGitAuthorEmailDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showGitSyncIntervalDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_git_select_sync_interval),
            options = gitSyncIntervals,
            currentSelection = gitAutoSyncInterval,
            onDismiss = { showGitSyncIntervalDialog = false },
            onSelect = {
                viewModel.updateGitAutoSyncInterval(it)
                showGitSyncIntervalDialog = false
            },
            labelProvider = { gitSyncIntervalLabels[it] ?: it },
        )
    }
}

@Composable
private fun gitSyncNowSubtitle(state: GitSyncState, lastSyncTime: Long): String =
    when (state) {
        is GitSyncState.Syncing -> stringResource(R.string.settings_git_sync_status_syncing)
        is GitSyncState.Initializing -> stringResource(R.string.settings_git_sync_status_initializing)
        is GitSyncState.Error ->
            stringResource(
                R.string.settings_git_sync_status_error,
                localizeGitSyncErrorMessage(state.message),
            )
        is GitSyncState.NotConfigured -> stringResource(R.string.settings_git_sync_status_not_configured)
        else -> {
            if (lastSyncTime > 0) {
                val relative = DateUtils.getRelativeTimeSpanString(
                    lastSyncTime,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
                stringResource(R.string.settings_git_sync_now_subtitle, relative)
            } else {
                stringResource(R.string.settings_git_sync_never)
            }
        }
    }

@Composable
private fun localizeGitSyncErrorMessage(message: String): String =
    if (message.startsWith("Git sync requires direct path mode", ignoreCase = true)) {
        stringResource(R.string.settings_git_sync_direct_path_required)
    } else {
        message
    }

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun localizePairingCodeError(raw: String): String {
    val detail = raw.trim()
    return when {
        detail.equals("Pairing code must be 6-64 characters", ignoreCase = true) -> {
            stringResource(R.string.share_error_invalid_pairing_code)
        }

        detail.equals("Invalid password", ignoreCase = true) -> {
            stringResource(R.string.share_error_invalid_pairing_code)
        }

        detail.isBlank() -> {
            stringResource(R.string.share_error_unknown)
        }

        else -> {
            detail
        }
    }
}
