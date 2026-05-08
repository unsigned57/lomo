# 图库 Reels 重构计划

## Context

当前 `GalleryScreen` 复用了主页 `MemoCardList` —— 它只是一个"图片更多的 memo 列表"，不像图库。重构目标：

1. **网格视觉换成 Bento 动态拼贴**：按图片宽高比决定单元尺寸，偶有大图作视觉重点
2. **新建图库专属"Reels 详情页"**：从图库点图进入；上下滑切 memo、左右滑切同 memo 多图、底部渐变遮罩展示文案，可上拉读全文
3. **保持其他入口（主页 / Search / Tag / Daily Review）继续走现有 `ImageViewerScreen`** —— 图库与 ImageViewer 体验彻底独立归属

需求确认要点（已在 brainstorming 中拍板）：

- 网格 = Bento 拼贴，按图片宽高比决定大小；偶有 2×2 大图（节奏：每 8 个 memo 出一个）
- 网格单元 = 一个 memo（显示首图）；多图 memo 仅手动左右滑切图（不自动轮播、不显示角标）
- 详情页：上下滑 = 切 memo（首图复位），左右滑 = 切同 memo 多图
- 文案：背景满屏 + 底部渐变遮罩；折叠态摘要 + tags + 时间，上拉看全文（带菜单按钮）
- 详情菜单：复用 `MemoMenuBinder` 的编辑/删除/分享/跳转

## 推荐方案

### 模块新增

| 路径 | 责任 |
|---|---|
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryGridContent.kt` | Bento 网格 composable |
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryGridLayout.kt` | 纯函数：根据 memo + aspect 算出 cell 布局 |
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryImageDimensionResolver.kt` | 异步解析图片宽高（`BitmapFactory inJustDecodeBounds`），LRU 缓存 |
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelScreen.kt` | Reels 详情页主容器（VerticalPager × HorizontalPager）|
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelMemoOverlay.kt` | 底部渐变遮罩 + 三档可拖动 sheet |
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelOverlayState.kt` | sheet 三档锚点状态（`Hidden`/`Collapsed`/`Expanded`）|
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelRequest.kt` | 入参 data class + payload store |
| `app/src/test/java/com/lomo/app/feature/gallery/GalleryGridLayoutTest.kt` | aspect 边界 / highlight 节奏的单测 |
| `app/src/test/java/com/lomo/app/feature/gallery/GalleryReelPayloadStoreTest.kt` | payload store TTL/容量测试（仿 `ImageViewerRoutePayloadStoreTest.kt`）|

### 模块修改

| 路径 | 改动要点 |
|---|---|
| `app/src/main/java/com/lomo/app/feature/gallery/GalleryScreen.kt` | 把 `MemoCardList` 替换为 `GalleryGridContent`；`onImageClick` 改走 `onNavigateToGalleryReel(...)` |
| `app/src/main/java/com/lomo/app/navigation/NavRoute.kt` | 加 `data class GalleryReel(val payloadKey: String, val initialMemoIndex: Int, val initialImageIndex: Int)`；新增 `GalleryReelPayloadStore` |
| `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt` | 注册 `composable<NavRoute.GalleryReel>`；`rememberGalleryReelNavigationAction`；GalleryScreen 入口接入 |

### 关键复用（不动）

- `MainViewModel.galleryUiMemos`：已倒序、已过滤纯音频，详情页 `collectAsStateWithLifecycle` 直接拿
- `MemoMenuBinder`（feature/memo）：详情页菜单 100% 复用，scope 用既有 `MemoActionOrderScopes.GALLERY`
- `me.saket.telephoto.zoomable.ZoomableAsyncImage`：详情页大图用，缩放阈值 `IMAGE_VIEWER_SCROLL_LOCK_THRESHOLD` 模式直接照搬
- `MemoCardCollapsedSummaryPolicy`（ui-components/card）：折叠态摘要
- `LocalSharedTransitionScope` / `LocalAnimatedVisibilityScope`：网格 cell → 详情大图共享元素
- `ImageViewerRoutePayloadStore`：作为 `GalleryReelPayloadStore` 的实现模板（同套 LRU + TTL）

---

## 设计要点

### Bento 网格算法

容器：`LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(3))`（`androidx.compose.foundation.lazy.staggeredgrid`）。`LazyVerticalGrid` 强制等行高，会让横图所在行其他格出现空隙。

`GalleryCellLayout` 数据：

```kotlin
enum class GalleryAspectKind { Portrait, Square, Landscape }

