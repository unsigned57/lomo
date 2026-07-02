package com.lomo.data.recording

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingServiceControllerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RecordingServiceController {
        override fun start() {
            val intent = Intent(context, RecordingForegroundService::class.java)
            context.startForegroundService(intent)
        }

        override fun stop() {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
