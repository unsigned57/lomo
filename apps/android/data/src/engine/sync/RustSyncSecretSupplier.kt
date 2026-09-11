package com.lomo.data.engine.sync

/**
 * Keystore-shaped secret edge for Stage-5 dark sync (P5-09).
 *
 * Reads plaintext secret material from a durable Keystore-backed store, immediately issues a
 * process-local lease via [RemoteSyncRepository], and returns **lease ids only**. Journals,
 * WorkManager inputs, and Sync Center state must never hold plaintext.
 *
 * Not production-wired until P5-13. Process death drops leases (re-issue credentials — not
 * journal restore of secret bytes).
 */
fun interface SecretMaterialSource {
    /**
     * Returns secret bytes for [fieldKey], or null when unset.
     * Implementations wipe/zero buffers when they own them; callers must not journal the array.
     */
    fun readSecretBytes(fieldKey: String): ByteArray?
}

/**
 * Issues / revokes process-local leases for a named credential field.
 */
interface RustSyncSecretSupplier {
    /**
     * Issues a lease for [fieldKey] when secret material is present.
     * @return lease id, or null when the field is unset (not an error).
     */
    fun issueLease(
        fieldKey: String,
        ttlMillis: Long,
    ): RemoteSyncSecretLease?

    fun revokeLease(leaseId: String)
}

/**
 * Dark [RustSyncSecretSupplier] over [SecretMaterialSource] + [RemoteSyncRepository].
 *
 * Never stores lease plaintext; never journals secret arrays.
 */
class KeystoreRustSyncSecretSupplier(
    private val materialSource: SecretMaterialSource,
    private val remoteSync: RemoteSyncRepository,
) : RustSyncSecretSupplier {
    override fun issueLease(
        fieldKey: String,
        ttlMillis: Long,
    ): RemoteSyncSecretLease? {
        require(fieldKey.isNotBlank()) { "credential field key must be non-blank" }
        require(ttlMillis > 0) { "secret lease TTL must be positive" }
        val material = materialSource.readSecretBytes(fieldKey) ?: return null
        require(material.isNotEmpty()) { "secret material for $fieldKey must be non-empty when present" }
        return remoteSync.issueSecretLease(material, ttlMillis)
    }

    override fun revokeLease(leaseId: String) {
        remoteSync.revokeSecretLease(leaseId)
    }
}