data class GalleryCellLayout(
    val memoId: String,
    val firstImageUrl: String,
    val aspectKind: GalleryAspectKind,
    val aspectRatio: Float,    // width / height
    val isHighlight: Boolean,  // 触发 2×2 大图
)
```

Aspect 分档（实际值在 `GalleryGridLayout.kt` 里标为 `const val`）：

| 范围 | 类别 | span |
|---|---|---|
| `< 0.7` | Portrait | 单列、按 `aspectRatio` 控高（高于 1：1）|
| `0.7 ~ 1.4` | Square | 单列、近似 1:1 |
| `> 1.4` | Landscape | 单列；按 `aspectRatio` 控高（高 < 宽，cell 偏扁）；不跨列以免破坏 staggered 整列布局 |
| 任意但 `position % 8 == 0` 且为 Square | Highlight | `staggeredGridItemSpan = StaggeredGridItemSpan.FullLine`，aspect 按 1:1 高 |

> 备注：Bento "大图"在 staggered 中以 `FullLine` 实现而不是 2×2，因为 staggered grid 不支持任意矩形跨；FullLine 已能制造出"偶尔出大图"的视觉重点，性能与回收最稳。

`planGalleryLayout(memos, aspects, highlightStride = 8)` 是纯函数，可单测。

### 图片宽高解析

`GalleryImageDimensionResolver`：
- `Map<String, Float>` LRU（最大 256 个）
- 解析时 `withContext(Dispatchers.IO)` 调 `BitmapFactory.decodeFile(path, Options{ inJustDecodeBounds = true })`
- 未命中：先返回 `1f`（Square），异步解析完成后通过 `mutableStateMapOf` 触发 recomposition 重排
- Coil URL（远端图）目前不在 gallery 范围（gallery 只接本地图） —— `GalleryScreen` 上游已经过滤；保险起见对解析失败也回落到 `1f`

### Reels 详情页

组件树：

```
GalleryReelScreen(memos: ImmutableList<MemoUiModel>, initialMemoIndex, initialImageIndex)
└─ MemoMenuBinder(... scope = GALLERY)
   └─ Box(背景 = Color.Black, fillMaxSize)
      ├─ VerticalPager(state, beyondViewportPageCount = 1, userScrollEnabled = !isZoomed)
      │   └─ GalleryReelPage(memo, isActive, onShowMenu)
      │       ├─ HorizontalPager(state, userScrollEnabled = !isZoomed && imageUrls.size > 1)
      │       │   └─ ZoomableAsyncImage(... onZoomFractionChanged = ...)
      │       ├─ GalleryReelImageDots(... 仅多图时显示)
      │       └─ GalleryReelMemoOverlay(memo, anchor, onShowMenu)
      └─ GalleryReelTopBar(onClose, onShowMenu)
