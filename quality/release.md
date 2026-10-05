# Release Contract

Read for signing, release resources or publishing. Gate selection and JSON evidence are defined in
[Quality Contract](README.md); module ownership remains in [Architecture](../ARCHITECTURE.md).
Release builds use the same xtask graph as local development and pull-request CI.

This file describes release preparation and execution, not a blanket instruction to publish.
Apply build/signing steps when producing a release; a resource review or prose change uses the
applicable Quality checks. Local APK publication means copying a validated artifact into the
repository's output directory; it is not a GitHub Release. Remote publication, release-tag pushes
and system installation require applicable task authorization under [AGENTS](../AGENTS.md).
Existing authorization remains valid; do not request it again for each documented step.
Write user-facing release notes using the [format guide](../docs/release_notes_format_guide.md).

## Prerequisites

```bash
just bootstrap
just ci
```

The release boundary requires all four signing values, either in `apps/android/app/keystore.properties` using
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
just android release all
```

Use an explicit ABI for split builds. Omission selects arm64 at the Just entrypoint.
A JSON result is successful only after artifact validation and signature verification finish.

xtask performs the following as one graph:

1. validates baseline profile sources and signing configuration;
2. builds `lomo-native` for the target ABI(s) with the pinned NDK/API and release-android profile;
3. generates BoltFFI Kotlin/JNI into `native-bindings` / `com.lomo.nativebridge` and packages
   only `liblomo_native_jni.so` per ABI;
4. builds the Kotlin Toolchain release APK with non-destructive ABI stashing isolation;
5. verifies the single native library for every targeted ABI, absence of unselected ABIs or JNA/`libjnidispatch`/old
   `liblomo_native.so`, ELF architecture and dependencies, and embedded baseline profile assets;
6. signs with `apksigner` using environment-backed passwords and verifies the signature.

The final artifacts are `target/lomo/apk/release/Lomo-<version>-<abi>.apk` for release and
`target/lomo/apk/debug/Lomo-<version>-<abi>.apk` for debug, where `<version>` is the app `versionName`
from `app/module.yaml` and `<abi>` is the ABI tag (`all` for universal packages), as reported by `data.apk` in the command result. Build intermediates stay in the single configured shared
Kotlin build directory.

Tag workflow `.github/workflows/android_release.yml` invokes the same commands and publishes all split and universal release artifacts (`target/lomo/apk/release/Lomo-*.apk`). It must not grow a second native, Kotlin, signing, or APK validation implementation.

## Resource Review

`just ci` includes string-resource key parity. `just android release` additionally validates the selected
native ABIs, ELF metadata, BoltFFI-only packaging (`liblomo_native_jni.so`), baseline profile
assets, signing, and the final APK signature.

The [parity checker](scripts/check_string_resource_parity.sh) owns the module/resource-root mapping.
It compares `string`, `plurals` and `string-array` keys between `values` and `values-zh-rCN` for
modules that own string resources. Do not create empty resource trees to satisfy a stale inventory.

No allowlist is currently needed. Key parity does not prove translation quality, placeholder
semantics, unused-resource cleanup, or Android resource merge behavior.

| Resource area | Owner | Risk | Release review |
| --- | --- | --- | --- |
| FileProvider paths | App release and share/update owners | Overbroad paths or stale cache files can expose unintended content. | Verify `file_paths.xml` exposes only generated share images and validated update APKs; grants are user-driven and stale files are cleaned. |
| Backup and data extraction | App release and data/security owners | Source extraction rules can be mistaken for active backup policy. | Inspect the merged manifest for `allowBackup`, `fullBackupContent`, and `dataExtractionRules`; record the intended cloud-backup and device-transfer behavior. |
| Locale config and string parity | App release and i18n reviewer | Locale declarations and cross-module copy can drift. | Verify every shipped locale has complete keys and that permission, sync, recovery, update, and destructive-action copy has equivalent meaning. |
| Permission and recovery strings | App release and capability owner | Copy can promise behavior unavailable after OS denial. | Verify every permission has an owner, purpose, denial/retry path, and settings recovery route. |
| Widget preview resources | Widget and app release owners | Launcher previews can diverge from Glance behavior or localized copy. | Validate supported sizes, localized strings, entry actions, and launcher rendering. |
| Shader and visual fallback resources | Update and UI owners | API 33 shader loading or compilation can break update progress. | Verify the API gate, reduced-animation behavior, pre-33 path, and usable fallback when the shader is unavailable. |

These are evidence checks performed by the reviewer, not requests for a new user approval per row.
Product consent and permission flows remain part of the behavior being verified.

Before shipping backup, migration, credential, or restore changes:

1. Inspect the merged release manifest for backup and extraction attributes.
2. Record whether cloud backup and device transfer are intentionally disabled or scoped.
3. Re-check every credential, sync, migration, and workspace setting that would enter that scope.

## TUI Release (Desktop)

`v*` tags belong to Android. TUI releases use `tui-v<semver>` tags and the
`TUI Release` workflow, which runs the `ci-rust fast` host gate once on Linux, then
builds and packages `lomo-tui` natively per target. Each matrix job runs the
`lomo-platform-fs` contract suite on the real kernel before packaging, so a backend
that only compiles cannot ship.

The target/runner matrix is owned by
[the TUI workflow](../.github/workflows/tui_release.yml); do not duplicate it in agent instructions.

Every archive carries the binary, `LICENSE`, shell completions (bash/zsh/fish,
plus PowerShell on Windows), and a `<file>.sha256` checksum; the `publish` job
attaches all of them to the GitHub Release.

Once a TUI release is authorized and its artifacts are verified, create and push the corresponding
`tui-v<semver>` tag. A tag command example does not itself authorize publication.

`workflow_dispatch` repackages the Cargo.toml version without publishing a release.
macOS archives are unsigned — Gatekeeper quarantine is cleared by the user
(`xattr -d com.apple.quarantine lomo`); notarization is a future step, not a gate.

`apps/tui/packaging/arch/lomo-bin/PKGBUILD` (`lomo-bin`) repackages that tarball. After each
release bump `pkgver` and refresh `sha256sums` with `updpkgsums` (pacman-contrib) before pushing
to AUR.

`apps/tui/packaging/arch/lomo-local/PKGBUILD` (`lomo-local`) packages the worktree build at
`target/release/lomo` for local iteration — `pkgver()` tracks `HEAD`, no network fetch:

Build/package explicitly when local packaging is requested:

```bash
cargo build -p lomo-tui --release --locked
(cd apps/tui/packaging/arch/lomo-local && makepkg -f)
```

`install-tui` is retired: quality commands do not enter sudo/pacman installation prompts.
Installing the resulting package is a separate system operation.
