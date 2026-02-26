package com.lomo.data.git

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences by lazy {
            EncryptedSharedPreferences.create(
                "git_credentials",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun getToken(): String? = prefs.getString(KEY_GITHUB_PAT, null)

        fun setToken(token: String?) {
            prefs.edit().apply {
                if (token.isNullOrBlank()) {
                    remove(KEY_GITHUB_PAT)
                } else {
                    putString(KEY_GITHUB_PAT, token)
                }
                apply()
            }
        }

        companion object {
            private const val KEY_GITHUB_PAT = "github_pat"
        }
    }
