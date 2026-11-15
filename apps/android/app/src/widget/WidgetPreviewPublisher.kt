package com.lomo.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.composeForPreview
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

private const val WIDGET_PREVIEW_SCHEMA_VERSION = 1
private const val WIDGET_PREVIEW_STAMP_FILE_NAME = "widget-preview-stamp.txt"
private const val WIDGET_PREVIEW_TEMP_SUFFIX = ".tmp"

internal enum class WidgetPreviewTarget {
    QUICK_NOTES,
    TASKS,
    INSIGHTS,
    REVIEW,
}

internal enum class WidgetPreviewPublishOutcome {
    UnsupportedSdk,
    UpToDate,
    Published,
    PartialFailure,
}

fun interface WidgetPreviewPublisher {
    suspend fun publishHomeScreenPreviewsIfNeeded()
}

internal fun buildWidgetPreviewStamp(
    versionCode: Long,
    lastUpdateTime: Long,
    localeTag: String,
    nightModeMask: Int,
): String = "$WIDGET_PREVIEW_SCHEMA_VERSION:$versionCode:$lastUpdateTime:$localeTag:$nightModeMask"

internal class WidgetPreviewPublishCoordinator(
    private val sdkInt: Int,
    private val readStamp: suspend () -> String?,
    private val writeStamp: suspend (String) -> Unit,
    private val publishTarget: suspend (WidgetPreviewTarget) -> Boolean,
) {
    suspend fun publishIfNeeded(stamp: String): WidgetPreviewPublishOutcome {
        if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return WidgetPreviewPublishOutcome.UnsupportedSdk
        }
        if (readStamp() == stamp) {
            return WidgetPreviewPublishOutcome.UpToDate
        }
        val allPublished =
            WidgetPreviewTarget.entries.fold(true) { accumulated, target ->
                val published = publishTarget(target)
                accumulated && published
            }
        if (!allPublished) {
            return WidgetPreviewPublishOutcome.PartialFailure
        }
        writeStamp(stamp)
        return WidgetPreviewPublishOutcome.Published
    }
}

class AndroidWidgetPreviewPublisher(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : WidgetPreviewPublisher {
    private val stampFile: File = context.filesDir.resolve(WIDGET_PREVIEW_STAMP_FILE_NAME)

    override suspend fun publishHomeScreenPreviewsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }
        publishOnApi35()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publishOnApi35() {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java) ?: return
        val packageInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        val config = context.resources.configuration
        val localeTag =
            if (config.locales.isEmpty) {
                Locale.getDefault().toLanguageTag()
            } else {
                config.locales[0].toLanguageTag()
            }
        val nightModeMask = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val stamp =
            buildWidgetPreviewStamp(
                versionCode = packageInfo.longVersionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
                localeTag = localeTag,
                nightModeMask = nightModeMask,
            )
        val providersByComponent =
            appWidgetManager
                .getInstalledProvidersForPackage(context.packageName, null)
                .associateBy { it.provider }

        val coordinator =
            WidgetPreviewPublishCoordinator(
                sdkInt = Build.VERSION.SDK_INT,
                readStamp = {
                    withContext(dispatcherProvider.io) {
                        if (stampFile.isFile) stampFile.readText().trim() else null
                    }
                },
                writeStamp = { newStamp ->
                    writeStampAtomically(
                        file = stampFile,
                        value = newStamp,
                        ioDispatcher = dispatcherProvider.io,
                    )
                },
                publishTarget = { target ->
                    val component = ComponentName(context, target.receiverClass())
                    val info = providersByComponent[component]
                    val remoteViews =
                        withContext(dispatcherProvider.default) {
                            target.glanceWidget().composeForPreview(
                                context = context,
                                widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                                info = info,
                            )
                        }
                    appWidgetManager.setWidgetPreview(
                        component,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                        remoteViews,
                    )
                },
            )

        coordinator.publishIfNeeded(stamp)
    }
}

private fun WidgetPreviewTarget.receiverClass(): Class<out AppWidgetProvider> = when (this) {
    WidgetPreviewTarget.QUICK_NOTES -> LomoWidgetReceiver::class.java
    WidgetPreviewTarget.TASKS -> LomoTasksWidgetReceiver::class.java
    WidgetPreviewTarget.INSIGHTS -> LomoInsightsWidgetReceiver::class.java
    WidgetPreviewTarget.REVIEW -> LomoReviewWidgetReceiver::class.java
}

private fun WidgetPreviewTarget.glanceWidget(): GlanceAppWidget = when (this) {
    WidgetPreviewTarget.QUICK_NOTES -> LomoWidget()
    WidgetPreviewTarget.TASKS -> LomoTasksWidget()
    WidgetPreviewTarget.INSIGHTS -> LomoInsightsWidget()
    WidgetPreviewTarget.REVIEW -> LomoReviewWidget()
}

private suspend fun writeStampAtomically(file: File, value: String, ioDispatcher: CoroutineDispatcher) =
    withContext(ioDispatcher) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}$WIDGET_PREVIEW_TEMP_SUFFIX")
        tempFile.writeText(value)
        Files.move(
            tempFile.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
