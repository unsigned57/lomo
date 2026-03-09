package com.lomo.data.webdav

import com.lomo.domain.model.WebDavProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultWebDavEndpointResolverTest {
    private val resolver = DefaultWebDavEndpointResolver()

    @Test
    fun `nutstore falls back to default endpoint`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NUTSTORE,
                baseUrl = null,
                endpointUrl = null,
                username = "user",
            )

        assertEquals("https://dav.jianguoyun.com/dav/Lomo/", endpoint)
    }

    @Test
    fun `nutstore ignores stale base url and prefers explicit endpoint or default`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NUTSTORE,
                baseUrl = "https://stale.example.com/should-not-be-used",
                endpointUrl = null,
                username = "user",
            )

        assertEquals("https://dav.jianguoyun.com/dav/Lomo/", endpoint)
    }

    @Test
    fun `nutstore upgrades official root endpoint to app folder`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NUTSTORE,
                baseUrl = null,
                endpointUrl = "https://dav.jianguoyun.com/dav/",
                username = "user",
            )

        assertEquals("https://dav.jianguoyun.com/dav/Lomo/", endpoint)
    }

    @Test
    fun `nutstore upgrades bare host to app folder`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NUTSTORE,
                baseUrl = null,
                endpointUrl = "dav.jianguoyun.com",
                username = "user",
            )

        assertEquals("https://dav.jianguoyun.com/dav/Lomo/", endpoint)
    }

    @Test
    fun `nextcloud builds user-scoped endpoint`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NEXTCLOUD,
                baseUrl = "https://cloud.example.com",
                endpointUrl = null,
                username = "alice",
            )

        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/Lomo/", endpoint)
    }

    @Test
    fun `custom uses explicit endpoint`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.CUSTOM,
                baseUrl = "https://ignored.example.com",
                endpointUrl = "dav.example.com/root",
                username = "alice",
            )

        assertEquals("https://dav.example.com/root/", endpoint)
    }

    @Test
    fun `nextcloud requires username`() {
        val endpoint =
            resolver.resolve(
                provider = WebDavProvider.NEXTCLOUD,
                baseUrl = "https://cloud.example.com",
                endpointUrl = null,
                username = null,
            )

        assertNull(endpoint)
    }
}
