package com.lomo.data.repository
import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetUnsupportedReason
import com.lomo.domain.model.AppUpdateAssetVerification
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.model.AppUpdateFetchFailure
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppUpdateRepository
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
internal class AppUpdateRepositoryImpl(
    private val httpClientProvider: AppUpdateHttpClientProvider,
) : AppUpdateRepository {
        override suspend fun fetchLatestRelease(): LatestAppRelease =
            try {
                httpClientProvider.withHttpClient { client ->
                    val response =
                        client.get(GITHUB_LATEST_RELEASES_URL) {
                            header(HttpHeaders.UserAgent, USER_AGENT)
                            timeout {
                                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                            }
                        }
                    if (!response.status.isSuccess()) {
                        throw AppUpdateFetchException(
                            AppUpdateFetchFailure.Http(
                                code = response.status.value,
                                diagnostic = "release endpoint answered HTTP ${response.status.value}",
                            ),
                        )
                    }
                    try {
                        parseLatestReleaseResponse(response.bodyAsText())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw AppUpdateFetchException(
                            AppUpdateFetchFailure.MalformedResponse(
                                diagnostic =
                                    "unparseable release document: " +
                                        (error.message ?: error.javaClass.simpleName),
                            ),
                            error,
                        )
                    }
                }
            } catch (error: AppUpdateFetchException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpRequestTimeoutException) {
                throw networkFailure(error)
            } catch (error: SocketTimeoutException) {
                throw networkFailure(error)
            } catch (error: UnresolvedAddressException) {
                throw networkFailure(error)
            } catch (error: IOException) {
                throw networkFailure(error)
            }
        private companion object {
            const val GITHUB_LATEST_RELEASES_URL =
                "https://api.github.com/repos/unsigned57/lomo/releases/latest"
            const val USER_AGENT = "Lomo-App"
            const val CONNECT_TIMEOUT_MS = 5_000L
            const val REQUEST_TIMEOUT_MS = 5_000L
        }
    }
private fun networkFailure(cause: Exception): AppUpdateFetchException =
    AppUpdateFetchException(
        AppUpdateFetchFailure.Network(
            diagnostic = cause.message ?: cause.javaClass.simpleName.orEmpty(),
        ),
        cause,
    )
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
        verification =
            asset.toAppUpdateAssetVerification(
                downloadUrl = downloadUrl,
                releaseVersion = releaseVersion,
            ),
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
        val versionName = match.groups["versionName"]?.run { value.takeIf { it.isNotBlank() } } ?: return null
        val versionCode = match.groups["versionCode"]?.run { value.toLongOrNull() }
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
        this?.run { jsonPrimitive.content }
    } catch (_: IllegalArgumentException) {
        // behavior-contract: silent-result-ok: optional JSON field is absent or not a primitive
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
