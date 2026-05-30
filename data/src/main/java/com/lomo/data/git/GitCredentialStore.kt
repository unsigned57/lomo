package com.lomo.data.git

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.credentialStatus
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitCredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "git_credentials",
                keyAlias = "git_credentials",
            )
        },
    )

    internal constructor(prefs: SecureStringStore) : this({ prefs })

    private val prefs: SecureStringStore by lazy(secureStringStoreFactory)

    fun getToken(): String? = prefs.getString(KEY_GITHUB_PAT)

    val tokenStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_GITHUB_PAT)

    val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.GIT,
                fields = listOf(CredentialFieldState(CredentialField.GIT_TOKEN, tokenStatus)),
            )

    fun setToken(token: String?) {
        prefs.putString(KEY_GITHUB_PAT, token)
    }

    companion object {
        private const val KEY_GITHUB_PAT = "github_pat"
    }
}
