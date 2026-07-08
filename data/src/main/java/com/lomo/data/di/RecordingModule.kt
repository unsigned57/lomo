package com.lomo.data.di

import com.lomo.data.recording.RecordingServiceController
import com.lomo.data.recording.RecordingServiceControllerImpl
import com.lomo.data.recording.RecordingSessionImpl
import com.lomo.data.recording.RecordingNotifier
import com.lomo.domain.repository.RecordingSession
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.core.qualifier.named

val recordingModule = module {
    single {
        RecordingSessionImpl(
            appScope = get(named("ApplicationScope")),
            voiceRecordingRepository = get(),
            mediaRepository = get(),
            serviceController = get()
        )
    } bind RecordingSession::class
    single { RecordingServiceControllerImpl(androidContext()) } bind RecordingServiceController::class
    single { RecordingNotifier(androidContext()) }
}
