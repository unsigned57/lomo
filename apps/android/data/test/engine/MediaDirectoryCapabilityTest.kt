package com.lomo.data.engine

import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ListingView
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.nio.file.Files

/*
 * Behavior Contract:
 * Capability: selected media directories constrain platform reads and scans; owner: data; P0.
 * Scenarios:
 * - Given a workspace and an independent selected images directory, when reading images/a.png,
 *   then selected bytes are returned and the workspace's same-named file is excluded.
 * - Given a selected directory outside the workspace, when enumerating the root and images,
 *   then the mount is visible with canonical logical child paths.
 * Observable outcomes: verified read bytes and listed paths.
 * TDD proof: ./kotlin test --include-module=data --include-classes='*MediaDirectoryCapabilityTest';
 * RED: old workspace bytes returned; GREEN: selected bytes returned.
 * Excludes: Android DocumentsProvider implementation and Rust transaction planning.
 */
class MediaDirectoryCapabilityTest :
    FunSpec({
        test(
            "given a missing selected image directory when notes are listed then only the physical notes tree is visited",
        ) {
            val root = Files.createTempDirectory("lomo-notes-root").toFile()
            val exchange = Files.createTempDirectory("lomo-notes-exchange").toFile()
            try {
                root.resolve("2026_09_12.md").writeText("- 12:00\nNotes remain available\n")
                val registry = CapabilityRegistry()
                val grant = registry.registerDirect(root)
                registry.bindMediaRoots(grant.capabilityToken, mapOf("images" to root.resolve("missing").path))
                val access = AndroidPlatformActionAccess(registry, ExchangeResolver(exchange), mockk())
                val request = PlatformAction.ListChildren(
                    "list-notes",
                    grant.capabilityToken,
                    WorkspaceTarget.Root,
                    null,
                    64u,
                    ListingView.NOTES,
                )
                val notes = access.execute(request).shouldBeInstanceOf<ActionOutcome.Applied>()
                    .output.shouldBeInstanceOf<PlatformActionOutput.Listed>()
                notes.page.items.map { it.target } shouldBe listOf(WorkspaceTarget.Relative("2026_09_12.md"))
                access.execute(request.copy(view = ListingView.WORKSPACE))
                    .shouldBeInstanceOf<ActionOutcome.Failed>().failure.code shouldBe "media_root_unavailable"
            } finally {
                root.deleteRecursively()
                exchange.deleteRecursively()
            }
        }
        test("given a selected external image directory when reading then only selected bytes resolve") {
            val root = Files.createTempDirectory("lomo-media-workspace").toFile()
            val selected = Files.createTempDirectory("lomo-media-selected").toFile()
            val exchange = Files.createTempDirectory("lomo-media-exchange").toFile()
            try {
                root.resolve("images").mkdir()
                root.resolve("images/a.png").writeText("wrong workspace bytes")
                selected.resolve("a.png").writeText("selected bytes")
                val registry = CapabilityRegistry()
                val grant = registry.registerDirect(root)
                registry.bindMediaRoots(grant.capabilityToken, mapOf("images" to selected.path))
                val access = AndroidPlatformActionAccess(registry, ExchangeResolver(exchange), mockk())
                val result = access.execute(
                    PlatformAction.Read(
                        "read",
                        grant.capabilityToken,
                        "images/a.png",
                        com.lomo.nativebridge.ExpectedFingerprint.Absent,
                    ),
                )
                    .shouldBeInstanceOf<ActionOutcome.Applied>().output.shouldBeInstanceOf<PlatformActionOutput.Read>()
                result.bytes.toString(Charsets.UTF_8) shouldBe "selected bytes"
                root.resolve("images/a.png").readText() shouldBe "wrong workspace bytes"
            } finally {
                root.deleteRecursively()
                selected.deleteRecursively()
                exchange.deleteRecursively()
            }
        }

        test("given an external image directory when scanning then the logical mount and children are enumerated") {
            val root = Files.createTempDirectory("lomo-media-workspace").toFile()
            val selected = Files.createTempDirectory("lomo-media-selected").toFile()
            val exchange = Files.createTempDirectory("lomo-media-exchange").toFile()
            try {
                selected.resolve("a.png").writeText("selected")
                val registry = CapabilityRegistry()
                val grant = registry.registerDirect(root)
                registry.bindMediaRoots(grant.capabilityToken, mapOf("images" to selected.path))
                val access = AndroidPlatformActionAccess(registry, ExchangeResolver(exchange), mockk())
                fun list(target: WorkspaceTarget) = access.execute(
                    PlatformAction.ListChildren(
                        "list",
                        grant.capabilityToken,
                        target,
                        null,
                        64u,
                        ListingView.WORKSPACE,
                    ),
                ).shouldBeInstanceOf<ActionOutcome.Applied>()
                    .output.shouldBeInstanceOf<PlatformActionOutput.Listed>().page
                list(WorkspaceTarget.Root).items.map { it.target } shouldBe listOf(WorkspaceTarget.Relative("images"))
                list(WorkspaceTarget.Relative("images")).items.map { it.target } shouldBe
                    listOf(WorkspaceTarget.Relative("images/a.png"))
            } finally {
                root.deleteRecursively()
                selected.deleteRecursively()
                exchange.deleteRecursively()
            }
        }
        test(
            "given an orphan in a selected image directory when moved to workspace trash then both granted roots preserve move semantics",
        ) {
            val root = Files.createTempDirectory("lomo-media-workspace").toFile()
            val selected = Files.createTempDirectory("lomo-media-selected").toFile()
            val exchange = Files.createTempDirectory("lomo-media-exchange").toFile()
            try {
                selected.resolve("a.png").writeText("selected bytes")
                val registry = CapabilityRegistry()
                val grant = registry.registerDirect(root)
                registry.bindMediaRoots(grant.capabilityToken, mapOf("images" to selected.path))
                val access = AndroidPlatformActionAccess(registry, ExchangeResolver(exchange), mockk())
                val source = access.execute(
                    PlatformAction.Stat("stat", grant.capabilityToken, WorkspaceTarget.Relative("images/a.png")),
                )
                    .shouldBeInstanceOf<ActionOutcome.Applied>()
                    .output.shouldBeInstanceOf<PlatformActionOutput.Stat>().metadata
                access.execute(
                    PlatformAction.Move(
                        "move",
                        grant.capabilityToken,
                        "images/a.png",
                        ".lomo-media-trash/a.png",
                        com.lomo.nativebridge.ExpectedFingerprint.Match(source.evidence),
                        com.lomo.nativebridge.ExpectedFingerprint.Absent,
                    ),
                ).shouldBeInstanceOf<ActionOutcome.Applied>()
                root.resolve(".lomo-media-trash/a.png").readText() shouldBe "selected bytes"
                selected.resolve("a.png").exists() shouldBe false
            } finally {
                root.deleteRecursively()
                selected.deleteRecursively()
                exchange.deleteRecursively()
            }
        }
    })