```

手势优先级（参照 `ImageViewerScreen.kt`）：
- `zoomFraction > 0.01`：禁用所有 pager 滚动
- 仅多图时启用内层 `HorizontalPager`
- 切到新 memo 时 `HorizontalPager` 重置到 0

### 底部 Overlay

不用 `ModalBottomSheet`（默认会有 scrim 遮背景图）。自实现：

- `AnchoredDraggableState<GalleryReelOverlayAnchor>` 三档：`Hidden` / `Collapsed`（默认）/ `Expanded`
- 渐变遮罩：`Brush.verticalGradient(0f to Transparent, 1f to Black.copy(alpha = 0.7f))`，覆盖底部 ~40% 高度
- `Collapsed`：摘要（用 `MemoCardCollapsedSummaryPolicy`）+ tags + 时间
- `Expanded`：完整 `processedContent` markdown + tags + 时间 + "更多 (overflow)"按钮（点击触发 `MemoMenuBinder` 的 `showMenu`）
- 系统返回键：`Expanded` → `Collapsed` → `popBackStack`

### Payload Store

避免 navigation arg 过大：

```kotlin
object GalleryReelPayloadStore {
    data class Payload(
        val memoIds: List<String>,                  // 打开瞬间快照
        val aspectByMemoId: Map<String, Float>,     // 解析过的 aspect 一并带过去，避免详情页再解析
    )
    fun put(payload: Payload): String
    fun get(key: String): Payload?
    // LRU + TTL（10 分钟）+ 64 上限，仿 ImageViewerRoutePayloadStore
}
```

详情页用 `payload.memoIds` 与 `MainViewModel.galleryUiMemos` 取交集恢复成 `List<MemoUiModel>`，按快照顺序排（避免后台数据刷新跳页）。

### SharedTransition

入场：`GalleryGridContent` 中每个 cell 的首图给 `Modifier.sharedElement(rememberSharedContentState(key = "gallery:$memoId:0"), animatedVisibilityScope = LocalAnimatedVisibilityScope.current!!)`；详情页 `ZoomableAsyncImage`（仅 `initialMemoIndex / initialImageIndex` 那一张）使用同 key。已在 `LomoNavHost` 中铺好 `SharedTransitionLayout`。

### i18n

需要新增字符串（同时改 `values/` 和 `values-zh-rCN/`）：
- `gallery_reel_close` / "Close" / "关闭"
- `gallery_reel_image_indicator` / "%1$d / %2$d" / "%1$d / %2$d"
- `gallery_reel_overlay_summary_more` / "Tap for full memo" / "点击展开备忘录"

---

## 任务拆解

### Task 1：Bento 布局纯函数 + 单测

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryGridLayout.kt`
- Create: `app/src/test/java/com/lomo/app/feature/gallery/GalleryGridLayoutTest.kt`

- [ ] Step 1：写 aspect 边界 + highlight 节奏的失败测试（覆盖 0.69/0.7/1.4/1.41 边界、第 0/8/16 个 memo 的 highlight）
- [ ] Step 2：实现 `planGalleryLayout`、`GalleryAspectKind`、`GalleryCellLayout`
- [ ] Step 3：测试通过；运行 `./gradlew :app:testDebugUnitTest --tests "*GalleryGridLayoutTest*"`
- [ ] Step 4：提交 `feat(gallery): introduce bento layout planner`

### Task 2：图片尺寸解析

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryImageDimensionResolver.kt`

- [ ] Step 1：实现 `class GalleryImageDimensionResolver` —— LRU `LinkedHashMap` 256，`suspend fun resolve(path: String): Float`，未命中时 `BitmapFactory inJustDecodeBounds`
- [ ] Step 2：暴露 `aspectFlow: StateFlow<ImmutableMap<String, Float>>`，UI 层 collect 触发重排
- [ ] Step 3：解析失败回落 `1f`，写日志（`android.util.Log.w`）
- [ ] Step 4：提交 `feat(gallery): add image dimension resolver`

### Task 3：Bento 网格 composable

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryGridContent.kt`

- [ ] Step 1：`@Composable fun GalleryGridContent(memos, aspectMap, contentPadding, onCellClick: (memoId, imageIndex) -> Unit, onShowMenu)`
- [ ] Step 2：用 `LazyVerticalStaggeredGrid` + `planGalleryLayout` 渲染；每 cell 用 `Modifier.aspectRatio(layout.aspectRatio)` 控高，内层 `HorizontalPager`（`userScrollEnabled = imageUrls.size > 1`），单图用 `coil.compose.AsyncImage`（`ContentScale.Crop`，圆角 12dp，无缩放手势 —— 缩放是详情页的事）
- [ ] Step 3：长按调起 `MemoMenuState`（与现 `MemoCardList` 等价）
- [ ] Step 4：单元做 `Modifier.combinedClickable(onClick = onCellClick(...), onLongClick = ...)`、`Modifier.sharedElement(key = "gallery:$memoId:${pagerState.currentPage}")`
- [ ] Step 5：提交 `feat(gallery): replace MemoCardList with bento grid`

### Task 4：Payload Store + 单测

**Files:**
- Modify: `app/src/main/java/com/lomo/app/navigation/NavRoute.kt`
- Create: `app/src/test/java/com/lomo/app/navigation/GalleryReelPayloadStoreTest.kt`

- [ ] Step 1：抄 `ImageViewerRoutePayloadStoreTest.kt` 写 TTL / 容量 / 顺序测试，先 fail
- [ ] Step 2：在 `NavRoute.kt` 加 `data class GalleryReel(...)` + `object GalleryReelPayloadStore`
- [ ] Step 3：测试通过：`./gradlew :app:testDebugUnitTest --tests "*GalleryReelPayloadStoreTest*"`
- [ ] Step 4：提交 `feat(navigation): add gallery reel route + payload store`

