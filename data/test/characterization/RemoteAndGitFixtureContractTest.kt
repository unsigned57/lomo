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
 * - Capability: keep remote layout and git scenario descriptors machine-readable and path-safe;
 *   keep rclone crypt vector file schema honest about deferred ciphertext.
 *
 * Scenarios:
 * - Given S3/WebDAV layout fixtures, when loaded, then required roots and path rules are present.
 * - Given git scenarios fixture, when loaded, then required scenario kinds exist.
 * - Given path rules, when inspected, then absolute and parent segments are forbidden.
 * - Given verified rclone crypt vectors, when loaded, then each vector has id/mode and
 *   ciphertext identity fields produced by rclone (not placeholders).
 *
 * Observable outcomes:
 * - JSON field presence and explicit forbid flags.
 * - rclone status=verified with ciphertext_name or ciphertext_hex per vector.
 *
 * TDD proof:
 * - RED before fixtures/remote and fixtures/git exist or when required fields are removed.
 * - RED if rclone vectors regress to empty deferred placeholders.
 *
 * Excludes:
 * - Live network, real git process materialization, production crypt engine.
 *
 * Test Change Justification:
 * - Reason category: Contract correction
 * - Old behavior/assertion being replaced: deferred/placeholder rclone vectors accepted as green
 * - Why old assertion is no longer correct: stage-0 requires verified ciphertext identity fields
 * - Coverage preserved by: still locks S3/WebDAV layout, git scenario kinds, and path safety
 * - Why this is not fitting the test to the implementation: asserts shared fixture schema only
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
            val roots =
                document
                    .getValue("bucket_relative_roots")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            roots shouldContain "lomo/memo/"
            roots shouldContain "lomo/media/"
            roots shouldContain "lomo/.index/"
            val samples = document.getValue("sample_objects").jsonArray
            samples.isEmpty() shouldBe false
            samples[0].jsonObject.requireString("key").shouldNotBeEmpty()
        }

        test("given webdav layout fixture when loaded then collections are present") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json.parseToJsonElement(Files.readString(root.resolve("remote/webdav-layout.json"))).jsonObject
            document.requireString("backend") shouldBe "webdav"
            val collections =
                document
                    .getValue("collection_paths")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            collections shouldContain "lomo/"
            collections shouldContain "lomo/memo/"
            val resources = document.getValue("sample_resources").jsonArray
            resources.isEmpty() shouldBe false
            resources[0].jsonObject.requireString("href").shouldNotBeEmpty()
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

        test("given rclone crypt vectors when loaded then verified ciphertext identities are present") {
            val root = FixtureRepositoryPaths.fixturesRoot()
            val document =
                json
                    .parseToJsonElement(Files.readString(root.resolve("remote/rclone-crypt-vectors.json")))
                    .jsonObject
            document.requireString("password").shouldNotBeEmpty()
            document.requireString("status") shouldBe "verified"
            val vectors = document.getValue("vectors").jsonArray
            vectors.isEmpty() shouldBe false
            for (element in vectors) {
                val vector = element.jsonObject
                vector.requireString("id").shouldNotBeEmpty()
                vector.requireString("mode").shouldNotBeEmpty()
                val hasPlainIdentity =
                    vector.containsKey("plaintext_path") || vector.containsKey("plaintext_utf8")
                hasPlainIdentity shouldBe true
                val hasCipherIdentity =
                    vector.containsKey("ciphertext_name") || vector.containsKey("ciphertext_hex")
                hasCipherIdentity shouldBe true
            }
        }
    }
}

private fun JsonObject.requireString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()
