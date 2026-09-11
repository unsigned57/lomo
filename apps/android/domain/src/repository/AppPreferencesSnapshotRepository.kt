package com.lomo.domain.repository

import com.lomo.domain.model.AppPreferenceSnapshot
import kotlinx.coroutines.flow.Flow

interface AppPreferencesSnapshotRepository {
    fun observeAppPreferenceSnapshot(): Flow<AppPreferenceSnapshot>
}