### Task 5：Overlay 状态 + 拖动锚点

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelOverlayState.kt`
- Create: `app/src/test/java/com/lomo/app/feature/gallery/GalleryReelOverlayStateTest.kt`

- [ ] Step 1：测试 `nextAnchorOnBack(current)` —— Expanded → Collapsed → Hidden → Hidden（最后一档触发外部 popBack）
- [ ] Step 2：实现 `enum GalleryReelOverlayAnchor { Hidden, Collapsed, Expanded }` + `nextAnchorOnBack`
- [ ] Step 3：测通过；提交 `feat(gallery): overlay anchor state machine`

### Task 6：底部 Overlay composable

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelMemoOverlay.kt`

- [ ] Step 1：`@Composable fun GalleryReelMemoOverlay(memo, draggableState, onShowMoreMenu)` —— `AnchoredDraggableState`，渐变遮罩，三档高度 `0.dp / 200.dp / fillMaxHeight 80%`
- [ ] Step 2：折叠用 `MemoCardCollapsedSummaryPolicy.summarize(...)`；展开渲染 markdown（用 `precomputedRenderPlan`）
- [ ] Step 3：在 `Expanded` 状态显示 overflow 按钮，点击调用 `onShowMoreMenu()` → 上抛 `MemoMenuState`
- [ ] Step 4：提交 `feat(gallery): reel memo overlay`

### Task 7：Reels 详情页主容器

**Files:**
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelScreen.kt`
- Create: `app/src/main/java/com/lomo/app/feature/gallery/GalleryReelRequest.kt`

- [ ] Step 1：`GalleryReelRequest(memos: ImmutableList<MemoUiModel>, initialMemoIndex: Int, initialImageIndex: Int)`
- [ ] Step 2：`GalleryReelScreen` 主结构 —— `Box(Color.Black) { VerticalPager { GalleryReelPage } + GalleryReelTopBar }`
- [ ] Step 3：`GalleryReelPage` —— 内层 `HorizontalPager` + `ZoomableAsyncImage`（参照 `ImageViewerPager` 的 `zoomFraction` 阈值锁定）
- [ ] Step 4：把 `GalleryReelMemoOverlay` 嵌进 `BoxScope.align(BottomCenter)`，与 pager 同层但 `Modifier.zIndex(1f)`
- [ ] Step 5：返回键拦截 —— `Expanded` → `Collapsed`，否则 popBack；`BackHandler` 实现
- [ ] Step 6：用 `MemoMenuBinder { showMenu -> ... }` 包裹整体，`scope = MemoActionOrderScopes.GALLERY`
- [ ] Step 7：提交 `feat(gallery): reel detail screen`

### Task 8：导航接入

**Files:**
- Modify: `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
- Modify: `app/src/main/java/com/lomo/app/feature/gallery/GalleryScreen.kt`

- [ ] Step 1：`LomoNavHost.kt` 加 `rememberGalleryReelNavigationAction(navController) -> (memoId, imageIndex) -> Unit` —— 从 `MainViewModel.galleryUiMemos.value` 拿当前列表 + aspect map → `GalleryReelPayloadStore.put(...)` → `navController.navigate(GalleryReel(...))`
- [ ] Step 2：在 `addSecondaryDestinations` 内 `composable<NavRoute.GalleryReel>` 注册：从 store 取 payload → 还原 `List<MemoUiModel>` → `GalleryReelScreen`
- [ ] Step 3：`GalleryScreen.kt`：参数从 `onNavigateToImage: (ImageViewerRequest) -> Unit` 改为 `onNavigateToReel: (memoId: String, imageIndex: Int) -> Unit`；其他入口不变
- [ ] Step 4：用 `LomoNavHost` 既有 `LocalSharedTransitionScope/LocalAnimatedVisibilityScope` 包裹 reel route
- [ ] Step 5：提交 `feat(gallery): wire reel route through nav host`

### Task 9：i18n 字符串

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] Step 1：加 `gallery_reel_close`、`gallery_reel_image_indicator`、`gallery_reel_overlay_summary_more` 三对
- [ ] Step 2：提交 `feat(gallery): i18n strings for reel`

