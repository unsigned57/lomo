package com.lomo.data.git

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.security.SecureStringStore
import com.lomo.data.security.credentialStatus
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus


class GitCredentialStore private constructor(
    secureStringStoreFactory: () -> SecureStringStore,
) {
    constructor(
        context: Context,
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

    internal fun getToken(): String? = prefs.getString(KEY_GITHUB_PAT)

    internal fun readToken(): SecureStringReadResult = prefs.readString(KEY_GITHUB_PAT)

    internal fun readUsername(): SecureStringReadResult = prefs.readString(KEY_GIT_USERNAME)

    internal val tokenStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_GITHUB_PAT)

    internal val usernameStatus: StoredCredentialStatus
        get() = prefs.credentialStatus(KEY_GIT_USERNAME)

    internal val credentialState: CredentialState
        get() =
            CredentialState(
                provider = CredentialProvider.GIT,
                fields =
                    listOf(
                        CredentialFieldState(CredentialField.GIT_TOKEN, tokenStatus),
                        CredentialFieldState(CredentialField.GIT_USERNAME, usernameStatus),
                    ),
            )

    internal fun setToken(token: String?) {
        prefs.putString(KEY_GITHUB_PAT, token)
    }

    internal fun setUsername(username: String?) {
        prefs.putString(KEY_GIT_USERNAME, username)
    }

    companion object {
        private const val KEY_GITHUB_PAT = "github_pat"
        private const val KEY_GIT_USERNAME = "git_username"
    }
}
