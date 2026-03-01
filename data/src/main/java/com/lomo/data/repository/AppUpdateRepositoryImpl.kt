package com.lomo.data.repository

import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppUpdateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

@Singleton
class AppUpdateRepositoryImpl
    @Inject
    constructor() : AppUpdateRepository {
        override suspend fun fetchLatestRelease(): LatestAppRelease? =
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL(GITHUB_LATEST_RELEASES_URL)
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", USER_AGENT)

                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        Timber.w("Update check failed: code=%d", responseCode)
                        return@withContext null
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    return@withContext LatestAppRelease(
                        tagName = json.getString("tag_name"),
                        htmlUrl = json.getString("html_url"),
                        body = json.optString("body", ""),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Timber.w(e, "Update check failed")
                    return@withContext null
                } finally {
                    connection?.disconnect()
                }
            }

        private companion object {
            const val GITHUB_LATEST_RELEASES_URL = "https://api.github.com/repos/unsigned57/lomo/releases/latest"
            const val USER_AGENT = "Lomo-App"
            const val CONNECT_TIMEOUT_MS = 5000
            const val READ_TIMEOUT_MS = 5000
        }
    }
