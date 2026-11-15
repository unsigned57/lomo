package com.lomo.app.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import com.lomo.app.MainActivity
import com.lomo.app.TrustedLaunchIntents
import com.lomo.app.WidgetDestination
import com.lomo.domain.model.RecordingDeepLink
import org.koin.core.context.GlobalContext
import timber.log.Timber

internal const val EXTRA_WIDGET_LAUNCH_KIND = "com.lomo.app.widget.EXTRA_LAUNCH_KIND"
internal const val EXTRA_WIDGET_LAUNCH_DESTINATION = "com.lomo.app.widget.EXTRA_LAUNCH_DESTINATION"
internal const val EXTRA_WIDGET_LAUNCH_MEMO_ID = "com.lomo.app.widget.EXTRA_LAUNCH_MEMO_ID"

internal const val WIDGET_TARGET_CREATE_MEMO = "create_memo"
internal const val WIDGET_TARGET_START_RECORDING = "start_recording"
internal const val WIDGET_TARGET_OPEN_DESTINATION = "open_destination"
internal const val WIDGET_TARGET_OPEN_MEMO = "open_memo"
internal const val WIDGET_TARGET_DEFAULT_MAIN = "default_main"

private const val ACTION_WIDGET_LAUNCH = "com.lomo.app.widget.ACTION_WIDGET_LAUNCH"
private const val WIDGET_LAUNCH_URI_PREFIX = "lomo://widget-launch/"

internal sealed interface WidgetLaunchTarget {
    val routeKey: String

    data object CreateMemo : WidgetLaunchTarget {
        override val routeKey: String = WIDGET_TARGET_CREATE_MEMO
    }

    data object StartRecording : WidgetLaunchTarget {
        override val routeKey: String = WIDGET_TARGET_START_RECORDING
    }

    data class OpenDestination(val destination: WidgetDestination) : WidgetLaunchTarget {
        override val routeKey: String = "$WIDGET_TARGET_OPEN_DESTINATION/${destination.name}"
    }

    data class OpenMemo(val memoId: String) : WidgetLaunchTarget {
        override val routeKey: String = "$WIDGET_TARGET_OPEN_MEMO/$memoId"
    }

    data object DefaultMain : WidgetLaunchTarget {
        override val routeKey: String = WIDGET_TARGET_DEFAULT_MAIN
    }
}

internal fun resolveWidgetLaunchTarget(intent: Intent?): WidgetLaunchTarget {
    val kind = intent?.getStringExtra(EXTRA_WIDGET_LAUNCH_KIND) ?: return WidgetLaunchTarget.DefaultMain
    return when (kind) {
        WIDGET_TARGET_CREATE_MEMO -> WidgetLaunchTarget.CreateMemo

        WIDGET_TARGET_START_RECORDING -> WidgetLaunchTarget.StartRecording

        WIDGET_TARGET_OPEN_DESTINATION -> {
            val destinationName = intent.getStringExtra(EXTRA_WIDGET_LAUNCH_DESTINATION)
            val destination = WidgetDestination.entries.firstOrNull { it.name == destinationName }
            if (destination != null) {
                WidgetLaunchTarget.OpenDestination(destination)
            } else {
                WidgetLaunchTarget.DefaultMain
            }
        }

        WIDGET_TARGET_OPEN_MEMO -> {
            val memoId = intent.getStringExtra(EXTRA_WIDGET_LAUNCH_MEMO_ID)?.takeIf { it.isNotBlank() }
            if (memoId != null) {
                WidgetLaunchTarget.OpenMemo(memoId)
            } else {
                WidgetLaunchTarget.DefaultMain
            }
        }

        else -> WidgetLaunchTarget.DefaultMain
    }
}

internal fun buildWidgetTargetMainIntent(
    target: WidgetLaunchTarget,
    createMemo: () -> Intent,
    startRecording: () -> Intent,
    openDestination: (WidgetDestination) -> Intent,
    openMemo: (String) -> Intent,
    defaultMain: () -> Intent,
): Intent = when (target) {
    WidgetLaunchTarget.CreateMemo -> createMemo()
    WidgetLaunchTarget.StartRecording -> startRecording()
    is WidgetLaunchTarget.OpenDestination -> openDestination(target.destination)
    is WidgetLaunchTarget.OpenMemo -> openMemo(target.memoId)
    WidgetLaunchTarget.DefaultMain -> defaultMain()
}

