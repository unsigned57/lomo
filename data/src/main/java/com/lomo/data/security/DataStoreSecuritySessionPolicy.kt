package com.lomo.data.security

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.flow.first


class DataStoreSecuritySessionPolicy(
    private val dataStore: LomoDataStore,
) : SecuritySessionPolicy,
    SecuritySessionController {
        @Volatile
        private var appLockSessionUnlocked: Boolean = false

        override suspend fun authorizeCredentialRead(): CredentialReadAuthorization {
            val appLockEnabled = dataStore.appLockEnabled.first()
            return if (!appLockEnabled || appLockSessionUnlocked) {
                CredentialReadAuthorization.Authorized
            } else {
                CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)
            }
        }

        override suspend fun isAppLockSatisfied(): Boolean {
            val appLockEnabled = dataStore.appLockEnabled.first()
            return !appLockEnabled || appLockSessionUnlocked
        }

        override fun markCredentialReadsAuthorized() {
            appLockSessionUnlocked = true
        }

        override fun markCredentialReadsLocked() {
            appLockSessionUnlocked = false
        }
    }
