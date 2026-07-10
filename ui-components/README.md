# UI Components Module Index

> Responsibilities and boundaries: see [ARCHITECTURE.md](../ARCHITECTURE.md). Contribution rules: see [AGENTS.md](../AGENTS.md).
> This file is a task map for locating code, not a source of truth — verify paths and APIs against the codebase before acting.

This module provides reusable UI infrastructure shared across app features. It contains shared Compose components, memo rendering, input surfaces, theme tokens, and text/layout helpers.

## Start Here

- `src/component/input/InputSheet.kt`
  - Shared memo input shell, input interception, focus and dismiss flow, recording/image hooks, and display-mode handling.
- `src/component/card/MemoCard.kt`
  - Shared memo presentation surface with header/body/footer composition, collapse behavior, tag display, and interaction wiring.
- `src/component/markdown/ModernMarkdownRenderer.kt`
  - Async markdown render-plan pipeline with fallback rendering while plans are computed.
- `src/theme/Theme.kt`
  - Shared theme root, dynamic color handling, dark-mode resolution, animated color transitions, and typography integration.

## Key Subdirectories

- `component/input`
  - Input sheet, editor bridge, focus helpers, toolbar parts, presentation state, recording panel, tag selector.
- `component/card`
  - Memo-card presentation and collapse/summary policy.
- `component/markdown`
  - Markdown parser, AST/render-plan support, known-tag stripping, fallback rendering, modern renderer pipeline.
- `component/menu`, `component/dialog`, `component/navigation`, `component/settings`, `component/stats`, `component/media`
  - Reusable primitives consumed by app features.
- `text`
  - Script-aware text rendering, paragraph layout, selection-handle styling, spacing normalization, raw paragraph composition.
- `theme`
  - Color, type, spacing, shapes, motion, and theme resolution.
- `util`
  - Shared UI-scoped helpers such as haptic locals or shared transition locals.

## Common Tasks

### Change shared typography, spacing, shape, or motion

Start in `theme/`:

- `Type.kt`
- `MemoTypography.kt`
- `AppSpacing.kt`
- `AppShapes.kt`
- `MotionTokens.kt`
- `ExpressiveMotion.kt`
- `Theme.kt`

### Change memo card appearance or collapse behavior

Start in:

- `component/card/MemoCard.kt`
- nearby `MemoCard*` support files

### Change markdown rendering or tag stripping

Start in:

- `component/markdown/ModernMarkdownRenderer.kt`
- `ModernMarkdownRenderPlan*`
- `MarkdownParser.kt`
- `MarkdownKnownTagFilter.kt`

### Change input-sheet interaction

Start in:

- `component/input/InputSheet.kt`
- `InputSheetContent.kt`
- `InputSheetPresentationState.kt`
- focus and bridge helpers when the change is editor/platform-specific

### Add a new reusable cross-feature component

Place it under the closest `component/<area>` package. If it starts needing feature-specific state or navigation knowledge, it belongs in `app` instead — switch to `app/README.md`.

## High-Risk Areas

- `component/input`
  - Focus, IME, dismiss timing, and edit-text bridge behavior are platform-sensitive.
- `text`
  - Script-aware rendering and selection behavior can regress subtly across devices.
- `component/markdown`
  - Render-plan performance and behavior correctness both matter.
