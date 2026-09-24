package com.lomo.domain.repository

import com.lomo.domain.model.PreferencesCorruptionNotice
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes preference-store health facts. Currently the only fact is a corruption quarantine;
 * the notice stays published until the user-facing surface acknowledges it. Quarantined evidence
 * is never deleted by acknowledgement.
 */
interface PreferencesHealthRepository {
    val corruptionNotice: StateFlow<PreferencesCorruptionNotice?>

    fun acknowledgeCorruptionNotice()
}
