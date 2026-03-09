package com.lomo.data.git

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: KeystoreBackedPreferences by lazy {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "git_credentials",
                keyAlias = "git_credentials",
            )
        }

        fun getToken(): String? = prefs.getString(KEY_GITHUB_PAT)

        fun setToken(token: String?) {
            prefs.putString(KEY_GITHUB_PAT, token)
        }

        companion object {
            private const val KEY_GITHUB_PAT = "github_pat"
        }
    }