internal fun createWidgetOpenMemoMainIntent(context: Context, memoId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_MEMO
        putExtra(RecordingDeepLink.EXTRA_MEMO_ID, memoId)
        addFlags(WIDGET_MAIN_LAUNCH_FLAGS)
    }

internal fun createWidgetOpenDestinationMainIntent(context: Context, destination: WidgetDestination): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_DESTINATION
        putExtra(MainActivity.EXTRA_WIDGET_DESTINATION, destination.name)
        addFlags(WIDGET_MAIN_LAUNCH_FLAGS)
    }

internal fun createWidgetDefaultMainIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    addCategory(Intent.CATEGORY_LAUNCHER)
    addFlags(WIDGET_MAIN_LAUNCH_FLAGS)
}

private const val WIDGET_MAIN_LAUNCH_FLAGS =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

/**
 * Ephemeral trampoline Activity for home-screen widget taps.
 *
 * Home-screen RemoteViews cache [android.app.PendingIntent] instances for hours or days, whereas
 * [TrustedLaunchIntents] issues single-use, short-TTL signed commands backed by Keystore HMAC.
 * Routing widget clicks here preserves distinct PendingIntent routes via unique data URIs and
 * mints fresh trusted command intents strictly at tap time.
 */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val target = resolveWidgetLaunchTarget(intent)
            val mainIntent = resolveMainIntent(target)
            startActivity(mainIntent)
        } catch (failure: Exception) {
            Timber.w(failure, "Failed to trampoline widget launch target")
            startActivity(createWidgetDefaultMainIntent(this))
        } finally {
            finish()
        }
    }

    private fun resolveMainIntent(target: WidgetLaunchTarget): Intent {
        val trustedIntents =
            GlobalContext.getOrNull()?.getOrNull<TrustedLaunchIntents>()
                ?: TrustedLaunchIntents.create(this)
        return buildWidgetTargetMainIntent(
            target = target,
            createMemo = { trustedIntents.trustedWidgetCreateMemoIntent() },
            startRecording = { trustedIntents.trustedWidgetStartRecordingIntent() },
            openDestination = { destination -> createWidgetOpenDestinationMainIntent(this, destination) },
            openMemo = { memoId -> createWidgetOpenMemoMainIntent(this, memoId) },
            defaultMain = { createWidgetDefaultMainIntent(this) },
        )
    }

    internal companion object {
        fun createIntent(context: Context, target: WidgetLaunchTarget): Intent =
            Intent(context, WidgetLaunchActivity::class.java).apply {
                action = ACTION_WIDGET_LAUNCH
                // PendingIntent.filterEquals ignores extras; encode routeKey in the URI so every
                // widget action and memo target keeps an independent PendingIntent slot.
                data = "$WIDGET_LAUNCH_URI_PREFIX${Uri.encode(target.routeKey)}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                when (target) {
                    WidgetLaunchTarget.CreateMemo ->
                        putExtra(EXTRA_WIDGET_LAUNCH_KIND, WIDGET_TARGET_CREATE_MEMO)

                    WidgetLaunchTarget.StartRecording ->
                        putExtra(EXTRA_WIDGET_LAUNCH_KIND, WIDGET_TARGET_START_RECORDING)

                    is WidgetLaunchTarget.OpenDestination -> {
                        putExtra(EXTRA_WIDGET_LAUNCH_KIND, WIDGET_TARGET_OPEN_DESTINATION)
                        putExtra(EXTRA_WIDGET_LAUNCH_DESTINATION, target.destination.name)
                    }

                    is WidgetLaunchTarget.OpenMemo -> {
                        putExtra(EXTRA_WIDGET_LAUNCH_KIND, WIDGET_TARGET_OPEN_MEMO)
                        putExtra(EXTRA_WIDGET_LAUNCH_MEMO_ID, target.memoId)
                    }

                    WidgetLaunchTarget.DefaultMain ->
                        putExtra(EXTRA_WIDGET_LAUNCH_KIND, WIDGET_TARGET_DEFAULT_MAIN)
                }
            }
    }
}
