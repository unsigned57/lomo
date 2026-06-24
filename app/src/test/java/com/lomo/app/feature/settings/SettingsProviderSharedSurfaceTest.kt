package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: Git/WebDAV/S3 provider settings Compose renderer ownership.
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: render common remote-provider controls through one shared Compose surface while
 *   provider-specific fields remain in typed provider sections.
 *
 * Scenarios:
 * - Given Git, WebDAV, and S3 renderer classes are compiled, when renderer ownership is inspected,
 *   then common enabled/auto-sync/interval/sync-on-refresh/sync-now/connection-test controls are
 *   declared by RemoteProviderSectionSurface instead of provider-local helpers.
 *
 * Observable outcomes:
 * - Bytecode-level renderer ownership: provider renderer classes no longer declare common-control
 *   helper methods, and the shared surface declares the common section/behavior/action renderers.
 *
 * TDD proof:
 * - RED observed with the focused `SettingsProviderSharedSurfaceTest` Gradle filter before the fix
 *   because provider renderer classes still declare local common-control helpers and
 *   RemoteProviderSectionSurface has no shared Compose renderer.
 *
 * Excludes:
 * - Repository-contract narrowing, sync engine execution, credential storage, and pixel-level UI tests.
 */
class SettingsProviderSharedSurfaceTest : AppFunSpec() {
    init {
        test("given provider renderer classes when common controls are inspected then only shared surface owns them") {
            providerRendererClassNames
                .flatMap { className ->
                    Class
                        .forName(className)
                        .declaredMethods
                        .map { method -> "${className.substringAfterLast('.')}#${method.name}" }
                }.filter { method -> providerLocalCommonRendererMethodNames.any(method::endsWith) }
                .sorted() shouldBe emptyList()

            val sharedMethodNames =
                Class
                    .forName("com.lomo.app.feature.settings.RemoteProviderSectionSurfaceKt")
                    .declaredMethods
                    .map { method -> method.name }

            sharedMethodNames shouldContainAll sharedCommonRendererMethodNames
        }
    }
}

private val providerRendererClassNames =
    listOf(
        "com.lomo.app.feature.settings.SettingsGitSyncSectionsKt",
        "com.lomo.app.feature.settings.SettingsWebDavSyncSectionsKt",
        "com.lomo.app.feature.settings.SettingsS3SyncSectionsKt",
    )

private val providerLocalCommonRendererMethodNames =
    setOf(
        "GitSyncBehaviorPreferences",
        "GitSyncActionPreferences",
        "GitAutoSyncIntervalPreference",
        "WebDavBehaviorPreferences",
        "WebDavActionPreferences",
        "WebDavAutoSyncIntervalPreference",
        "S3BehaviorPreferences",
        "S3ActionPreferences",
    )

private val sharedCommonRendererMethodNames =
    setOf(
        "RemoteProviderSectionSurface",
        "RemoteProviderBehaviorPreferences",
        "RemoteProviderActionPreferences",
        "RemoteProviderSyncIntervalPreference",
    )
