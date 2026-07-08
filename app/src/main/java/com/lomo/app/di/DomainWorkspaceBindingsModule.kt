package com.lomo.app.di

import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ExportEncryptedSettingsUseCase
import com.lomo.domain.usecase.ImportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ImportEncryptedSettingsUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import org.koin.dsl.module

val domainWorkspaceModule = module {
    single { BackupSyncConflictFilesUseCase(get()) }
    single { InitializeWorkspaceUseCase(get(), get()) }
    single { SwitchRootStorageUseCase(get(), get()) }
    single { ExportAllNotesArchiveUseCase(get()) }
    single { ImportAllNotesArchiveUseCase(get(), get()) }
    single { ExportEncryptedSettingsUseCase(get()) }
    single { ImportEncryptedSettingsUseCase(get()) }
    single { SaveImageUseCase(get()) }
    single { DiscardMemoDraftAttachmentsUseCase(get()) }
    single { StartupMaintenanceUseCase(get(), get(), get(), get(), get(), get()) }
}
