package com.lomo.data.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** Same-UID, debug-only probe delivery; data owns both the registration and execution. */
internal class PerfProbeReceiver(
    private val measurements: () -> EnginePerfProbe,
    private val probeScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != probeAction(context)) return
        val run = intent.getStringExtra("run")
        if (run == null || !PROBE_RUN_NAME.matches(run)) {
            Timber.tag("PerfProbeReceiver").w("Rejected invalid perf probe run name")
            return
        }
        val pending = goAsync()
        probeScope.launch {
            try {
                val payload = execute(context, intent, run)
                writeResult(context, run, payload)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun execute(context: Context, intent: Intent, run: String): JSONObject =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            errorPayload(run, "perf probes require a debuggable build")
        } else {
            try {
                measurements().execute(intent, run, ioDispatcher)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorPayload(run, error)
            }
        }

    private suspend fun writeResult(context: Context, run: String, payload: JSONObject) = withContext(ioDispatcher) {
        val directory = File(context.filesDir, "perf-probe")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create perf probe directory" }
        File(directory, "$run.json").writeText(payload.toString(2))
    }

    companion object {
        fun probeAction(context: Context): String = context.packageName + ".PERF_PROBE"
    }
}

private val PROBE_RUN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
