package com.lomo.data.security

import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.SecuritySessionEvent
import com.lomo.domain.model.SecuritySessionSnapshot
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.allowsCredentialRead
import com.lomo.domain.model.credentialAuthorization
import com.lomo.domain.repository.AuthorizedWorkResume
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class DataStoreSecuritySessionPolicy(
    private val preferenceSource: AppLockPreferenceSource,
    private val authorizedWorkResume: AuthorizedWorkResume,
    appScope: CoroutineScope,
) : SecuritySessionPolicy,
    SecuritySessionController {
    private val snapshot = AtomicReference(SecuritySessionSnapshot())
    private val states = MutableStateFlow(snapshot.get().state)

    init {
        appScope.launch {
            preferenceSource.observe().collect { preference ->
                applyEvent(SecuritySessionEvent.PreferenceRead(preference))
            }
        }
    }

    override fun observe(): StateFlow<SecuritySessionState> = states.asStateFlow()

    override suspend fun current(): SecuritySessionState =
        applyEvent(SecuritySessionEvent.PreferenceRead(preferenceSource.read())).state

    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        current().credentialAuthorization()

    override fun recordAuthenticated() {
        applyEvent(SecuritySessionEvent.Authenticated)
    }

    override fun recordBackgrounded() {
        applyEvent(SecuritySessionEvent.Backgrounded)
    }

    override suspend fun refresh() {
        current()
    }

    private fun applyEvent(event: SecuritySessionEvent): SecuritySessionSnapshot {
        while (true) {
            val previous = snapshot.get()
            val next = previous.apply(event)
            if (snapshot.compareAndSet(previous, next)) {
                states.value = next.state
                if (!previous.state.allowsCredentialRead() && next.state.allowsCredentialRead()) {
                    authorizedWorkResume.onSessionAllowsBackgroundWork()
                }
                return next
            }
        }
    }
}
