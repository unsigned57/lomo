# Resource Release Checklist

This checklist turns release-sensitive Android resources into a reviewable gate instead of leaving
them scattered across feature files. Run the automated parity check before a release candidate, then
complete the manual review rows for resources whose true behavior depends on the merged manifest,
device API level, framework UI, or runtime grant flow.

## Automated Check

```bash
quality/scripts/check_string_resource_parity.sh
```

The check compares `string`, `plurals`, and `string-array` keys between:

- `app/res/values/strings.xml` and `app/res/values-zh-rCN/strings.xml`
- `data/res/values/strings.xml` and `data/res/values-zh-rCN/strings.xml`
- `ui-components/composeResources/values/strings.xml` and `ui-components/composeResources/values-zh-rCN/strings.xml`

Current finding: no allowlist is needed; all three modules currently have default/`zh-rCN` key parity.
This proves key coverage only. It does not prove translation quality, placeholder semantics, unused
resources, or Android resource merge behavior.

## Release Rows

| Resource area | Owner | Risk | Check method | Release must-review |
| --- | --- | --- | --- | --- |
| FileProvider paths | App release owner + feature owners for share/update | `app/res/xml/file_paths.xml` exposes cache `shared_memos/` and `updates/` through `${applicationId}.fileprovider`; overbroad paths or stale files can expose unintended cache content. | Review `file_paths.xml`, manifest provider config, call sites that grant URIs, MIME/type handling, cleanup, and install/share failure recovery. | Confirm only generated share images and downloaded APKs are reachable, grants are one-shot/user-driven, stale cache is cleaned, and update APK validation happens before share/install URI exposure. |
| Backup and data extraction | App release owner + data/security owner | `data_extraction_rules.xml` includes shared preferences and databases, while `AndroidManifest.xml` sets `allowBackup="false"` and `fullBackupContent="false"`. Maintainers can misread rules as active backup policy. | Inspect source manifest plus merged release manifest/build artifact for `allowBackup`, `fullBackupContent`, and `dataExtractionRules`; test on target Android versions when backup/transfer behavior changes. | Treat current source config as backup disabled unless the merged release manifest proves otherwise. Explicitly record whether cloud backup and device transfer are expected to include Lomo preferences/databases for the release. |
| Locale config and string parity | App release owner + i18n reviewer | `locales_config.xml` declares `en` and `zh-CN`; strings are split across app/data/ui-components and can drift by key or promise different behavior. | Run `quality/scripts/check_string_resource_parity.sh`; review `locales_config.xml`; manually inspect sensitive user-facing copy. | Every shipped locale has complete keys. Permission, sync, recovery, update, and destructive-action strings must describe the same capability and consequence in `values` and `values-zh-rCN`. |
| Permission and recovery strings | App release owner + capability owner | Manifest permissions cover local network/Wi-Fi discovery, recording, notification, exact alarms, unknown APK install, biometric, location, boot, and network state; user recovery copy is spread across feature strings. | Review manifest permissions against settings/runtime request copy and denial paths. Search for `permission`, `requires_install_permission`, local-network, notification, exact alarm, recording, and location strings. | Each permission must have an owner, user-visible purpose, denial/retry path, and settings recovery route. Copy must not imply a capability works after denial or outside OS restrictions. |
| Widget preview resources | Widget owner + app release owner | `lomo_widget_info.xml` uses `@layout/widget_preview`; preview resources can fall out of sync with Glance widget behavior, launcher sizing, or localized copy. | Review `lomo_widget_info.xml`, `widget_preview.xml`, widget backgrounds/icons, `widget_description`, and preview strings in both locales. Validate on API/launcher targets before release. | Preview renders at supported sizes, uses localized strings, does not imply unavailable actions, and remains consistent with `LomoWidget` entry actions. |
| Shader and visual fallback resources | Update owner + UI owner | `app/res/raw/update_progress_shader.agsl` feeds API 33 `RuntimeShader`; shader compile/runtime issues can break update progress visuals on supported devices. | Review raw AGSL resource, `AppUpdateProgressBackdropApi33`, API gating, fallback composable/path, and release shrink/resource packaging. | Update progress remains usable when shader loading or compilation fails, on API levels below 33, and when animations are reduced or unavailable. |

## Manual Build Artifact Confirmation

`allowBackup=false` and `data_extraction_rules` need manifest/build artifact confirmation before release.
The source files describe intent, but Android behavior is determined by the merged release manifest
and framework version. Before shipping a backup, migration, credential, or restore-related change:

1. Inspect the merged release manifest for `allowBackup`, `fullBackupContent`, and `dataExtractionRules`.
2. Record whether cloud backup and device transfer are intentionally disabled or intentionally scoped.
3. Re-check any credential, sync, migration, or workspace setting storage that would be included if
   backup/device-transfer behavior changes.
