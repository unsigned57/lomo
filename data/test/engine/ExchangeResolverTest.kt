package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: ExchangeResolver.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: map opaque exchange tokens onto files under the application-private exchange root,
 *   resolve complete typed UTF-8 artifacts, and refuse invalid/stale references before returning
 *   content.
 *
 * Scenarios:
 * - Given a valid token, when resolved, then the file path is under the exchange root.
 * - Given absolute, parent, blank, or backslash tokens, when resolved, then validation fails.
 * - Given a token file, when digest is computed, then length and lowercase SHA-256 match content.
 * - Given a complete memo artifact longer than 240 characters, when its typed reference is read,
 *   then the exact full UTF-8 content is returned.
 * - Given a missing, length-mismatched, digest-mismatched, or invalid UTF-8 artifact, when its typed
 *   reference is read, then a structured error is thrown and no content is returned.
 *
 * Observable outcomes:
 * - canonical file location, exact content, structured validation failures, digest/length pairs.
 *
 * TDD proof:
 * - RED: `readUtf8Artifact` and `ExchangeArtifactReference` do not exist.
 *
 * Excludes:
 * - SAF document I/O and platform action orchestration.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.security.MessageDigest

class ExchangeResolverTest : DataFunSpec() {
    init {
        test("given valid token when resolved then file stays under exchange root") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                val file = resolver.resolveFile("exchange-read-1")

                file.canonicalPath shouldStartWith root.canonicalPath
                file.name shouldBe "exchange-read-1"
            } finally {
                root.deleteRecursively()
            }
        }

        test("given escaping token when resolved then validation fails closed") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                shouldThrow<ExchangeResolverException> { resolver.resolveFile("../escape") }
                shouldThrow<ExchangeResolverException> { resolver.resolveFile("/abs") }
                shouldThrow<ExchangeResolverException> { resolver.resolveFile("a\\b") }
                shouldThrow<ExchangeResolverException> { resolver.resolveFile("") }
            } finally {
                root.deleteRecursively()
            }
        }

        test("given exchange file when digested then length and sha256 match content") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                val file = resolver.resolveFile("payload")
                file.writeBytes(byteArrayOf(1, 2, 3, 4))

                val artifact = resolver.digestArtifact("payload")

                artifact.token shouldBe "payload"
                artifact.length shouldBe 4uL
                artifact.digest shouldBe sha256Hex(byteArrayOf(1, 2, 3, 4))
            } finally {
                root.deleteRecursively()
            }
        }


        test("given long complete UTF-8 artifact when read then exact content is returned") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                val content = "prefix-${"界🙂".repeat(180)}-suffix"
                val bytes = content.encodeToByteArray()
                resolver.resolveFile("ex.scope.memo-0").writeBytes(bytes)

                resolver.readUtf8Artifact(
                    ExchangeArtifactReference(
                        token = "ex.scope.memo-0",
                        length = bytes.size.toULong(),
                        digest = sha256Hex(bytes),
                    ),
                ) shouldBe content
            } finally {
                root.deleteRecursively()
            }
        }

        test("given missing or stale artifact reference when read then failure is structured") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                val missing =
                    shouldThrow<ExchangeResolverException> {
                        resolver.readUtf8Artifact(
                            ExchangeArtifactReference(
                                token = "ex.scope.missing",
                                length = 1uL,
                                digest = sha256Hex(byteArrayOf(1)),
                            ),
                        )
                    }
                missing.code shouldBe "exchange_artifact_missing"

                val bytes = "current".encodeToByteArray()
                resolver.resolveFile("ex.scope.stale").writeBytes(bytes)
                val stale =
                    shouldThrow<ExchangeResolverException> {
                        resolver.readUtf8Artifact(
                            ExchangeArtifactReference(
                                token = "ex.scope.stale",
                                length = bytes.size.toULong(),
                                digest = sha256Hex("old".encodeToByteArray()),
                            ),
                        )
                    }
                stale.code shouldBe "exchange_artifact_mismatch"
            } finally {
                root.deleteRecursively()
            }
        }

        test("given invalid UTF-8 artifact when read then failure is structured") {
            val root = kotlin.io.path.createTempDirectory("lomo-exchange").toFile()
            try {
                val resolver = ExchangeResolver(root)
                val bytes = byteArrayOf(0xC3.toByte(), 0x28)
                resolver.resolveFile("ex.scope.invalid-utf8").writeBytes(bytes)

                val failure =
                    shouldThrow<ExchangeResolverException> {
                        resolver.readUtf8Artifact(
                            ExchangeArtifactReference(
                                token = "ex.scope.invalid-utf8",
                                length = bytes.size.toULong(),
                                digest = sha256Hex(bytes),
                            ),
                        )
                    }

                failure.code shouldBe "exchange_artifact_invalid_utf8"
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
