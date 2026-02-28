package com.lomo.app.feature.update

import com.lomo.app.BuildConfig
import kotlinx.coroutines.CancellationException
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
        data class UpdateInfo(
            val htmlUrl: String,
            val version: String,
            val releaseNotes: String,
        )

        suspend fun checkForUpdatesInfo(): UpdateInfo? =
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://api.github.com/repos/unsigned57/lomo/releases/latest")
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    // GitHub API requires a User-Agent header
                    connection.setRequestProperty("User-Agent", "Lomo-App")

                    val responseCode = connection.responseCode

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val tagName = json.getString("tag_name") // e.g., "v1.2.0"
                        val htmlUrl = json.getString("html_url")
                        val body = json.optString("body", "")
                        val sanitizedReleaseNotes = sanitizeReleaseNotes(body)

                        // Remove 'v' prefix if present for clean comparison
                        val remoteVersion = tagName.removePrefix("v")
                        // Strip suffix like "-DEBUG" for comparison
                        val localVersion = BuildConfig.VERSION_NAME.substringBefore("-")
                        val forceUpdate = body.contains("[FORCE_UPDATE]")
                        val updateAvailable = forceUpdate || isUpdateAvailable(localVersion, remoteVersion)

                        Timber.d(
                            "Update check summary: code=%d local=%s remote=%s force=%b available=%b",
                            responseCode,
                            localVersion,
                            remoteVersion,
                            forceUpdate,
                            updateAvailable,
                        )

                        if (updateAvailable) {
                            return@withContext UpdateInfo(
                                htmlUrl = htmlUrl,
                                version = remoteVersion,
                                releaseNotes = sanitizedReleaseNotes,
                            )
                        }
                    } else {
                        Timber.w("Update check failed: code=%d", responseCode)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Timber.w(e, "Update check failed")
                } finally {
                    connection?.disconnect()
                }
                return@withContext null
            }

        suspend fun checkForUpdates(): String? = checkForUpdatesInfo()?.htmlUrl

        private fun sanitizeReleaseNotes(raw: String): String {
            val cleaned =
                raw
                    .replace("[FORCE_UPDATE]", "")
                    .replace("\r\n", "\n")
                    .trim()

            if (cleaned.isBlank()) return ""

            val maxLength = 3000
            if (cleaned.length <= maxLength) return cleaned
            return "${cleaned.take(maxLength).trimEnd()}\n..."
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
