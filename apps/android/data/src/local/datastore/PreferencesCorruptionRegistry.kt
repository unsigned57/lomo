package com.lomo.data.local.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lomo.domain.model.PreferencesCorruptionNotice
import com.lomo.domain.repository.PreferencesHealthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Publishes preference-store health facts. [shared] is the process-wide instance wired into the
 * application DataStore; tests construct their own.
 */
class PreferencesCorruptionRegistry : PreferencesHealthRepository {
    private val published = MutableStateFlow<PreferencesCorruptionNotice?>(null)

    override val corruptionNotice: StateFlow<PreferencesCorruptionNotice?> = published.asStateFlow()

    fun publish(notice: PreferencesCorruptionNotice) {
        published.value = notice
    }

    override fun acknowledgeCorruptionNotice() {
        published.value = null
    }

    companion object {
        val shared = PreferencesCorruptionRegistry()
    }
}

/**
 * Quarantines a corrupted preferences file as evidence instead of letting it be silently
 * overwritten. The store is then rebuilt on [emptyPreferences] — the running session sees
 * defaults — and a [PreferencesCorruptionNotice] is published so the app can present recovery.
 * Acknowledging the notice never deletes the quarantined file.
 */
internal fun lomoPreferencesCorruptionHandler(
    corruptionFile: () -> File,
    registry: PreferencesCorruptionRegistry,
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { ex ->
        val file = corruptionFile()
        val quarantined =
            File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        val evidenceName =
            if (file.exists() && file.renameTo(quarantined)) {
                quarantined.name
            } else {
                // The file could not be moved; delete it so the store can be rebuilt and
                // record that no evidence survived.
                file.delete()
                file.name
            }
        registry.publish(
            PreferencesCorruptionNotice(
                quarantinedFileName = evidenceName,
                diagnostic = ex.message ?: ex.javaClass.simpleName.orEmpty(),
            ),
        )
        emptyPreferences()
    }
