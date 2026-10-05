<p align="center">
  <img src="docs/logo.png" width="96" height="96" alt="Lomo logo" />
</p>

<h1 align="center">Lomo</h1>

<p align="center">
  English | <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <strong>Local-first Markdown memos on Android and Linux — no cloud lock-in.</strong>
</p>

<p align="center">
  <a href="https://github.com/unsigned57/lomo/releases/latest"><img src="https://img.shields.io/github/v/release/unsigned57/lomo?label=release&style=flat-square" alt="Release" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/platform-Linux-FCC624?style=flat-square&logo=linux&logoColor=black" alt="Linux" />
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License GPL-3.0" />
  <img src="https://img.shields.io/badge/minSdk-26-informational?style=flat-square" alt="minSdk 26" />
</p>

<p align="center">
  <a href="https://github.com/unsigned57/lomo/releases/latest"><b>Download APK</b></a>
  ·
  <a href="docs/sponsor_en.md">Sponsor</a>
</p>

<p align="center">
  <img src="docs/screenshots/01_menu.png" width="32%" alt="Menu" />
  <img src="docs/screenshots/02_home.png" width="32%" alt="Home" />
  <img src="docs/screenshots/03_detail.png" width="32%" alt="Detail" />
</p>
<p align="center"><sub>Menu · Home · Detail</sub></p>

## Features

#### Capture

- **Local plain text** — memos are standard Markdown files
- **Voice recording** — capture thoughts hands-free
- **Home screen widgets** — quick capture and recent notes

#### Organize

- **Tags** — organize with `#tags`, including nested tags like `#tag1/tag2`
- **Full-text search** — indexed local search
- **Material 3** — clean UI with dynamic color
- **Linux TUI** — `lomo` on a terminal: timeline, tasks, review, stats, media, trash, settings

#### Review

- **Heatmap** — GitHub-style contribution graph for writing habits
- **Daily review** — flashback to this day in previous years

#### Sync & share

- **S3 backup (recommended)** — object storage with end-to-end encryption
- **Git / WebDAV** — optional built-in backup paths
- **LAN sharing** — share notes to other Lomo devices on the local network

## How should you sync?

Notes live entirely on your device. Pick one path:

1. **S3 (recommended)** — the only built-in option with end-to-end encryption; what the author uses day to day, and the most actively maintained
2. **Any file sync** — Syncthing, Nextcloud, or anything else that syncs the local folder
3. **Git / WebDAV** — built-in backups (WebDAV has mainly been tested with Nutstore)

<details>
<summary>Obsidian / Rclone notes</summary>

Lomo’s S3 sync is compatible with the Obsidian Remotely Save plugin. That plugin has not been actively maintained for a long time, so for Android-to-Android syncing it is better to point Lomo’s S3 sync at the root of your Obsidian vault. Custom folder sync is supported; on Linux, Rclone is usually the better desktop companion.

</details>

## Why Lomo?

Lomo draws inspiration from excellent predecessors like **Memos**, **Flomo**, **Moe-Memos**, and the **Thino** plugin for Obsidian. The name itself is a nod to "**Lo**cal Me**mo**" (or simply Flomo without the *F*—Foreign/Cloud).

Why build another one?
Most existing solutions require a server or network connection. I wanted the "Memos experience"—lightweight, timestamped thoughts—but strictly **offline** and based on local Markdown files (proven to be the most universal and portable format).

For a long time, I relied on the Thino plugin in Obsidian. While Thino covers the basics, Obsidian's mobile client can feel heavy, and I found the plugin's mobile UI/UX lacking in snappiness and polish.

**Compatibility**: Lomo is fully compatible with Thino's daily note format. You can effectively treat it as a standalone, native Android client for your Thino data.

> **A Note on Development**: This project was built almost entirely using AI-assisted development tools. Since Lomo is tailored to my specific workflow, I plan to maintain it for as long as it remains part of my daily toolchain. If you have concerns about the stability of AI-generated code, feel free to fork and adapt it to your needs.

## Install

**Android**

