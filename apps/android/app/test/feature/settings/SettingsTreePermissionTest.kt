package com.lomo.app.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

/*
 * Behavior Contract:
 * - Unit under test: settings SAF tree permission persistence.
 * - Owning layer: app settings Android capability boundary.
 * - Priority tier: P0.
 * - Capability: accept a selected workspace tree only after read and write grants are persisted.
 *
 * Scenarios:
 * - Given a tree without a persisted grant, when settings persists the selection, then activation fails closed.
 *
 * Observable outcomes:
 * - A structured IllegalStateException containing the stable permission failure code.
 *
 * TDD proof:
 * - RED because the previous helper returned after taking a grant without verifying persisted read/write access.
 *
 * Excludes:
 * - The Android document picker UI, provider lifecycle, and workspace engine activation.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for SettingsTreePermission.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including persisted tree permission must expose read and
 * write grants.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SettingsTreePermissionTest : AppFunSpec() {
    init {
        test("persisted tree permission must expose read and write grants") {
            val context = mockk<Context>()
            val resolver = mockk<ContentResolver>(relaxed = true)
            val uri = mockk<Uri>()
            every { context.contentResolver } returns resolver
            every { resolver.persistedUriPermissions } returns emptyList()

            shouldThrow<IllegalStateException> {
                persistTreePermission(context, uri)
            }.message shouldContain "saf_grant_not_persisted"
        }
    }
}
