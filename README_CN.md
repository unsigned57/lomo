<p align="center">
  <img src="docs/logo.png" width="96" height="96" alt="Lomo logo" />
</p>

<h1 align="center">Lomo</h1>

<p align="center">
  <a href="README.md">English</a> | 中文
</p>

<p align="center">
  <strong>本地优先的 Android Markdown 备忘录——无云端围栏。</strong>
</p>

<p align="center">
  <a href="https://github.com/unsigned57/lomo/releases/latest"><img src="https://img.shields.io/github/v/release/unsigned57/lomo?label=release&style=flat-square" alt="Release" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" />
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

1. 从 [Releases](https://github.com/unsigned57/lomo/releases/latest) 下载最新 APK
2. 安装到 Android 设备（Min SDK 26）
3. 首次启动时，选择一个本地文件夹存放备忘录

从源码构建见下方 **构建指南**。

## 赞助

如果 Lomo 对你有帮助，可以在这里支持项目：[赞助页面](docs/sponsor.md)。

<details>
<summary>技术栈</summary>

- **语言：** Kotlin
- **UI：** Jetpack Compose（Material 3）
- **架构：** MVVM + Clean Architecture（Domain / Data / UI）
- **依赖注入：** Koin
- **异步：** Coroutines & Flow
- **数据：**
  - 基于文件系统的存储（Storage Access Framework）
  - Room（FTS 索引与缓存）

</details>

<details>
<summary>构建指南</summary>

**前置要求：** JDK 26 · Android SDK API 37 · just

```bash
# 构建 Debug APK
just debug

# 运行单元测试
just test

# 完整合并前检查
just quality
```

也可使用 Android Studio（推荐 Ladybug 或更新版本）打开项目，使用 Kotlin Toolchain 项目模型，在模拟器或真机上构建运行。

</details>

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可证。
