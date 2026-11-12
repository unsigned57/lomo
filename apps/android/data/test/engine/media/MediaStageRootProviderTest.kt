package com.lomo.data.engine.media

import android.content.Context
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File

/*
 * Behavior Contract:
 * Capability: staged attachments stay in app-private storage; owner: data; priority: P1.
 * Scenarios: Given a Direct user workspace, when a stage root is requested, then it belongs to filesDir.
 * Observable outcomes: returned absolute path and directory creation.
 * TDD proof: RED/GREEN ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.media.MediaStageRootProviderTest'.
 * Excludes: attachment promotion and OS grant validation.
 */
class MediaStageRootProviderTest : DataFunSpec() {
    init {
        test("given a Direct workspace when staging media then its journal lives in private files") {
            val files = kotlin.io.path.createTempDirectory("stage-private").toFile()
            val documents = kotlin.io.path.createTempDirectory("stage-documents").toFile()
            try {
                val context = mockk<Context>()
                every { context.filesDir } returns files
                val provider = WorkspaceMediaStageRootProvider(context)
                provider.requireRoot() shouldBe File(files, HOST_MEDIA_STAGE_ROOT_NAME).absolutePath
                File(provider.requireRoot()).isDirectory shouldBe true
            } finally {
                files.deleteRecursively()
                documents.deleteRecursively()
            }
        }
    }
}
