package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractShareAttachmentsUseCaseTest {
    private val useCase = ExtractShareAttachmentsUseCase()

    @Test
    fun `extracts markdown wiki and audio local attachments while filtering remote`() {
        val content =
            """
            ![img](images/a.png)
            ![[vault/b.jpg|cover]]
            [voice](voice/recording.m4a)
            ![remote](https://cdn.example.com/c.png)
            [remote-audio](http://example.com/d.mp3)
            """.trimIndent()

        val result = useCase(content)

        assertEquals(
            listOf("images/a.png", "vault/b.jpg", "voice/recording.m4a"),
            result.localAttachmentPaths,
        )
        assertEquals(
            mapOf(
                "images/a.png" to "images/a.png",
                "vault/b.jpg" to "vault/b.jpg",
                "voice/recording.m4a" to "voice/recording.m4a",
            ),
            result.attachmentUris,
        )
    }

    @Test
    fun `extracts distinct local paths only`() {
        val content =
            """
            ![img]( ./same.png )
            ![img2](./same.png)
            """.trimIndent()

        val result = useCase(content)

        assertEquals(listOf("./same.png"), result.localAttachmentPaths)
    }
}
