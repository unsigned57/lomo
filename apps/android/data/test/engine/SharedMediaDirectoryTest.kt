package com.lomo.data.engine

import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: shared media selections preserve one physical workspace namespace; owner: data; P0.
 * Scenarios:
 * - Given all three slots select one root, when listed, then memo documents have no synthetic aliases.
 * - Given an images file in that root, when read, then images/ is retained.
 * Observable outcomes: exact listed paths and read bytes through the production platform executor.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log lists synthetic images/voice roots before the fix;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log.
 * Excludes: JNI indexing and real SAF permissions (covered by the device journey).
 */
class SharedMediaDirectoryTest :
    FunSpec({
        test("given shared media selections when listing and reading then the workspace has one namespace") {
            val workspace = createTempDirectory("lomo-shared-root-").toFile()
            val exchange = createTempDirectory("lomo-shared-exchange-").toFile()
            try {
                workspace.resolve("2026_11_06.md").writeText("memo")
                workspace.resolve("images").mkdir()
                workspace.resolve("images/a.png").writeText("correct bytes")
                val registry = CapabilityRegistry()
                val grant = registry.registerDirect(workspace)
                registry.bindMediaRoots(
                    grant.capabilityToken,
                    mapOf("images" to workspace.path, "voice" to workspace.path),
                )
                val access = AndroidPlatformActionAccess(registry, ExchangeResolver(exchange), mockk())
                val listing = access.execute(
                    PlatformAction.ListChildren(
                        "list",
                        grant.capabilityToken,
                        WorkspaceTarget.Root,
                        null,
                        64u,
                    ),
                ).shouldBeInstanceOf<ActionOutcome.Applied>()
                    .output.shouldBeInstanceOf<PlatformActionOutput.Listed>().page
                listing.items.map { (it.target as WorkspaceTarget.Relative).path }.toSet() shouldBe
                    setOf("2026_11_06.md", "images")
                val read = access.execute(
                    PlatformAction.Read(
                        "read",
                        grant.capabilityToken,
                        "images/a.png",
                        com.lomo.nativebridge.ExpectedFingerprint.Absent,
                    ),
                ).shouldBeInstanceOf<ActionOutcome.Applied>().output.shouldBeInstanceOf<PlatformActionOutput.Read>()
                read.bytes.toString(Charsets.UTF_8) shouldBe "correct bytes"
            } finally {
                workspace.deleteRecursively()
                exchange.deleteRecursively()
            }
        }
    })
