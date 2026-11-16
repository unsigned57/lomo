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
# Complete local delivery verification when no green CI receipt exists for this commit:
just ci
```

Release signing is owned by the Kotlin toolchain: `settings.android.signing` in
`apps/android/app/module.yaml` signs the release APK during the build. Its single input is
`apps/android/app/keystore.properties` with the camelCase keys:

```text
storeFile=<path>        # relative paths resolve from apps/android/app/
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

Partial or missing configuration fails before the release build. No default key, interactive guess,
or debug signing fallback exists; the release workflow materializes the same file from secrets.

## Build

```bash
just build android release all
```

Use an explicit ABI for split builds. Omission selects arm64 at the Just entrypoint.
A JSON result is successful only after artifact validation and signature verification finish.

xtask performs the following as one graph:

1. validates measured Baseline/Startup Profile receipt hashes and SDK grammar, plus the `keystore.properties` signing configuration;
2. builds `lomo-native` for the target ABI(s) with the pinned NDK/API and release-android profile;
3. generates BoltFFI Kotlin/JNI into `native-bindings` / `com.lomo.nativebridge` and packages
   only `liblomo_native_jni.so` per ABI;
4. builds the Kotlin Toolchain release APK — signed in-build by `android.signing` — with
   non-destructive ABI stashing isolation;
5. verifies the single native library for every targeted ABI, absence of unselected ABIs or JNA/`libjnidispatch`/old
   `liblomo_native.so`, ELF architecture and dependencies, and embedded baseline profile assets;
6. verifies the toolchain-applied signature with `apksigner verify` before publishing.

The universal release artifact is
`target/lomo/artifacts/android/release/lomo-v<version>-vc<versionCode>.apk`.
Its name is the installed Android updater's metadata contract: both version fields come from
`app/module.yaml`. Split release artifacts use
`target/lomo/artifacts/android/release/Lomo-<version>-<abi>.apk`. Debug artifacts use
`target/lomo/artifacts/android/debug/Lomo-<version>-<abi>.apk`, where `<version>` is the app `versionName`
from `app/module.yaml` and `<abi>` is the ABI tag (`all` for universal packages), as reported by `data.apk` in the command result. Build intermediates stay in the single configured shared
Kotlin build directory.

Tag workflow `.github/workflows/android_release.yml` checks the release notes before starting any
expensive gate, runs
the Quality Checks workflow as its gate (all surfaces, in parallel jobs), then builds the same
commands and publishes both split and universal release artifacts. Push the release commit first
and let Quality Checks pass; the tag then repeats an already-green gate instead of discovering
failures after the release build has started. The tag forces full coverage even if path selection
skipped delivery jobs on the branch. Its gate and signed build restore compatible build snapshots
and save completed compilation after failures; pinned tools are saved immediately after bootstrap.
The universal package alone is discoverable by the in-app
updater; split packages remain available for manual installation. It must not grow a second native,
Kotlin, signing, or APK validation implementation.

### Android 1.6.2 → 2.0.0

The released 1.6.2 package is `com.lomo.app`, version code 46. The 2.0.0 manifest uses the same
package and version code 47. Updating in place requires the original release signing certificate;
a debug or newly generated key cannot update the installed release.

1. Run the release gates and `just build android release all` with the original signing key.
   Compare `apksigner verify --print-certs` output for the released 1.6.2 APK and the built APK.
2. Publish the verified `lomo-v2.0.0-vc47.apk` in the Android `v2.0.0` GitHub release. The old
   updater reads `/releases/latest`, so this must be a published stable release, not a draft or
   prerelease. Remote publication requires task authorization.
3. In 1.6.2, use Settings → Check for updates, download the update, allow installation from Lomo
   if Android requests it, and confirm the system update dialog. Cancellation leaves 1.6.2 installed.
   A manual download of the same universal APK uses the same Android package update checks.
4. Launch 2.0.0 and verify the installed version and a usable editor. This upgrade does not promise
   migration of old application state. The user may configure the workspace and preferences again;
   there is no automatic deletion of user workspace documents or app data.

For device evidence, install the released 1.6.2 APK on a dedicated test device, launch it, then use
`adb install -r <2.0.0 APK>` and launch again. Record the signer digests, install result, installed
version, launch outcome and whether app data was retained or explicitly cleared. This proves package
replacement and startup; it does not prove GitHub download or system installer consent UI.

## Resource Review

`just ci` includes string-resource key parity. `just build android release` additionally validates the selected
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
`target/lomo/artifacts/tui/release/lomo` for local iteration — `pkgver()` tracks `HEAD`, no network fetch:

Build/package explicitly when local packaging is requested:

```bash
just build tui release
(cd apps/tui/packaging/arch/lomo-local && makepkg -f)
```

`install-tui` is retired: quality commands do not enter sudo/pacman installation prompts.
Installing the resulting package is a separate system operation.
