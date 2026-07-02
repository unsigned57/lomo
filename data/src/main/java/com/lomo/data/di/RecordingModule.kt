package com.lomo.data.di

import com.lomo.data.recording.RecordingServiceController
import com.lomo.data.recording.RecordingServiceControllerImpl
import com.lomo.data.recording.RecordingSessionImpl
import com.lomo.domain.repository.RecordingSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds
    @Singleton
    abstract fun bindRecordingSession(impl: RecordingSessionImpl): RecordingSession

    @Binds
    @Singleton
    abstract fun bindRecordingServiceController(impl: RecordingServiceControllerImpl): RecordingServiceController
}