### Task 10：架构边界检查 + 全量质检

**Files:**（无新建）

- [ ] Step 1：`./gradlew :app:testDebugUnitTest --tests "*architecture*AppLayerBoundaryTest*"` —— 确认未引入 `com.lomo.data.*`
- [ ] Step 2：`./gradlew fastQualityCheck`（迭代验）
- [ ] Step 3：`./gradlew qualityCheck`（提交前的最终全量门）
- [ ] Step 4：手动验证（见下方 Verification 节）

---

## 边界条件清单

- **空 gallery**：保留现有 `EmptyState`
- **memo 只有音频附件**：上游 `galleryUiMemos` 已过滤，不会出现
- **远端图（http(s)://）**：当前 gallery 只走本地图；resolver 对非 file 路径回落 `1f`
- **图未解析时打开详情**：详情页用 `ZoomableAsyncImage` + `ContentScale.Fit`，与 `ImageViewerScreen` 一致
- **打开详情后台 memo 列表刷新**：详情页用快照（payload 中的 `memoIds`）锁定顺序；新 memo 不会插入打乱
- **memo 在详情页被删除**：监听 `galleryUiMemos`，发现当前 page 的 memoId 已不在列表 → 自动 `popBackStack` 并 toast（用 `MainViewModel.errorMessage` 统一通道）
- **缩放中切 memo 切图**：与 `ImageViewerPager` 一致，缩放时 `userScrollEnabled = false`
- **横屏**：staggered grid 自适应；详情页 VerticalPager 在横屏下仍是上下滑（与抖音横屏一致）
- **配置变更**：payload store 的 TTL = 10 min 足够覆盖；`rememberSaveable` 持久化 pager state

---

## Verification

### 自动化

- `./gradlew fastQualityCheck` — 迭代质检
- `./gradlew qualityCheck` — 提交前全量门（任何提交前都要跑一次）
- `./gradlew :app:testDebugUnitTest --tests "*Gallery*"` — 本次新增测试聚焦
- `./gradlew :app:testDebugUnitTest --tests "*architecture*"` — 架构边界

### 手动（按顺序在真机或模拟器跑）

1. **Bento 网格基础**：`/gallery` 入口打开；确认 3 列、横图扁、竖图高、约每 8 个 memo 出一次大图
2. **多图轮播**：找一个 ≥3 图 memo 的 cell，左右滑切图；切回上一格再切回，pager 状态正确（同 memo 内位置保留）
3. **进入详情 SharedTransition**：点 cell 中的图 → 详情页能见到该 memo 该图作为初始页，转场为放大动画
4. **详情上下滑**：上滑 → 进入下一条 memo（首图复位、overlay 复位 Collapsed）；下滑 → 上一条 memo
5. **详情左右滑**：在多图 memo 上左右滑切图；切完后上下滑应再切 memo（不会"卡"在最后/第一张图）
6. **缩放锁定**：双指放大某张图；上下/左右滑动都不应触发翻页
7. **底部 overlay**：默认 Collapsed → 上拉 Expanded（看到全文 + tags + 时间 + 更多按钮）→ 返回键回 Collapsed → 再返回回退到图库
8. **菜单**：Expanded 状态点更多按钮 → 出现 MemoMenu；编辑 → 跳回主页定位到该 memo；删除 → 详情自动 popBack 回图库
9. **i18n**：切换系统语言到简中，再走一遍流程，所有新字符串显示正确
10. **其他入口未受影响**：从主页 / Search / Tag / Daily Review 点图，仍进入旧 `ImageViewerScreen`（黑底 + 仅图）

### 性能 / 稳定性

- 滚动 Bento 网格 60fps（用 `Profile GPU rendering`，没有明显掉帧）
- 详情页连续上下滑 20+ 条不卡顿、内存稳定（`adb shell dumpsys meminfo`）
- 内存压力下 payload store 不泄漏（已有 LRU + TTL）

---

## Architecture Impact

仅 `app` 层改动，未触碰 `domain` / `data` / `ui-components`：
- `app` 不导入 `com.lomo.data.*` —— 维持
- 通过既有 `MemoUiCoordinator` / `MainViewModel.galleryUiMemos` 拿数据，不直接接触 repository 实现
- 新组件落在 `feature/gallery/`，符合 README 的"specialized screen entrypoints"约定
