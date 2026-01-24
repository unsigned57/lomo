package com.lomo.app.feature.update

import com.lomo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager
    @Inject
    constructor() {
        suspend fun checkForUpdates(): String? =
            withContext(Dispatchers.IO) {
                try {
                    val url = URL("https://api.github.com/repos/unsigned57/lomo/releases/latest")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    // GitHub API requires a User-Agent header
                    connection.setRequestProperty("User-Agent", "Lomo-App")

                    Timber.d("Checking for updates from $url")
                    val responseCode = connection.responseCode
                    Timber.d("Update check response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        Timber.d("Update check response: $response")
                        val json = JSONObject(response)
                        val tagName = json.getString("tag_name") // e.g., "v1.2.0"
                        val htmlUrl = json.getString("html_url")

                        // Remove 'v' prefix if present for clean comparison
                        val remoteVersion = tagName.removePrefix("v")
                        // Strip suffix like "-DEBUG" for comparison
                        val localVersion = BuildConfig.VERSION_NAME.substringBefore("-")

                        Timber.d("Versions - Local: $localVersion, Remote: $remoteVersion")

                        if (isUpdateAvailable(localVersion, remoteVersion)) {
                            Timber.d("Update available: $htmlUrl")
                            return@withContext htmlUrl
                        } else {
                            Timber.d("No update available")
                        }
                    } else {
                        Timber.e("Update check failed with code: $responseCode")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error checking for updates")
                    e.printStackTrace()
                }
                return@withContext null
            }

        private fun isUpdateAvailable(
            local: String,
            remote: String,
        ): Boolean {
            // Simple semantic version comparison
            // Assuming format x.y.z
            try {
                val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
                val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }

                val length = maxOf(localParts.size, remoteParts.size)
                for (i in 0 until length) {
                    val l = localParts.getOrElse(i) { 0 }
                    val r = remoteParts.getOrElse(i) { 0 }
                    if (r > l) return true
                    if (r < l) return false
                }
            } catch (e: Exception) {
                // Fallback to simple string comparison if not semver or parsing fails
                return remote != local
            }
            return false
        }
    }
