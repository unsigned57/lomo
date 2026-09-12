<p align="center">
  <img src="docs/logo.png" width="96" height="96" alt="Lomo logo" />
</p>

<h1 align="center">Lomo</h1>

<p align="center">
  <a href="README.md">English</a> | 中文
</p>

<p align="center">
  <strong>本地优先的 Android 与 Linux Markdown 备忘录——无云端围栏。</strong>
</p>

<p align="center">
  <a href="https://github.com/unsigned57/lomo/releases/latest"><img src="https://img.shields.io/github/v/release/unsigned57/lomo?label=release&style=flat-square" alt="Release" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/platform-Linux-FCC624?style=flat-square&logo=linux&logoColor=black" alt="Linux" />
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License GPL-3.0" />
  <img src="https://img.shields.io/badge/minSdk-26-informational?style=flat-square" alt="minSdk 26" />
</p>

<p align="center">
  <a href="https://github.com/unsigned57/lomo/releases/latest"><b>下载 APK</b></a>
  ·
  <a href="docs/sponsor.md">赞助</a>
</p>

<p align="center">
  <img src="docs/screenshots/01_menu.png" width="32%" alt="菜单" />
  <img src="docs/screenshots/02_home.png" width="32%" alt="首页" />
  <img src="docs/screenshots/03_detail.png" width="32%" alt="详情" />
</p>
<p align="center"><sub>菜单 · 首页 · 详情</sub></p>

## 功能特性

#### 记录

- **本地纯文本** — 备忘录存为标准 Markdown 文件
- **语音记录** — 快速录入语音备忘
- **桌面小组件** — 主屏幕快速记录与查看最近笔记

#### 整理

- **标签** — 使用 `#tags` 组织笔记，支持嵌套如 `#tag1/tag2`
- **全文搜索** — 本地索引加速检索
- **Material 3** — 简洁现代 UI，支持动态取色
- **Linux TUI** — 终端程序 `lomo`：时间流、任务、回顾、统计、附件、回收站、设置

#### 回顾

- **热力图** — GitHub 风格的写作习惯贡献图
- **每日回顾** — 回顾「当年今日」你写了什么

#### 同步与分享

- **S3 备份（推荐）** — 标准对象存储，支持端对端加密
- **Git / WebDAV** — 可选内置备份方式
- **局域网分享** — 将笔记分享到其他 Lomo 设备

## 如何同步笔记

笔记完全存储在本地。任选其一：

1. **S3（推荐）** — 唯一支持端对端加密的内置选项；作者日常使用，维护最积极
2. **任意文件同步** — Syncthing、Nextcloud 等同步本地文件夹
3. **Git / WebDAV** — 内置备份（WebDAV 目前主要测试了坚果云）

<details>
<summary>Obsidian / Rclone 说明</summary>

Lomo 的 S3 同步兼容 Obsidian 的 Remotely Save 插件。该插件已经很久没有更新维护，安卓间同步时推荐使用 Lomo 的 S3 直接同步 Obsidian 的 vault 根目录。S3 支持自定义文件夹同步；在 Linux 上则更推荐直接使用 Rclone。

</details>

## 为什么是 Lomo？

想要类似 Memos / Flomo 的轻量、带时间戳的碎片化记录，但必须**纯离线**，并以本地 Markdown 为唯一真相源。名字来自 **Lo**cal Me**mo**。Lomo 完全兼容 **Thino** 日记文件格式，可当作 Thino 数据的独立原生 Android 客户端。

<details>
<summary>关于维护与开发</summary>

**维护：** Lomo 按作者自身工作流定制，并高强度自用。只要它仍是日常工具链的一部分，就会持续维护。

**开发：** 本项目几乎完全由 **Google Antigravity** 与 **Codex** 构建。若对 AI 生成代码的稳定性有顾虑，欢迎 Fork 并按需修改。

</details>

## 安装

**Android**

1. 从 [Releases](https://github.com/unsigned57/lomo/releases/latest) 下载最新 APK
2. 安装到 Android 设备（Min SDK 26）
3. 首次启动时，选择一个本地文件夹存放备忘录

**Linux（x86_64 TUI）**

1. 在本仓库执行 `just package-linux`，生成 `target/lomo/dist/lomo-linux-x86_64.tar.gz`。从源码运行 TUI：`just tui`（可选工作区路径：`just tui /path/to/notes`）。
2. 解压后首次运行会写入 `$XDG_CONFIG_HOME/lomo/config.toml`（通常是 `~/.config/lomo/config.toml`），默认 `workspace` 为 `~/Notes`。也可启动时指定目录：`lomo /path/to/notes`。仍可从 `config/config.toml.example` 复制后自行修改。
3. 将 `workspace` 指到笔记目录。编辑器优先级为该配置，其次 `$VISUAL`，再次 `$EDITOR`（绝不默认 vim）
4. 必须设置 `$XDG_RUNTIME_DIR`。缺少剪贴板或播放器时失败封闭，不伪造成功

主界面是居中的单列正文流。Enter 阅读全文，Esc 恢复阅读位置；`n` 展开可恢复的多行速记，`Ctrl+S` 保存，`Ctrl+E` 将草稿交给外部编辑器，`e` 在外部编辑器修改已有记录。`/`、`t`、`c` 组合关键词、标签和日期筛选。`Ctrl+P` 打开可搜索的功能菜单，`.` 打开记录操作。支持图片协议的终端可在全文页显示图片。

本轮 Linux 不含内置 Git/WebDAV/S3 同步与局域网分享；跨端交换请复制 Markdown 工作区（含 `.lomo`）或使用外部文件同步。

从源码构建见下方 **构建指南**。

## 赞助

如果 Lomo 对你有帮助，可以在这里支持项目：[赞助页面](docs/sponsor.md)。

<details>
<summary>技术栈</summary>

- **语言：** Kotlin + Rust（生产 native 基础设施为 BoltFFI/JNI；阶段 0 的 UniFFI/JNA 仅作历史证据）。Linux TUI 是无 JNI 的宿主二进制
- **UI：** Android 为 Jetpack Compose（Material 3）；Linux 为 Ratatui 终端界面
- **架构：** Android 为 MVVM + Clean Architecture（Domain / Data / UI）；Linux 为 TEA 组合根
- **依赖注入：** Koin（Android）
- **异步：** Coroutines & Flow（Android）
- **数据：**
  - Android 通过 Storage Access Framework 管理 Markdown 工作区，Linux 使用 POSIX 文件
  - Rust 管理的 SQLite 派生索引与耐久 `.lomo` 状态

</details>

<details>
<summary>构建指南</summary>

**前置要求：** JDK 26 · Android SDK API 37 · Rustup · just

```bash
# 安装固定版本的 Rust 工具、targets 与 Android NDK
just bootstrap

# 构建并校验 Debug APK
just android debug

# Linux 宿主门禁（不调用 Android 工具链）与通用 TUI 归档
just check-linux
just tui
just package-linux

# 运行 Rust 与 Kotlin host tests
just test

# 迭代检查 / 完整合并前门禁
just check
just ci
```

`native-bindings/src` 与 native `.so` 都是可再生、被忽略的构建产物；clean checkout 是标准输入。
签名 release 使用 `just android release`，必须按 `quality/release.md` 明确提供 keystore 配置。

生产 FFI 身份为 `native-bindings` / `com.lomo.nativebridge` / `liblomo_native_jni.so`。
当前行为合同位于 `fixtures/contracts/`，可执行基线位于 `fixtures/baselines/`。

Android Studio 仍可用于编辑和设备调试，但仓库门禁与 native 生成必须使用上述命令。

</details>

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可证。
