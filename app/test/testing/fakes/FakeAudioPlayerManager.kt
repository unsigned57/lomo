package com.lomo.app.testing.fakes

import android.content.Context
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import io.mockk.mockk

class FakeAudioPlayerManager(
    context: Context = mockk<Context>(),
    uriResolver: AudioPlaybackResolverRepository = FakeAudioPlaybackResolverRepository(),
) : AudioPlayerManager(context, uriResolver) {

    override fun play(uri: String) {}
    override fun seekTo(positionMs: Long) {}
    override fun pause() {}
    override fun stop() {}
    override fun release() {}
    override fun updateProgress() {}
}

class FakeAudioPlaybackResolverRepository : AudioPlaybackResolverRepository {
    private var rootLocation: StorageLocation? = null
    private var voiceLocation: StorageLocation? = null

    override fun setRootLocation(location: StorageLocation?) {
        rootLocation = location
    }

    override fun setVoiceLocation(location: StorageLocation?) {
        voiceLocation = location
    }

    override suspend fun resolve(source: String): String? {
        return source
    }
}
