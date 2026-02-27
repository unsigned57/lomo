# Lomo

[English](README.md) | 中文

Lomo 是一款基于 Jetpack Compose 和 Material 3 构建的 Android 本地优先 Markdown 备忘录应用。
它将你的笔记作为纯文本 `.md` 文件保存在本地的设备上——没有任何**数字围栏**，支持 Git 备份，或者你可以使用任何你喜欢的方式（如 Syncthing, Nextcloud 等）进行同步。

<p align="center">
  <img src="docs/screenshots/01_menu.png" width="32%" />
  <img src="docs/screenshots/02_home.png" width="32%" />
  <img src="docs/screenshots/03_detail.png" width="32%" />
</p>

## 为什么开发 Lomo？

Lomo 的灵感来自于许多优秀的前辈，如 **Memos**、**Flomo**、**Moe-Memos** 以及 Obsidian 的 **Thino** 插件。Lomo 这个名字本身就是 "**Lo**cal Me**mo**"（本地备忘录）的缩写，或者说是去掉 *F(Foreign,外部云服务器)* 的 Flomo以及**Lo**calsend + **Me**mo的结合体。

为什么要重复造轮子？
大多数现有的解决方案都需要服务器或网络连接。我想要类似 "Memos" 的体验——轻量级、带时间戳的碎片化记录——但是必须是**纯离线**的，并且基于本地 Markdown 文件 (markdown 作为一种通用的可迁移的格式显然是最佳选择)。

很长一段时间以来，我一直依赖 Obsidian 中的 Thino 插件。虽然 Thino 满足了基本需求，但 Obsidian 的移动端客户端感觉比较沉重，而且我发现该插件在移动端的 UI/UX 不够流畅和精致。

**兼容性**：Lomo 完全兼容 Thino 的日记文件格式。你完全可以把它当作是你 Thino 数据的独立原生 Android 客户端。

> **关于开发的说明**：本项目几乎完全由 **Google Antigravity** 与 **Codex** 构建。由于 Lomo 是根据我的特定需求定制的，只要它还是我日常工具链的一部分，我就会一直维护它。如果你对 AI 生成代码的稳定性有顾虑，欢迎 Fork 并根据你的需求进行修改。

## 功能特性

- **本地纯文本**：所有备忘录都存储为标准的 Markdown 文件。
- **Material 3 设计**：简洁现代的 UI，支持动态取色。
- **标签管理**：使用 `#tags` 组织笔记。支持嵌套标签，如 `#tag1/tag2`。
- **回顾工具**：
  - **热力图**：GitHub 风格的写作习惯贡献图。
  - **每日回顾**：回顾“当年今日”你写了什么。
- **桌面小组件**：主屏幕小组件，支持快速记录和查看最近笔记。
- **搜索**：支持全文索引搜索。
- **语音记录**：支持语音记录。
- **局域网分享**：支持通过局域网将笔记分享到其他lomo应用。
- **Git 备份**：支持通过 Git 将笔记备份至 Github。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **架构**: MVVM + Clean Architecture (Domain/Data/UI 分层)
- **依赖注入**: Hilt
- **异步**: Coroutines & Flow
- **数据**:
  - 基于文件系统的存储 (Storage Access Framework)
  - Room (用于 FTS 索引和缓存)

## 构建指南

### 前置要求
- JDK 25
- Android SDK API 36

### 常用命令

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest
```

## 安装设置

1. 直接在 Releases 页面下载最新 APK 安装包。
2. 或者，手动构建：
   1. 使用 Android Studio (推荐 Ladybug 或更新版本) 打开项目。
   2. 同步 Gradle。
   3. 在模拟器或真机上构建并运行 (Min SDK 28)。
4. 首次启动时，选择一个本地文件夹来存储你的备忘录。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可证。
