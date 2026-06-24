package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.AppPreferenceSnapshot
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesSnapshotRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : AppPreferencesSnapshotRepository {
        override fun observeAppPreferenceSnapshot(): Flow<AppPreferenceSnapshot> {
            val descriptors = SettingsCatalog.descriptorsFor(SettingsReadModel.APP_PREFERENCES)
            val fieldValueFlows =
                descriptors.map { descriptor ->
                    dataStore.settingValueFlow(descriptor).map { value ->
                        descriptor.snapshotField to value
                    }
                }

            return combine(fieldValueFlows) { values ->
                SettingsCatalog.appPreferenceSnapshotFrom(values.toMap())
            }
        }
    }
