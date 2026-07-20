package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation

/**
 * Validates a candidate workspace root before freeze + durable selection persistence.
 *
 * Implementations must prove the path exists (Direct) or the SAF grant is resolvable. Blank-only
 * checks are not sufficient production validation.
 */
fun interface WorkspaceCandidateValidator {
    suspend fun validate(location: StorageLocation)
}
