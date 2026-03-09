package com.lomo.data.webdav

import android.content.Context
import com.lomo.data.security.KeystoreBackedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: KeystoreBackedPreferences by lazy {
            KeystoreBackedPreferences(
                context = context,
                preferenceFileName = "webdav_credentials",
                keyAlias = "webdav_credentials",
            )
        }

        fun getUsername(): String? = prefs.getString(KEY_USERNAME)

        fun setUsername(username: String?) {
            prefs.putString(KEY_USERNAME, username)
        }

        fun getPassword(): String? = prefs.getString(KEY_PASSWORD)

        fun setPassword(password: String?) {
            prefs.putString(KEY_PASSWORD, password)
        }

        companion object {
            private const val KEY_USERNAME = "webdav_username"
            private const val KEY_PASSWORD = "webdav_password"
        }
    }
