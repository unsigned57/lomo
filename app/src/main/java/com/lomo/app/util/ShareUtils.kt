package com.lomo.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.FileProvider
import com.lomo.app.R
import com.lomo.domain.usecase.PersistShareImageUseCase
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Application-layer sharing orchestrator.
 *
 * Responsibilities:
 * - dispatch Android share/copy intents
 * - persist rendered share image to cache
 * - delegate card rendering to [ShareCardBitmapRenderer]
 */
@Singleton
class ShareUtils
    @Inject
    constructor(
        private val persistShareImageUseCase: PersistShareImageUseCase,
        private val shareCardBitmapRenderer: ShareCardBitmapRenderer,
    ) {
        private data class ShareImageConfig(
            val style: String,
            val showTime: Boolean,
            val timestampMillis: Long?,
            val tags: List<String>,
            val activeDayCount: Int?,
        )

        suspend fun shareMemoAsImage(
            context: Context,
            content: String,
            title: String? = null,
            hostView: View? = null,
            style: String = "warm",
            showTime: Boolean = true,
            timestamp: Long? = null,
            tags: List<String> = emptyList(),
            activeDayCount: Int? = null,
        ) {
            try {
                val imageUri =
                    createShareImageUri(
                        context = context,
                        content = content,
                        title = title,
                        hostView = hostView,
                        config =
                            ShareImageConfig(
                                style = style,
                                showTime = showTime,
                                timestampMillis = timestamp,
                                tags = tags,
                                activeDayCount = activeDayCount,
                            ),
                    )
                withContext(Dispatchers.Main.immediate) {
                    val sendIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            title?.let { putExtra(Intent.EXTRA_TITLE, it) }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(context.contentResolver, "memo_image", imageUri)
                            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(Intent.createChooser(sendIntent, null))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.e(throwable, "shareMemoAsImage failed, fallback to text share")
                withContext(Dispatchers.Main.immediate) {
                    shareMemoText(context, content, title)
                }
            }
        }

        fun shareMemoText(
            context: Context,
            content: String,
            title: String? = null,
        ) {
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, content)
                    type = "text/plain"
                    title?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(Intent.createChooser(sendIntent, null))
        }

        fun copyToClipboard(
            context: Context,
            content: String,
            showToast: Boolean = true,
        ) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Lomo Memo", content)
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

        fun shareMemoAsMarkdown(
            context: Context,
            content: String,
            fileName: String,
        ) {
            // TODO: Create temp file and share via FileProvider.
            shareMemoText(context, content, fileName)
        }

        @Suppress("UNUSED_PARAMETER")
        private suspend fun createShareImageUri(
            context: Context,
            content: String,
            title: String?,
            hostView: View?,
            config: ShareImageConfig,
        ): android.net.Uri {
            val bitmap =
                withContext(Dispatchers.Default) {
                    shareCardBitmapRenderer.render(
                        context = context,
                        content = content,
                        title = title,
                        style = config.style,
                        showTime = config.showTime,
                        timestampMillis = config.timestampMillis,
                        tags = config.tags,
                        activeDayCount = config.activeDayCount,
                    )
                }
            val filePath =
                try {
                    val pngBytes =
                        withContext(Dispatchers.Default) {
                            ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }
                    withContext(Dispatchers.IO) {
                        persistShareImageUseCase(
                            pngBytes = pngBytes,
                            fileNamePrefix = "memo_share",
                        )
                    }
                } finally {
                    bitmap.recycle()
                }

            val file = File(filePath)
            return withContext(Dispatchers.Default) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
        }
    }

val LocalShareUtils =
    staticCompositionLocalOf<ShareUtils> {
        error("ShareUtils not provided")
    }
