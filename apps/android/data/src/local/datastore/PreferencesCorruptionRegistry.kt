package com.lomo.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lomo.domain.model.PreferencesCorruptionNotice
import com.lomo.domain.repository.PreferencesHealthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Publishes preference-store health facts. [shared] is the process-wide instance wired into the
 * application DataStore; tests construct their own.
 *
 * The corruption witness itself is not kept here: it is a persisted fact of the rebuilt store
 * ([LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS]), written by the corruption handler into the
 * replacement preferences that DataStore commits atomically with the rebuild. Readers therefore
 * derive "destroyed vs. recorded" from the store contents in every process generation, not from
 * a flag that dies with the process.
 *
 * The user-facing notice is transient state: [publish] surfaces it immediately, and
 * [republishPersistedNotice] revives it from the persisted witness when a restart destroyed the
 * in-memory emission before it was seen. [acknowledgeCorruptionNotice] additionally records
 * [LomoDataStoreKeys.PREFERENCES_CORRUPTION_NOTICE_ACKED] so a restart does not re-report a
 * consumed notice.
 */
class PreferencesCorruptionRegistry : PreferencesHealthRepository {
    private val published = MutableStateFlow<PreferencesCorruptionNotice?>(null)

    // behavior-contract: stateful-var-ok: process-owned handle bound once by LomoAppDataStoreHolder
    // when the DataStore is created; durable acknowledgement needs the store that owns the witness.
    @Volatile
    private var witnessStore: DataStore<Preferences>? = null

    override val corruptionNotice: StateFlow<PreferencesCorruptionNotice?> = published.asStateFlow()

    fun publish(notice: PreferencesCorruptionNotice) {
        published.value = notice
    }

    /** Binds the store that owns the persisted witness so acknowledgement can be written durably. */
    internal fun bindWitnessStore(store: DataStore<Preferences>) {
        witnessStore = store
    }

    /**
     * Republishes the persisted notice for a store that was rebuilt after corruption but whose
     * in-memory emission never surfaced (e.g. the process died before collection). Witness-gated
     * readers do not depend on this — they read the sentinel straight from the store.
     */
    internal suspend fun republishPersistedNotice(store: DataStore<Preferences>) {
        bindWitnessStore(store)
        val notice =
            store.data
                .map { prefs -> persistedCorruptionNotice(prefs) }
                .catchOnlyIOException("corruptionNoticeRepublish", null)
                .first()
        if (notice != null) {
            publish(notice)
        }
    }

    override suspend fun acknowledgeCorruptionNotice() {
        published.value = null
        val store = witnessStore ?: return
        try {
            store.editPreferences {
                this[LomoDataStoreKeys.PREFERENCES_CORRUPTION_NOTICE_ACKED] = true
            }
        } catch (error: IOException) {
            // The durable acknowledgement is best-effort: a lost write only re-surfaces the
            // notice once more after restart — it never downgrades the witness itself.
            Timber.tag(LOMO_DATA_STORE_TAG).w(error, "Could not persist corruption-notice acknowledgement")
        }
    }

    companion object {
        val shared = PreferencesCorruptionRegistry()

        internal fun persistedCorruptionNotice(prefs: Preferences): PreferencesCorruptionNotice? {
            if (prefs[LomoDataStoreKeys.PREFERENCES_CORRUPTION_NOTICE_ACKED] == true) {
                return null
            }
            val witness = prefs[LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS] ?: return null
            return PreferencesCorruptionNotice(
                quarantinedFileName = witness.substringBefore('\n'),
                diagnostic = witness.substringAfter('\n', ""),
            )
        }
    }
}

/**
 * True when this store was rebuilt after a witnessed corruption — in any process generation.
 * Readers of keys whose absence is ambiguous ("never recorded" vs "recorded state was
 * destroyed") consult this before collapsing an absent key into a default.
 */
internal fun Preferences.corruptionWitnessed(): Boolean =
    this[LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS] != null

/**
 * Quarantines a corrupted preferences file as evidence instead of letting it be silently
 * overwritten. The store is then rebuilt on preferences that already carry the
 * [LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS] sentinel — DataStore persists the handler's
 * replacement atomically with the rebuild, so every subsequent process generation can still
 * distinguish "key was never recorded" from "recorded state was destroyed". A
 * [PreferencesCorruptionNotice] is published so the app can present recovery. Acknowledging the
 * notice never deletes the quarantined file or the witness.
 *
 * The corrupt bytes are **copied** aside, never removed from the store path: DataStore re-reads
 * the store under its corruption lock and persists this replacement only while the re-read still
 * fails. Removing the file would make the re-read succeed with empty defaults and silently drop
 * the witness. DataStore's own write then atomically replaces the corrupt file with the
 * sentinel-bearing preferences.
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
            try {
                file.copyTo(quarantined, overwrite = false).name
            } catch (error: IOException) {
                // Evidence could not be preserved; the corrupt original stays in place so the
                // persisted replacement still carries the witness sentinel.
                Timber.tag(LOMO_DATA_STORE_TAG).w(error, "Could not quarantine corrupted preferences file")
                file.name
            }
        val diagnostic = ex.message ?: ex.javaClass.simpleName.orEmpty()
        registry.publish(
            PreferencesCorruptionNotice(
                quarantinedFileName = evidenceName,
                diagnostic = diagnostic,
            ),
        )
        mutablePreferencesOf(
            LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS to "$evidenceName\n$diagnostic",
        )
    }
