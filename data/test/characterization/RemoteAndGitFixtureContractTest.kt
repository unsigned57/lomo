package com.lomo.data.characterization

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Behavior Contract:
 * - Unit under test: fixtures/remote and fixtures/git open-layout contracts
 * - Owning layer: data (test-only characterization)
 * - Capability: keep remote layout and git scenario descriptors machine-readable and path-safe.
 *
 * Scenarios:
 * - Given S3/WebDAV layout fixtures, when loaded, then required roots and path rules are present.
 * - Given git scenarios fixture, when loaded, then required scenario kinds exist.
 * - Given path rules, when inspected, then absolute and parent segments are forbidden.
 *
 * Observable outcomes:
 * - JSON field presence and explicit forbid flags.
 *
 * TDD proof:
 * - RED before fixtures/remote and fixtures/git exist or when required fields are removed.
 *
 * Excludes:
 * - Live network, real git process materialization, rclone ciphertext equality (deferred).
 */
class RemoteAndGitFixtureContractTest : DataFunSpec() {
    init {
        val json = Json { ignoreUnknownKeys = true }

        test("given s3 layout fixture when loaded then path rules forbid escapes") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json.parseToJsonElement(Files.readString(root.resolve("remote/s3-layout.json"))).jsonObject
            document.requireString("backend") shouldBe "s3"
            val rules = document.getValue("path_rules").jsonObject
            rules.boolean("forbid_absolute") shouldBe true
            rules.boolean("forbid_parent_segments") shouldBe true
            document
                .getValue("bucket_relative_roots")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldContain "lomo/memo/"
        }

        test("given webdav layout fixture when loaded then collections are present") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json.parseToJsonElement(Files.readString(root.resolve("remote/webdav-layout.json"))).jsonObject
            document.requireString("backend") shouldBe "webdav"
            document
                .getValue("collection_paths")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldContain "lomo/"
        }

        test("given git scenarios when loaded then required kinds exist") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json.parseToJsonElement(Files.readString(root.resolve("git/scenarios.json"))).jsonObject
            val kinds =
                document
                    .getValue("scenarios")
                    .jsonArray
                    .map { it.jsonObject.requireString("kind") }
                    .toSet()
            kinds shouldContain "ordinary"
            kinds shouldContain "shallow"
            kinds shouldContain "diverged"
            kinds shouldContain "conflict"
            kinds shouldContain "dirty"
        }

        test("given rclone vector slots when loaded then they remain deferred placeholders") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json
                    .parseToJsonElement(Files.readString(root.resolve("remote/rclone-crypt-vectors.json")))
                    .jsonObject
            document.requireString("password").shouldNotBeEmpty()
            val notes =
                document
                    .getValue("vectors")
                    .jsonArray
                    .map { it.jsonObject.requireString("notes") }
            notes.any { it.contains("Placeholder") || it.contains("slot") } shouldBe true
        }
    }
}

private fun JsonObject.requireString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()
