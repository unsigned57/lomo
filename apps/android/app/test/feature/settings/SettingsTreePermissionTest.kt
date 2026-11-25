package com.lomo.app.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.throwables.shouldNotThrowAny
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

        test("cross-user 0@ tree uri accepts matching bare persisted read and write grant") {
            val context = mockk<Context>()
            val resolver = mockk<ContentResolver>(relaxed = true)
            val requestedUri = mockk<Uri>()
            val persistedUri = mockk<Uri>()
            val grant = mockk<UriPermission>()
            every {
                requestedUri.toString()
            } returns "content://0@com.android.externalstorage.documents/tree/primary%3AObsidian"
            every {
                persistedUri.toString()
            } returns "content://com.android.externalstorage.documents/tree/primary%3AObsidian"
            every { grant.uri } returns persistedUri
            every { grant.isReadPermission } returns true
            every { grant.isWritePermission } returns true
            every { context.contentResolver } returns resolver
            every { resolver.persistedUriPermissions } returns listOf(grant)

            shouldNotThrowAny {
                persistTreePermission(context, requestedUri)
            }
        }

        test("cross-user 0@ tree uri fails closed when bare grant is read-only or belongs to another explicit user") {
            val context = mockk<Context>()
            val resolver = mockk<ContentResolver>(relaxed = true)
            val requestedUri = mockk<Uri>()
            val bareUri = mockk<Uri>()
            val userTenUri = mockk<Uri>()
            val readOnlyGrant = mockk<UriPermission>()
            val otherUserGrant = mockk<UriPermission>()
            every {
                requestedUri.toString()
            } returns "content://0@com.android.externalstorage.documents/tree/primary%3AObsidian"
            every {
                bareUri.toString()
            } returns "content://com.android.externalstorage.documents/tree/primary%3AObsidian"
            every {
                userTenUri.toString()
            } returns "content://10@com.android.externalstorage.documents/tree/primary%3AObsidian"
            every { readOnlyGrant.uri } returns bareUri
            every { readOnlyGrant.isReadPermission } returns true
            every { readOnlyGrant.isWritePermission } returns false
            every { otherUserGrant.uri } returns userTenUri
            every { otherUserGrant.isReadPermission } returns true
            every { otherUserGrant.isWritePermission } returns true
            every { context.contentResolver } returns resolver
            every { resolver.persistedUriPermissions } returns listOf(readOnlyGrant, otherUserGrant)

            shouldThrow<IllegalStateException> {
                persistTreePermission(context, requestedUri)
            }.message shouldContain "saf_grant_not_persisted"
        }
    }
}
