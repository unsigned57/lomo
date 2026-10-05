# Lomo Release Notes Formatting Guide (发布日志格式化指南)

This guide documents the design standards, structure, and bilingual formatting rules for writing Lomo release notes. All future release notes must follow these guidelines to maintain visual consistency and clarity.
本指南记录了编写 Lomo 发布日志的设计标准、结构及双语格式规范。所有未来的发布日志均须遵循这些准则，以保持视觉一致性与清晰度。

This file owns release-note copy only. Build, signing and publishing follow the [Release Contract](../quality/release.md); writing notes does not authorize publication.
本文件只负责发布文案；构建、签名和发布遵循[发布规范](../quality/release.md)，撰写文案不代表获得发布授权。

---

## 📐 Overall Structure (整体结构)

Each release log has a header and summary, followed by whichever categories contain actual changes. Omit empty categories; never invent features or fixes to fill the format.
每份发布日志包含标题和概述，随后只保留有实际改动的分类。省略空分类，不得为填满格式编造功能或修复。

1.  **Header & Summary Paragraph (标题与概述段落)**: A high-level, cohesive bilingual paragraph summarizing the theme of the release.
2.  **New Features and Improvements (新功能与改进)**: A bilingual bulleted list detailing new features, UX enhancements, and performance optimizations.
3.  **Bug Fixes (缺陷修复)**: A bilingual bulleted list documenting fixed issues and code stabilization.

---

## ✍️ Formatting Rules (格式化细则)

### 1. Version Heading (版本标题)
Use Heading level 2 with the prefix `## Lomo v` followed by the semantic version number.
使用二级标题，前缀为 `## Lomo v`，后跟语义化版本号。
*   *Example (示例)*: `## Lomo v1.6.1`

### 2. Summary Paragraph (概述段落)
Directly below the heading, write two paragraphs: the first in English, and the second in Chinese.
在标题正下方，编写两段文字：第一段为英文，第二段为中文。
*   Do not use bullet points or lists in this section.
*   Summarize the release's user-visible outcomes. Mention a technical change only when it helps users understand compatibility, migration or how to use the product; omit internal classes, schemas and dependency inventories.
*   概述用户可感知的变化。技术说明仅用于解释兼容性、迁移或使用方式，不列举内部类、数据库结构或依赖清单。

### 3. Category Headings (分类标题)
Use Heading level 3 containing a prefix emoji, followed by the bilingual category titles separated by a slash `/`.
使用三级标题，包含一个前缀表情符号，后跟由斜杠 `/` 分隔的双语分类标题。
*   *New Features (新功能)*: `### ✨ New Features and Improvements / 新功能与改进`
*   *Bug Fixes (缺陷修复)*: `### 🐞 Bug Fixes / 缺陷修复`

### 4. Item Formatting (单项格式)
Each feature or bug fix must be presented as a bullet point using the following structure:
每个新功能或缺陷修复必须使用以下结构以列表项形式呈现：
*   **Bilingual Title (双语标题)**: Bolded, formatted as `**English Title / Chinese Title**` on its own line.
*   **English Description (英文说明)**: Starts on a new line immediately below the title. Focus on describing *what* has been done conceptually and its user-facing value. Do NOT include internal implementation details, code classes, specific package/dependency names, or internal architecture details (e.g., do NOT write `AndroidDynamicShortcutPublisher`, `ExternalAppCommandStore`, or MVVM details).
*   **Chinese Description (中文说明)**: Starts on the next line. Provide a fluent, natural translation of the English description.

*Example (示例)*:
```markdown
- **Floating Footer Panel / 侧边栏浮岛底座**
  Redesigned the sidebar layout by housing the settings and trash action buttons inside an elevated card.
  重新设计了侧边栏布局，将“设置”和“回收站”操作按钮收纳于独立的悬浮式圆角卡片中。
```

---

## 🌐 Linguistic Tone and Translation (语言风格与翻译)

1.  **No Implementation Details (杜绝实现细节)**:
    Do not expose internal class names, variables, package structure, or programming patterns. Focus on the user-facing outcome.
    不要公开内部代码类名、变量、包结构或编程设计模式。只描述对用户可见的实际功能改进和成果。
    *   *Incorrect (错误)*: `Replaced shortcuts.xml with AndroidDynamicShortcutPublisher and utilized ExternalAppCommandStore.`
    *   *Correct (正确)*: `Replaced static shortcuts with dynamic shortcuts and implemented a secure launcher mechanism.`

2.  **Professional Terminology (专业术语规范)**:
    Keep relevant public format, service and platform names in English (e.g., `Markdown`, `S3`, `Android`, `API`, `SAF`). This does not permit internal implementation inventories.
    保留英文专业技术术语，不要强行直译。

3.  **Action-Oriented (动作导向描述)**:
    Start English sentences with active verbs in past tense (e.g., "Implemented...", "Optimized...", "Resolved...", "Refactored...", "Added...").
    在英文中，描述应以过去时态的动词开头（如 "Implemented", "Optimized" 等）。

4.  **Natural Translation (自然流畅的中文翻译)**:
    Avoid raw word-for-word translation. Rephrase sentences to read naturally in Chinese while preserving technical accuracy (e.g., using "杜绝了...隐患" for "preventing...", and "为...提供了更具弹性的底层架构" for "provides a resilient architecture for...").
    避免机械式的逐词翻译。在保留技术准确性的同时，使用符合中文阅读习惯的表达方式。
