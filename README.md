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

**Linux (x86_64 TUI)**

1. Build a generic archive from this repository with `just package-linux` (writes `target/lomo/dist/lomo-linux-x86_64.tar.gz`). Run the TUI from a checkout with `just tui` (optional workspace path: `just tui /path/to/notes`).
2. Extract it. First run writes `$XDG_CONFIG_HOME/lomo/config.toml` (usually `~/.config/lomo/config.toml`) with `workspace` set to `$HOME/Notes`. Pass a directory on the command line to bind that folder instead: `lomo /path/to/notes`. You can still start from `config/config.toml.example`.
3. Set `workspace` to your notes directory. Editor priority is that config, then `$VISUAL`, then `$EDITOR` (never a vim default)
4. `$XDG_RUNTIME_DIR` is required. Missing clipboard or player fails closed instead of pretending success

The home screen is a centered, single-column memo feed.

- `Enter` opens full text; `Esc` restores the reading position
- `n` opens a recoverable multiline draft; `Ctrl+S` saves; `Ctrl+E` hands the draft to an external editor; `e` edits an existing memo externally
- `/`, `t`, `c` combine search, tag, and date filters; `Ctrl+P` opens the searchable function menu; `.` opens memo actions
- Terminals with an image protocol render images in full text

Linux built-in Git/WebDAV/S3 sync and LAN sharing are not in this first round; copy the Markdown workspace (and `.lomo`) or use an external file sync tool.

Building from source is covered under **Building** below.

## Support

If Lomo is useful to you, you can support the project here: [Sponsor page](docs/sponsor_en.md).

<details>
<summary>Tech stack</summary>

- **Languages:** Kotlin + Rust (Rust native core via JNI). Linux TUI is a host binary with no JNI.
- **UI:** Jetpack Compose (Material 3) on Android; Ratatui terminal UI on Linux
- **Architecture:** MVVM + Clean Architecture (Domain / Data / UI) on Android; TEA composition root on Linux
- **DI:** Koin (Android)
- **Async:** Coroutines & Flow (Android)
- **Data:**
  - Markdown workspace storage through the Storage Access Framework on Android, POSIX files on Linux
  - Rust-owned SQLite derived index and durable `.lomo` state

</details>

<details>
<summary>Building</summary>

**Prerequisites:** JDK 26 · Android SDK API 37 · Rustup · just

```bash
# Install pinned Rust tools, targets, and Android NDK
just bootstrap

# Build and validate Debug APK
just android debug

# Linux host gate (no Android toolchain), TUI, and generic archive
just check-linux
just tui
just package-linux

# Run Rust and Kotlin host tests
just test

# Iterative / full pre-merge gates
just check
just ci
```

`native-bindings/src` and native `.so` files are generated build outputs; a clean checkout is the
expected input. Signed release builds use `just android release` and require explicit keystore
configuration documented in `quality/release.md`.

Production FFI identity is `native-bindings` / `com.lomo.nativebridge` / `liblomo_native_jni.so`.
Current contracts live in `fixtures/contracts/`; executable baselines live in `fixtures/baselines/`.

Android Studio may still be used for editing and device work, but repository verification and
native generation must use the commands above.

</details>

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
