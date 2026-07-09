package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SettingsDialogRoute and provider FormState in SettingsDialogState
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: model Git/WebDAV/S3 provider dialogs as one typed route plus one form state,
 *   with provider and secrecy derived from the field/action contract instead of supplied by callers.
 *
 * Scenarios:
 * - Given a provider text field is opened, when the user edits and toggles secrecy, then the
 *   active route and form state carry provider, field, initial value, current value, dirty state,
 *   and visibility through one canonical path.
 * - Given Git, WebDAV, and S3 provider fields are opened, when the route is inspected, then the
 *   provider and secret-entry policy are derived from the field instead of caller-supplied pairs.
 * - Given a provider dialog is dismissed, when state is inspected, then provider route/form
 *   lifecycle is cleared centrally.
 *
 * Observable outcomes:
 * - SettingsDialogState.activeProviderDialogRoute and providerTextFormState values.
 *
 * TDD proof:
 * - RED: focused app test fails before the fix because SettingsDialogRoute, FormState,
 *   and provider route open/dismiss APIs do not exist.
 *
 * Excludes:
 * - Non-provider settings dialogs, repository writes, and Compose text-field rendering.
 */
class SettingsProviderDialogStateTest : AppFunSpec() {
    init {
        test("provider text route derives provider and secret policy for all remote providers") {
            val dialogState = SettingsDialogState()
            val gitRoute = SettingsDialogRoute.RemoteProviderText(RemoteProviderTextField.GitRemoteUrl)
            val webDavRoute = SettingsDialogRoute.RemoteProviderText(RemoteProviderTextField.WebDavPassword)
            val s3Route = SettingsDialogRoute.RemoteProviderText(RemoteProviderTextField.S3RcloneEncryptedSuffix)

            dialogState.openProviderTextDialog(
                field = RemoteProviderTextField.GitRemoteUrl,
                initialValue = "https://example/repo.git",
            )
            dialogState.updateProviderTextValue("https://example/updated.git")

            assertSoftly(dialogState) {
                activeProviderDialogRoute shouldBe gitRoute
                gitRoute.provider shouldBe SyncBackendType.GIT
                gitRoute.secret shouldBe false
                providerTextFormState.initialValue shouldBe "https://example/repo.git"
                providerTextFormState.value shouldBe "https://example/updated.git"
                providerTextFormState.isDirty shouldBe true
                providerTextFormState.secretVisible shouldBe false
            }

            dialogState.openProviderTextDialog(field = RemoteProviderTextField.WebDavPassword, initialValue = "")
            dialogState.updateProviderTextValue("app-password")
            dialogState.toggleProviderTextSecretVisibility()

            assertSoftly(dialogState) {
                activeProviderDialogRoute shouldBe webDavRoute
                webDavRoute.provider shouldBe SyncBackendType.WEBDAV
                webDavRoute.secret shouldBe true
                providerTextFormState.value shouldBe "app-password"
                providerTextFormState.secretVisible shouldBe true
                providerTextFormState.isDirty shouldBe true
            }

            dialogState.openProviderTextDialog(
                field = RemoteProviderTextField.S3RcloneEncryptedSuffix,
                initialValue = ".bin",
            )
            dialogState.dismissProviderDialog()

            dialogState.activeProviderDialogRoute shouldBe null
            dialogState.providerTextFormState shouldBe FormState(initialValue = "", value = "")
        }

        test("provider selection and confirmation routes retain provider-specific field identity") {
            val dialogState = SettingsDialogState()
            val selectionRoute =
                SettingsDialogRoute.RemoteProviderSelection(RemoteProviderSelectionField.S3EncryptionMode)
            val confirmationRoute =
                SettingsDialogRoute.RemoteProviderConfirmation(RemoteProviderConfirmationAction.GitResetRepository)

            dialogState.openProviderSelectionDialog(RemoteProviderSelectionField.S3EncryptionMode)
            dialogState.activeProviderDialogRoute shouldBe selectionRoute
            selectionRoute.provider shouldBe SyncBackendType.S3

            dialogState.openProviderConfirmationDialog(RemoteProviderConfirmationAction.GitResetRepository)
            dialogState.activeProviderDialogRoute shouldBe confirmationRoute
            confirmationRoute.provider shouldBe SyncBackendType.GIT
        }
    }
}
