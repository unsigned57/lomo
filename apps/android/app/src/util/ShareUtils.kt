package com.lomo.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import com.lomo.app.R
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.preferences.CustomFontHost
import com.lomo.app.feature.preferences.ShareCardTypefaces
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.ContrastLevel
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.PersistShareImageUseCase
import com.lomo.ui.theme.TypographyScales
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Application-layer sharing orchestrator.
 *
 * Responsibilities:
 * - dispatch Android share/copy intents
 * - persist rendered share image to cache
 * - delegate card rendering to [ShareCardBitmapRenderer]
 */
class ShareUtils(
    private val persistShareImageUseCase: PersistShareImageUseCase,
    private val shareCardBitmapRenderer: ShareCardBitmapRenderer,
    private val appConfigStateProvider: AppConfigStateProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val customFontHost: CustomFontHost,
) {
    private data class ShareImageConfig(
        val showTime: Boolean,
        val showSignature: Boolean,
        val signatureText: String,
        val timestampMillis: Long?,
        val tags: List<String>,
        val colorSource: ColorSource,
        val contrastLevel: ContrastLevel,
        val themeMode: ThemeMode,
        val typographyScales: TypographyScales,
        val resolvedImagePaths: List<String>,
        val bodyTypefaces: ShareCardTypefaces?,
    )

    suspend fun shareMemoAsImage(
        context: Context,
        content: String,
        title: String? = null,
        showTime: Boolean = true,
        showSignature: Boolean = true,
        signatureText: String = DEFAULT_SHARE_CARD_SIGNATURE,
        timestamp: Long? = null,
        tags: List<String> = emptyList(),
        resolvedImagePaths: List<String> = emptyList(),
    ) {
        try {
            val displayPreferences = appConfigStateProvider.appPreferences.value
            val resolvedTypefaces = customFontHost.shareCardTypefacesOrNull(displayPreferences.fontPreference)
            val imageUri =
                createShareImageUri(
                    context = context,
                    content = content,
                    title = title,
                    config =
                    ShareImageConfig(
                        showTime = showTime,
                        showSignature = showSignature,
                        signatureText = signatureText,
                        timestampMillis = timestamp,
                        tags = tags,
                        colorSource = displayPreferences.colorSource,
                        contrastLevel = displayPreferences.contrastLevel,
                        themeMode = displayPreferences.themeMode,
                        typographyScales = TypographyScales(
                            fontSizeScale = displayPreferences.typographyFontSizeScale,
                            lineHeightScale = displayPreferences.typographyLineHeightScale,
                            letterSpacingScale = displayPreferences.typographyLetterSpacingScale,
                            paragraphSpacingScale = displayPreferences.typographyParagraphSpacingScale,
                        ),
                        resolvedImagePaths = resolvedImagePaths,
                        bodyTypefaces = resolvedTypefaces,
                    ),
                )
            withContext(dispatcherProvider.main) {
                val sendIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        if (!title.isNullOrEmpty()) putExtra(Intent.EXTRA_TITLE, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(context.contentResolver, "memo_image", imageUri)
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(Intent.createChooser(sendIntent, null))
            }
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Exception) {
            Timber.e(throwable, "shareMemoAsImage failed, fallback to text share")
            withContext(dispatcherProvider.main) {
                shareMemoText(context, content, title)
            }
        }
    }

    fun shareMemoText(context: Context, content: String, title: String? = null) {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, content)
                type = "text/plain"
                if (!title.isNullOrEmpty()) putExtra(Intent.EXTRA_TITLE, title)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(Intent.createChooser(sendIntent, null))
    }

    fun copyToClipboard(context: Context, content: String, showToast: Boolean = true) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_memo), content)
        clipboardManager.setPrimaryClip(clip)

        if (showToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.copied_to_clipboard),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    fun shareMemoAsMarkdown(context: Context, content: String, fileName: String) {
        // Markdown shares currently reuse the plain-text path until a dedicated FileProvider flow is introduced.
        shareMemoText(context, content, fileName)
    }

    private suspend fun createShareImageUri(
        context: Context,
        content: String,
        title: String?,
        config: ShareImageConfig,
    ): android.net.Uri {
        val bitmap =
            withContext(dispatcherProvider.default) {
                shareCardBitmapRenderer.render(
                    context = context,
                    content = content,
                    title = title,
                    options =
                    ShareCardRenderOptions(
                        showTime = config.showTime,
                        showSignature = config.showSignature,
                        signatureText = config.signatureText,
                        timestampMillis = config.timestampMillis,
                        tags = config.tags,
                        colorSource = config.colorSource,
                        contrastLevel = config.contrastLevel,
                        themeMode = config.themeMode,
                        typographyScales = config.typographyScales,
                        resolvedImagePaths = config.resolvedImagePaths,
                        bodyTypefaces = config.bodyTypefaces,
                    ),
                )
            }
        val filePath =
            try {
                withContext(dispatcherProvider.io) {
                    persistShareImageUseCase(
                        fileNamePrefix = "memo_share",
                    ) { output ->
                        requireSuccessfulPngEncode(
                            bitmap.compress(
                                android.graphics.Bitmap.CompressFormat.PNG,
                                PNG_COMPRESS_QUALITY,
                                output,
                            ),
                        )
                    }
                }
            } finally {
                bitmap.recycle()
            }

        val file = File(filePath)
        return withContext(dispatcherProvider.default) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }
    }

    private companion object {
        const val PNG_COMPRESS_QUALITY = 100
    }
}

@Composable
fun rememberShareUtils(): ShareUtils = org.koin.compose.koinInject()