1. Download the latest APK from [Releases](https://github.com/unsigned57/lomo/releases/latest)
2. Install on an Android device (Min SDK 26)
3. On first launch, choose a local folder for your memos

**Desktop TUI (Linux, macOS, Windows)**

1. Grab the archive for your platform from GitHub Releases, verify the `.sha256` checksum, and unpack `lomo`:
   - Linux x86_64: `lomo-*-x86_64-unknown-linux-gnu.tar.gz` (Arch users can instead run `makepkg -si` inside `apps/tui/packaging/arch/lomo-bin`)
   - macOS Apple Silicon / Intel: `lomo-*-aarch64-apple-darwin.tar.gz` / `lomo-*-x86_64-apple-darwin.tar.gz` — untagged builds are unsigned; if Gatekeeper blocks the binary, clear it with `xattr -d com.apple.quarantine lomo` or allow it in System Settings
   - Windows x86_64: `lomo-*-x86_64-pc-windows-msvc.zip` — run `lomo.exe` in Windows Terminal, PowerShell, or a console host
   - Or run from a checkout on any platform: `cargo run -p lomo-tui --release --locked`
2. Pass a directory on the command line to bind it as the workspace: `lomo /path/to/notes`. First run writes `config.toml` under the platform config dir with `workspace` set to `~/Notes`; you can still start from `config/config.toml.example`:
   - Linux: `$XDG_CONFIG_HOME/lomo/` (usually `~/.config/lomo/`)
   - macOS: `~/Library/Application Support/lomo/`
   - Windows: `%APPDATA%\lomo\`
3. Set `workspace` to your notes directory. Editor priority is that config, then `$VISUAL`, then `$EDITOR` (never a vim default)
4. Linux requires `$XDG_RUNTIME_DIR`; macOS and Windows fall back to a private `run` directory. Missing clipboard or player fails closed instead of pretending success. The media opener defaults to `xdg-open` on Linux, `open` on macOS, and `Start-Process` via PowerShell on Windows — override with `player = [...]` in `config.toml`
5. Diagnostics: `LOMO_LOG=debug lomo` writes `lomo.log` under the state dir (`$XDG_STATE_HOME/lomo/` on Linux, `~/Library/Application Support/lomo/` on macOS, `%LOCALAPPDATA%\lomo\` on Windows); a crash restores the terminal and writes a report under `<state>/crash/`

The home screen is a centered, single-column memo feed.

- `Enter` opens full text; `Esc` restores the reading position
- `n` opens a recoverable multiline draft; `Ctrl+S` saves; `Ctrl+E` hands the draft to an external editor; `e` edits an existing memo externally
- `/` searches (`Ctrl+F` switches fulltext / fuzzy + pinyin); `:` opens the grouped command palette (this item, filters, pages, everything else); `.` shows only the current item's actions; `e` `m` `d` edit, pin and trash directly
- `Esc` peels one layer at a time and the hint bar always names what it will do next
- Terminals with an image protocol render images in full text

The desktop TUI has no built-in Git/WebDAV/S3 sync or LAN sharing in this round; copy the Markdown workspace (and `.lomo`) or use an external file sync tool.

Building from source is covered under **Building** below.

## Support

If Lomo is useful to you, you can support the project here: [Sponsor page](docs/sponsor_en.md).

<details>
<summary>Tech stack</summary>

- **Languages:** Kotlin + Rust (Rust native core via JNI). The desktop TUI is a host binary with no JNI.
- **UI:** Jetpack Compose (Material 3) on Android; Ratatui terminal UI on desktop
- **Architecture:** MVVM + Clean Architecture (Domain / Data / UI) on Android; TEA composition root on desktop
- **DI:** Koin (Android)
- **Async:** Coroutines & Flow (Android)
- **Data:**
  - Markdown workspace storage through the Storage Access Framework on Android, host filesystem on desktop
  - Rust-owned SQLite derived index and durable `.lomo` state

</details>

<details>
<summary>Building</summary>

**Prerequisites:** Rustup, just, and the JDK/Android SDK versions specified by the
[app module](apps/android/app/module.yaml). See [canonical build inputs](quality/README.md#canonical-build-inputs).

```bash
# Install pinned Rust tools, targets, and Android NDK
just bootstrap

# Build and validate Debug APK
just android debug

# Run the TUI
cargo run -p lomo-tui --release --locked

# Worktree iteration gate (add --tests-only to skip static analysis)
just dev

# Code handoff / merge gates (see Quality for applicability)
just check
just ci
```

`native-bindings/src` and native `.so` files are generated build outputs. Builds must work from a
clean checkout; existing local edits are also supported and must be preserved. Signed release builds
use `just android release` and require the keystore configuration in [Release](quality/release.md).

Production FFI identity is `native-bindings` / `com.lomo.nativebridge` / `liblomo_native_jni.so`.
Capability fixtures live in [fixtures](fixtures/README.md); module authority lives in
[Architecture](ARCHITECTURE.md). The [documentation index](docs/README.md) maps each document to its owner.

Android Studio may still be used for editing and device work, but repository verification and
native generation must use the commands above.

</details>

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
