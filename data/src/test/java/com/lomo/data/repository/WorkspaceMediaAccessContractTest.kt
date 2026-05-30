package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldNotContain

/*
 * Behavior Contract:
 * - Unit under test: WorkspaceMediaAccess
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: media access implementations must provide explicit stream read/write behavior.
 *
 * Scenarios:
 * - Given a workspace media implementation, when callers stream media out, then no interface byte-array fallback can satisfy the call.
 * - Given a workspace media implementation, when callers stream media in, then no interface ByteArrayOutputStream fallback can buffer the payload.
 * - Given future test fakes or production backends omit stream methods, when the code compiles, then the omission is rejected by the interface contract.
 * - Given a default byte-array adapter could reappear, when compiled API defaults are inspected, then stream fallback methods are absent.
 *
 * Observable outcomes:
 * - Compiled interface default-method metadata does not contain readFileToStream or writeFileFromStream fallback methods.
 *
 * TDD proof:
 * - RED: fails before the fix because WorkspaceMediaAccess.DefaultImpls still contains stream fallback methods.
 *
 * Excludes:
 * - Archive zip contents, direct filesystem writes, SAF provider behavior, and concrete backend performance.
 */
class WorkspaceMediaAccessContractTest : DataFunSpec() {
    init {
        test("given stream-first media access when compiled then interface exposes no stream fallback defaults") {
            val defaultMethodNames =
                runCatching {
                    Class
                        .forName("${WorkspaceMediaAccess::class.java.name}\$DefaultImpls")
                        .declaredMethods
                        .map { method -> method.name }
                }.getOrDefault(emptyList())

            defaultMethodNames shouldNotContain "readFileToStream"
            defaultMethodNames shouldNotContain "writeFileFromStream"
        }
    }
}
