package com.lomo.data.webdav

import com.lomo.domain.model.WebDavProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

interface WebDavEndpointResolver {
    fun resolve(
        provider: WebDavProvider,
        baseUrl: String?,
        endpointUrl: String?,
        username: String?,
    ): String?
}

@Singleton
class DefaultWebDavEndpointResolver
    @Inject
    constructor() : WebDavEndpointResolver {
        override fun resolve(
            provider: WebDavProvider,
            baseUrl: String?,
            endpointUrl: String?,
            username: String?,
        ): String? =
            when (provider) {
                WebDavProvider.NUTSTORE -> {
                    normalizeNutstoreEndpoint(endpointUrl)
                }

                WebDavProvider.NEXTCLOUD -> {
                    val normalizedBase = normalize(baseUrl) ?: return null
                    val normalizedUsername = username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                    withTrailingSlash(
                        normalizedBase.trimEnd('/') + "/remote.php/dav/files/" + normalizedUsername + "/Lomo",
                    )
                }

                WebDavProvider.CUSTOM -> {
                    normalize(endpointUrl)
                }
            }

        private fun normalize(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return withTrailingSlash(candidate)
        }

        private fun normalizeNutstoreEndpoint(endpointUrl: String?): String {
            val normalized = normalize(endpointUrl) ?: return NUTSTORE_DEFAULT_ENDPOINT
            val parsed = normalized.toHttpUrlOrNull() ?: return normalized
            val normalizedPath = parsed.encodedPath.trimEnd('/')
            val normalizedBase =
                parsed
                    .newBuilder()
                    .encodedPath("/")
                    .build()
                    .toString()
                    .trimEnd('/')
            return when {
                parsed.host == NUTSTORE_HOST && (normalizedPath.isEmpty() || normalizedPath == "/") -> NUTSTORE_DEFAULT_ENDPOINT
                parsed.host == NUTSTORE_HOST && normalizedPath == "/dav" -> "$normalizedBase/dav/Lomo/"
                else -> normalized
            }
        }

        private fun withTrailingSlash(value: String): String = value.trimEnd('/') + "/"

        private companion object {
            private const val NUTSTORE_HOST = "dav.jianguoyun.com"
            private const val NUTSTORE_DEFAULT_ENDPOINT = "https://dav.jianguoyun.com/dav/Lomo/"
        }
    }
