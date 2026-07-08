package com.lomo.data.repository
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetUnsupportedReason
import com.lomo.domain.model.AppUpdateAssetVerification
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
class AppUpdateRepositoryImpl : AppUpdateRepository {
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
    val tagName = requireJsonString(json, "tag_name")
    val releaseVersion = tagName.removePrefix("v")
    val candidates =
        assets
            .asSequence()
            .map { it.jsonObject }
            .mapNotNull { asset -> parseAssetCandidate(asset = asset, releaseVersion = releaseVersion) }
            .toList()
    val verifiedApk = candidates.firstOrNull { it.verification is AppUpdateAssetVerification.Verified }
    return LatestAppRelease(
        tagName = tagName,
        htmlUrl = requireJsonString(json, "html_url"),
        body = json["body"].jsonContentOrNull().orEmpty(),
        apkDownloadUrl = verifiedApk?.downloadUrl,
        apkFileName = verifiedApk?.fileName,
        apkSizeBytes = verifiedApk?.sizeBytes,
        assetCandidates = candidates,
    )
}
private fun parseAssetCandidate(
    asset: kotlinx.serialization.json.JsonObject,
    releaseVersion: String,
): AppUpdateAssetCandidate? {
    val fileName = asset["name"].jsonContentOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (!fileName.endsWith(".apk", ignoreCase = true)) {
        return null
    }
    val downloadUrl =
        asset["browser_download_url"]
            .jsonContentOrNull()
            ?.takeIf { it.isNotBlank() }
    return AppUpdateAssetCandidate(
        fileName = fileName,
        downloadUrl = downloadUrl,
        sizeBytes = asset["size"].jsonLongOrNull()?.takeIf { it > 0 },
        verification = asset.toAppUpdateAssetVerification(downloadUrl = downloadUrl, releaseVersion = releaseVersion),
    )
}
private fun kotlinx.serialization.json.JsonObject.toAppUpdateAssetVerification(
    downloadUrl: String?,
    releaseVersion: String,
): AppUpdateAssetVerification {
    if (downloadUrl == null) {
        return unsupported(AppUpdateAssetUnsupportedReason.DOWNLOAD_URL_MISSING)
    }
    val fileName = this["name"].jsonContentOrNull().orEmpty()
    val metadata = AppUpdateReleaseAssetNamePolicy.parse(fileName)
    if (metadata == null) {
        val reason =
            if (fileName.startsWith(EXPECTED_RELEASE_ASSET_PREFIX, ignoreCase = true)) {
                AppUpdateAssetUnsupportedReason.METADATA_UNAVAILABLE
            } else {
                AppUpdateAssetUnsupportedReason.WRONG_PACKAGE
            }
        return unsupported(reason)
    }
    if (metadata.versionName.removePrefix("v") != releaseVersion) {
        return unsupported(AppUpdateAssetUnsupportedReason.VERSION_MISMATCH)
    }
    return AppUpdateAssetVerification.Verified(
        packageName = EXPECTED_RELEASE_PACKAGE_NAME,
        versionName = metadata.versionName,
        versionCode = metadata.versionCode,
    )
}
private data class ReleaseAssetNameMetadata(
    val versionName: String,
    val versionCode: Long?,
)
private object AppUpdateReleaseAssetNamePolicy {
    private val assetNamePattern =
        Regex("""^lomo-v(?<versionName>[0-9][A-Za-z0-9._-]*?)(?:-vc(?<versionCode>[1-9][0-9]*))?\.apk$""")
    fun parse(fileName: String): ReleaseAssetNameMetadata? {
        val match = assetNamePattern.matchEntire(fileName) ?: return null
        val versionName = match.groups["versionName"]?.value?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = match.groups["versionCode"]?.value?.toLongOrNull()
        return ReleaseAssetNameMetadata(versionName = versionName, versionCode = versionCode)
    }
}
private fun unsupported(reason: AppUpdateAssetUnsupportedReason): AppUpdateAssetVerification =
    AppUpdateAssetVerification.Unsupported(reason)
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
private const val EXPECTED_RELEASE_PACKAGE_NAME = "com.lomo.app"
private const val EXPECTED_RELEASE_ASSET_PREFIX = "lomo-v"
