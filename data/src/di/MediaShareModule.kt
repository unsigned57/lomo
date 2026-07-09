package com.lomo.data.di

import com.lomo.data.media.AudioPlaybackUriResolverImpl
import com.lomo.data.media.AudioRecorder
import com.lomo.data.security.LanShareCredentialStore
import com.lomo.data.share.SharePairingConfig
import com.lomo.data.share.ShareTransferOrchestrator
import com.lomo.data.share.ShareServiceLifecycleController
import com.lomo.data.share.ShareAttachmentStorage
import com.lomo.data.share.ShareIncomingMemoSaver
import com.lomo.data.share.ShareIncomingStateHolder
import com.lomo.data.share.LanShareStateDelegate
import com.lomo.data.share.LanShareLifecycleControllerImpl
import com.lomo.data.share.LanShareTransferControllerImpl
import com.lomo.data.share.LanShareConfigurationControllerImpl
import com.lomo.data.share.ShareServiceManager
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.VoiceRecordingRepository
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

val mediaShareModule = module {
    single { AudioRecorder(androidContext()) } bind VoiceRecordingRepository::class
    singleOf(::AudioPlaybackUriResolverImpl) bind AudioPlaybackResolverRepository::class

    single { LanShareCredentialStore(androidContext()) }
    single { SharePairingConfig(get(), get(), get()) }
    single { ShareTransferOrchestrator(androidContext(), get(), get()) }
    single { ShareServiceLifecycleController(androidContext(), get()) }
    single { ShareAttachmentStorage(androidContext(), get(), get()) }
    single { ShareIncomingMemoSaver(get(), get()) }
    single { ShareIncomingStateHolder() }

    single {
        LanShareStateDelegate(
            lifecycleController = get(),
            transferOrchestrator = get(),
            pairingConfig = get(),
            incomingStateHolder = get(),
            attachmentStorage = get(),
            incomingMemoSaver = get()
        )
    }
    single { LanShareLifecycleControllerImpl(get(), get()) }
    single {
        LanShareTransferControllerImpl(
            lifecycleController = get(),
            transferOrchestrator = get(),
            incomingStateHolder = get(),
            pairingConfig = get()
        )
    }
    single { LanShareConfigurationControllerImpl(get(), get()) }

    single {
        ShareServiceManager(
            stateRepository = get(),
            lifecycleController = get(),
            transferController = get(),
            configurationController = get()
        )
    } bind LanShareService::class
}
