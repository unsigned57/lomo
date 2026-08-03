# Android Release

Release builds use the same xtask graph as local development and pull-request CI.

## Prerequisites

```bash
just bootstrap
just ci
```

The release boundary requires all four signing values, either in `app/keystore.properties` using
camelCase or uppercase keys, or as environment variables:

```text
KEYSTORE_FILE
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

Partial or missing configuration fails before the release build. No default key, interactive guess,
or debug signing fallback exists.

## Build

```bash
just android release [abi]
```

xtask performs the following as one graph:

1. validates baseline profile sources and signing configuration;
2. builds `lomo-native` for the target ABI(s) with NDK 29/API 26 and the release-android profile;
3. generates BoltFFI Kotlin/JNI into `native-bindings` / `com.lomo.nativebridge` and packages
   only `liblomo_native_jni.so` per ABI;
4. builds the Kotlin Toolchain release APK with non-destructive ABI stashing isolation;
5. verifies the single native library for every targeted ABI, absence of unselected ABIs or JNA/`libjnidispatch`/old
   `liblomo_native.so`, ELF architecture and dependencies, and embedded baseline profile assets;
6. signs with `apksigner` using environment-backed passwords and verifies the signature.

The final artifacts are `.kotlin/artifacts/android-release/lomo-release-<abi>.apk` (and
`lomo-release.apk` for universal `all`). Build intermediates stay in the single configured shared
Kotlin build directory.

Tag workflow `.github/workflows/android_release.yml` invokes the same commands and publishes all split and universal release artifacts (`lomo-release-*.apk`). It must not grow a second native, Kotlin, signing, or APK validation implementation.

## Resource Review

`just ci` includes string-resource key parity. `just android release` additionally validates all
native ABIs, ELF metadata, BoltFFI-only packaging (`liblomo_native_jni.so`), baseline profile
assets, signing, and the final APK signature.

The parity check compares `string`, `plurals`, and `string-array` keys between:

- `app/res/values/strings.xml` and `app/res/values-zh-rCN/strings.xml`
- `data/res/values/strings.xml` and `data/res/values-zh-rCN/strings.xml`
- `ui-components/composeResources/values/strings.xml` and
  `ui-components/composeResources/values-zh-rCN/strings.xml`

No allowlist is currently needed. Key parity does not prove translation quality, placeholder
semantics, unused-resource cleanup, or Android resource merge behavior.

| Resource area | Owner | Risk | Release review |
| --- | --- | --- | --- |
| FileProvider paths | App release and share/update owners | Overbroad paths or stale cache files can expose unintended content. | Confirm `file_paths.xml` exposes only generated share images and validated update APKs; grants are user-driven and stale files are cleaned. |
| Backup and data extraction | App release and data/security owners | Source extraction rules can be mistaken for active backup policy. | Inspect the merged manifest for `allowBackup`, `fullBackupContent`, and `dataExtractionRules`; record the intended cloud-backup and device-transfer behavior. |
| Locale config and string parity | App release and i18n reviewer | Locale declarations and cross-module copy can drift. | Confirm every shipped locale has complete keys and that permission, sync, recovery, update, and destructive-action copy has equivalent meaning. |
| Permission and recovery strings | App release and capability owner | Copy can promise behavior unavailable after OS denial. | Confirm every permission has an owner, purpose, denial/retry path, and settings recovery route. |
| Widget preview resources | Widget and app release owners | Launcher previews can diverge from Glance behavior or localized copy. | Validate supported sizes, localized strings, entry actions, and launcher rendering. |
| Shader and visual fallback resources | Update and UI owners | API 33 shader loading or compilation can break update progress. | Verify the API gate, reduced-animation behavior, pre-33 path, and usable fallback when the shader is unavailable. |

Before shipping backup, migration, credential, or restore changes:

1. Inspect the merged release manifest for backup and extraction attributes.
2. Record whether cloud backup and device transfer are intentionally disabled or scoped.
3. Re-check every credential, sync, migration, and workspace setting that would enter that scope.
