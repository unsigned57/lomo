package com.lomo.data.repository

import com.lomo.data.s3.LomoS3Client
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain

/*
 * Behavior Contract:
 * - Unit under test: S3/WebDAV remote payload client contracts.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: remote object upload/download APIs are streaming-first and keep ByteArray only on
 *   explicitly named small-text/test-helper paths.
 *
 * Scenarios:
 * - Given S3 client APIs, when primary remote object methods are inspected, then they expose File
 *   transfer contracts and do not expose ByteArray payload parameters.
 * - Given WebDAV client APIs, when primary remote object methods are inspected, then they expose File
 *   transfer contracts and do not expose ByteArray payload parameters.
 * - Given small-object helpers, when the contract is inspected, then ByteArray usage is isolated to
 *   names containing Small.
 *
 * Observable outcomes:
 * - Reflected public method signatures expose File on primary upload/download paths and no ByteArray
 *   on non-small remote payload methods.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.data.repository.RemotePayloadStreamingContractTest'`
 *   fails before the fix because LomoS3Client.putObject(bytes), WebDavClient.put(bytes), and
 *   WebDavClient.get()/LomoS3Client.getObject payload contracts expose whole-body ByteArray paths.
 *
 * Excludes:
 * - SDK-internal request bodies, memo text helper content, and live network transport behavior.
 */
class RemotePayloadStreamingContractTest : DataFunSpec() {
    init {
        test("given s3 client contract when inspecting primary payload methods then only file streaming is primary") {
            val primaryPayloadMethods = primaryPayloadMethods(LomoS3Client::class.java)

            primaryPayloadMethods.methodNames() shouldContain "getObjectToFile"
            primaryPayloadMethods.methodNames() shouldContain "putObjectFile"
            primaryPayloadMethods.withByteArrayInSignature().shouldBeEmpty()
        }

        test("given webdav client contract when inspecting primary payload methods then only file streaming is primary") {
            val primaryPayloadMethods = primaryPayloadMethods(WebDavClient::class.java)

            primaryPayloadMethods.methodNames() shouldContain "getToFile"
            primaryPayloadMethods.methodNames() shouldContain "putFile"
            primaryPayloadMethods.withByteArrayInSignature().shouldBeEmpty()
        }
    }
}

private fun primaryPayloadMethods(type: Class<*>): List<java.lang.reflect.Method> =
    type.methods
        .asSequence()
        .filterNot { method -> method.name.contains("Small") }
        .toList()

private fun List<java.lang.reflect.Method>.methodNames(): List<String> = map(java.lang.reflect.Method::getName)

private fun List<java.lang.reflect.Method>.withByteArrayInSignature(): List<String> =
    filter { method ->
        method.returnType == ByteArray::class.java ||
            method.parameterTypes.any { parameter -> parameter == ByteArray::class.java }
    }
        .map(java.lang.reflect.Method::getName)
