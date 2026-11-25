# Release Contract

Read this file for release signing, artifact validation, and release tag workflows.
[Quality](README.md) owns gate evidence; [Architecture](../ARCHITECTURE.md) owns module authority;
[AGENTS.md](../AGENTS.md) owns authorization (documentation examples never authorize publishing or
pushing release tags). Write user-facing release notes using
[the format guide](../docs/release_notes_format_guide.md).

## Android release

### Prerequisites and signing

```bash
just bootstrap
just ci   # required when no green CI receipt exists for the commit
```

`settings.android.signing` in [`apps/android/app/module.yaml`](../apps/android/app/module.yaml)
signs the release APK during build using `apps/android/app/keystore.properties`:

```text
storeFile=<path>        # relative paths resolve from apps/android/app/
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

Missing or incomplete `keystore.properties` fails closed before building (no debug signing or
interactive fallback). Release workflows materialize the same file from repository secrets.

### Build and validation

```bash
just build android release all
```

Pass an explicit ABI (`arm64`, `armv7`, `x86`, `x86_64`) for split builds (default `arm64`).
`lomo-xtask` executes one unified graph:

1. validates measured Baseline/Startup Profile receipts/syntax (`profgen`) and `keystore.properties`;
2. compiles `lomo-native` (`release-android`, immediate abort + `-Z build-std`);
3. regenerates BoltFFI Kotlin/JNI (`native-bindings` / `com.lomo.nativebridge`) and stages `liblomo_native_jni.so`;
4. builds and signs the APK via the Kotlin toolchain with non-destructive ABI stashing;
5. verifies targeted ABI ELF files, absence of unselected ABIs or JNA/legacy libraries, and embedded
   baseline profile assets (`assets/dexopt/baseline.prof`);
6. verifies the APK signature via `apksigner verify` before publishing under `target/lomo/artifacts/android/release/`:
   - Universal updater APK: `lomo-v<version>-vc<versionCode>.apk` (metadata parsed from `app/module.yaml`)
   - Split APKs: `Lomo-<version>-<abi>.apk` (debug APKs use `artifacts/android/debug/Lomo-<version>-<abi>.apk`)

### Tag workflow and gate reuse

Tag pushes (`v*`) trigger [`.github/workflows/android_release.yml`](../.github/workflows/android_release.yml),
which requires `docs/releases/<tag>.md` and verifies via the Actions API that `architecture_checks.yml`
(`check`, `rust-host`, `android-kotlin`, `native` matrix, `quality`) already succeeded on the exact
commit (default branch pushes run full gates automatically; other refs need a manual full dispatch
first). Active runs wait up to 90 minutes; missing/failed/cancelled runs block release without
re-running checks.

While the quality gate waits, four runners build the shipping `release-android` libraries and
upload one library and build stamp per ABI. After the gate passes, xtask validates all four
artifacts against the checkout, shipping profile, and library digests. It then uses those inputs to
build, sign, and verify the universal APK and the four split APKs. The publish job downloads the
signed APK artifact, so a publish-only retry can use the existing packages. A manual dispatch
builds and uploads the signed artifact for the selected commit. Never duplicate
native/signing/validation logic outside xtask.

### In-place upgrade compatibility

Official releases keep package ID `com.lomo.app` and the original release signing certificate so
in-app updates (`lomo-v<version>-vc<versionCode>.apk`) and `adb install -r` succeed in place. When
validating an upgrade on device, compare `apksigner verify --print-certs` between the previous release
and candidate APK, test `adb install -r`, and verify the installed version, launch, and data policy.

## Resource review

`just ci` checks `values` / `values-zh-rCN` parity via
[the parity checker](scripts/check_string_resource_parity.sh) across modules owning strings. Do not
create empty resource directories to satisfy a stale list.

| Resource area | Owner | Release check |
| --- | --- | --- |
| FileProvider paths | App release, share/update | Confirm `file_paths.xml` exposes only share images and validated update APKs with cleanup |
| Backup & extraction | App release, data/security | Inspect merged manifest `allowBackup`, `fullBackupContent`, `dataExtractionRules`; verify credential/sync/workspace scope before shipping backup/restore changes |
| Locale & string parity | App release, i18n | Verify complete locale keys and equivalent meaning for permission, sync, recovery, update, and destructive copy |
| Permission & recovery | App release, capability owner | Verify every permission documents purpose, denial fallback, and settings recovery path |
| Widget previews | Widget, app release | Validate supported sizes, localized strings, entry actions, and launcher rendering |
| Shader & fallback | Update, UI | Verify API 33 gate, reduced-animation handling, and pre-33 fallback |

## TUI release (desktop)

TUI releases use `tui-v<semver>` tags and [`.github/workflows/tui_release.yml`](../.github/workflows/tui_release.yml),
which runs host checks on Linux and builds/verifies `lomo-platform-fs` contracts per target OS/kernel
before packaging archives (`lomo` + `LICENSE` + shell completions + `.sha256`). Unsigned macOS
archives require user Gatekeeper clearance (`xattr -d com.apple.quarantine lomo`). `workflow_dispatch`
repackages the `apps/tui/Cargo.toml` version without publishing a release.

Arch packaging recipes live under `apps/tui/packaging/arch/`:
- `lomo-bin/PKGBUILD`: after a release, update `pkgver` and `updpkgsums`.
- `lomo-local/PKGBUILD`: packages local `target/lomo/artifacts/tui/release/lomo` (`just build tui release && (cd apps/tui/packaging/arch/lomo-local && makepkg -f)`). Installing system packages is always a separate manual step.
