# Lomo

English | [中文](README_CN.md)

Lomo is a local-first, Markdown-based memo application for Android, built with Jetpack Compose and Material 3.
It manages your notes as plain `.md` files on your device—no **digital walled gardens**, just local files you can sync however you want (Syncthing, Nextcloud, etc.).

<p align="center">
  <img src="docs/screenshots/01_menu.png" width="32%" />
  <img src="docs/screenshots/02_home.png" width="32%" />
  <img src="docs/screenshots/03_detail.png" width="32%" />
</p>

## Why Lomo?

Lomo draws inspiration from excellent predecessors like **Memos**, **Flomo**, **Moe-Memos**, and the **Thino** plugin for Obsidian. The name itself is a nod to "**Lo**cal Me**mo**" (or simply Flomo without the *F*—Foreign/Cloud), as well as a blend of **Lo**calsend and **Me**mo.

Why build another one?
Most existing solutions require a server or network connection. I wanted the "Memos experience"—lightweight, timestamped thoughts—but strictly **offline** and based on local Markdown files (proven to be the most universal and portable format).

For a long time, I relied on the Thino plugin in Obsidian. While Thino covers the basics, Obsidian's mobile client can feel heavy, and I found the plugin's mobile UI/UX lacking in snappiness and polish.

**Compatibility**: Lomo is fully compatible with Thino's daily note format. You can effectively treat it as a standalone, native Android client for your Thino data.

> **A Note on Development**: This project was built almost entirely using **Google Antigravity** and **Codex**. Since Lomo is tailored to my specific workflow, I plan to maintain it for as long as it remains part of my daily toolchain. If you have concerns about the stability of AI-generated code, feel free to fork and adapt it to your needs.

## Features

- **Local & Plain Text**: All memos are stored as standard Markdown files.
- **Material 3 Design**: Clean, modern UI with dynamic theming.
- **Tag Management**: Organize notes with `#tags`. Supports nested tags, e.g., `#tag1/tag2`.
- **Review Tools**:
  - **Heatmap**: GitHub-style contribution graph for your writing habits.
  - **Daily Review**: Flashback to what you wrote on this day in previous years.
- **Widgets**: Home screen widgets for quick capture and recent notes.
- **Search**: Full-text search with indexing.
- **Voice Recording**: Support for voice memos.
- **LAN Sharing**: Support sharing notes to other Lomo apps via local network.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Clean Architecture (Domain/Data/UI Separation)
- **DI**: Hilt
- **Async**: Coroutines & Flow
- **Data**: 
  - File-system based storage (Storage Access Framework)
  - Room (for FTS indexing and caching)

## Building

### Prerequisites
- JDK 25
- Android SDK API 36

### Common Commands

```bash
# Build Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew testDebugUnitTest
```

## Setup

1. Download the latest APK from the Releases page.
2. Or, build manually:
   1. Open the project in Android Studio (Ladybug or newer recommended).
   2. Sync Gradle.
   3. Build and Run on an Emulator or Device (Min SDK 28).
4. On first launch, select a local folder to store your memos.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
