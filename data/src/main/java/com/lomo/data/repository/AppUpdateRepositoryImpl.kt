package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppUpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl
    @Inject
    constructor() : AppUpdateRepository {
        override suspend fun fetchLatestRelease(): LatestAppRelease? =
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    runNonFatalCatching {
                        val url = URL(GITHUB_LATEST_RELEASES_URL)
                        connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = CONNECT_TIMEOUT_MS
                        connection.readTimeout = READ_TIMEOUT_MS
                        connection.setRequestProperty("User-Agent", USER_AGENT)

                        val responseCode = connection.responseCode
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            Timber.w("Update check failed: code=%d", responseCode)
                            null
                        } else {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            parseLatestReleaseResponse(response)
                        }
                    }.getOrElse { error ->
                        Timber.w(error, "Update check failed")
                        null
                    }
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

internal fun parseLatestReleaseResponse(responseBody: String): LatestAppRelease {
    val json = releaseJsonParser.parseToJsonElement(responseBody).jsonObject
    val assets = json["assets"]?.jsonArray.orEmpty()
    val apkAsset =
        assets
            .asSequence()
            .map { it.jsonObject }
            .firstOrNull { asset ->
                asset["name"].jsonContentOrNull().orEmpty().endsWith(".apk", ignoreCase = true)
            }
    val apkFileName = apkAsset?.get("name").jsonContentOrNull()?.takeIf { it.isNotBlank() }
    val apkDownloadUrl =
        apkAsset
            ?.get("browser_download_url")
            .jsonContentOrNull()
            ?.takeIf { it.isNotBlank() }
    val apkSizeBytes = apkAsset?.get("size").jsonLongOrNull()?.takeIf { it > 0 }

    return LatestAppRelease(
        tagName = requireJsonString(json, "tag_name"),
        htmlUrl = requireJsonString(json, "html_url"),
        body = json["body"].jsonContentOrNull().orEmpty(),
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
    )
}

private fun requireJsonString(
    json: kotlinx.serialization.json.JsonObject,
    key: String,
): String = requireNotNull(json[key].jsonContentOrNull()) { "Missing GitHub release field: $key" }

private fun JsonElement?.jsonContentOrNull(): String? =
    try {
        this?.jsonPrimitive?.content
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonElement?.jsonLongOrNull(): Long? = jsonContentOrNull()?.toLongOrNull()

private val releaseJsonParser =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
