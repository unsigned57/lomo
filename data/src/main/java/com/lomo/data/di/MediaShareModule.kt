package com.lomo.data.di

import com.lomo.data.media.AudioPlaybackUriResolverImpl
import com.lomo.data.media.AudioRecorder
import com.lomo.data.share.ShareServiceManager
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.VoiceRecordingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaShareModule {
    @Provides
    @Singleton
    fun provideVoiceRecorder(
        audioRecorder: AudioRecorder,
    ): VoiceRecordingRepository = audioRecorder

    @Provides
    @Singleton
    fun provideAudioPlaybackUriResolver(
        impl: AudioPlaybackUriResolverImpl,
    ): AudioPlaybackResolverRepository = impl

    @Provides
    @Singleton
    fun provideLanShareService(
        impl: ShareServiceManager,
    ): LanShareService = impl
}
