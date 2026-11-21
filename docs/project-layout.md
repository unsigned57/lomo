# 项目目录与构建产物

源码按产品入口和模块职责组织，生成文件按用途隔离。模块权威与依赖方向由
[Architecture](../ARCHITECTURE.md) 定义；构建输出与门禁规则由
[Quality](../quality/README.md#generated-state-and-failure-triage) 定义。

## 源码与配置目录

| 目录 / 文件 | 职责 |
| --- | --- |
| `apps/android/` | Android 应用、Kotlin 模块（`app`/`data`/`domain`/`ui-components`/`native-bindings`）、Detekt 自定义规则、`benchmark` 与 `uitest` |
| `apps/tui/` | 桌面终端应用、PTY `uitest` 与系统打包配方 |
| `crates/` | Rust 领域核心、平台适配器、`lomo-architecture-tests` 与 `lomo-xtask` 编排工具 |
| `fixtures/` | 小型格式语料、characterization golden 与能力契约 |
| `quality/` | 质量契约、Detekt/Lint/deny 配置、工具版本与单一职责检查脚本 |
| `docs/` | 正式文档索引、`development/` 工程说明、发布日志与静态资源 |
| `.github/`、`.githooks/` | CI/发布工作流、PR 模板与 Git hooks |
| `target/` | Cargo 编译缓存与 Lomo 构建成品（Git 忽略） |
| `reports/` | 严格由工具写入的检查、覆盖率、性能与 UI 证据（Git 忽略） |
| `.cargo/` | Cargo 共享输出配置 `config.toml` 及被忽略的本地 `local.toml` |
| `.config/` | 共享的 `nextest.toml` 并发策略 |

根目录仅保留 README、许可证、架构与代理契约，以及 Cargo、just、Kotlin/Rust 工具链要求的入口配置。
`docs/` 只保留仍在生效的正式说明，不存放临时设计脚手架、审计台账或备份副本。

| 配置 | 位置与加载方式 |
| --- | --- |
| Rust 格式与 Clippy | 根目录 `.rustfmt.toml`、`.clippy.toml` |
| 共享编辑器格式 | 根目录 `.editorconfig` |
| Cargo 输出布局 | `.cargo/config.toml` 声明 `target/cargo`，xtask 读取同一声明 |
| 工具版本 | `quality/tools.toml`，由 `lomo-xtask` 读取 |
| Rust 依赖审计 | `quality/rust/deny.toml`，由门禁显式传入 `cargo-deny` |
| Android Lint | `quality/android/lint.xml`，由 Lint 脚本显式加载 |
| 本地 SDK 配置 | `apps/android/local.properties`（仅本机，Git 忽略） |

## 构建产物与 `reports/` 布局

```text
reports/                          # 仅工具写入，Git 忽略，cache clean 保留
├── verification/<ts-pid>/        # dev / check / ci 结果（自动保留最近 10 次）
├── detekt/                       # 模块静态分析报告与当前索引 symbols/
├── detekt-test-style/            # 测试规范 Detekt 报告
├── android-lint/                 # Android Lint XML/HTML 与生成工程描述
├── jacoco/                       # JaCoCo Kotlin 覆盖率报告
├── android-uitest/               # Android Maestro UI 测试结果
├── android-lan-uitest/           # 双机 LAN 分享 UI 测试结果
├── android-uisnap/               # uishot 屏幕快照（保留最近 20 份时间戳目录）
├── android-benchmark/            # Macrobenchmark 结果与 trace
├── android-device-perf/          # 设备性能各 plane 结果
├── device-perf-engine-baseline.v2.json  # 设备引擎基线快照
├── tui-uitest/                   # TUI PTY 测试结果
└── feasibility/                  # 主机可行性与性能基线 JSON

target/
├── cargo/                        # Cargo 编译缓存与各目标平台产物
└── lomo/
    ├── artifacts/                # 可交付成品：android/<debug|release>/、tui/<debug|release|dist>/
    ├── build/                    # 构建内部状态（shared kotlin、bindings/JNI 摘要、benchmark）
    ├── corpora/                  # `just perf` 生成的测试语料
    └── tmp/                      # 可重置的命令临时目录（7 天未修改自动清理）
```

禁止向 `reports/` 写入人工总结、审计 markdown、`reports/manual/` 或临时杂项；任务交接与验证结论直接写在对话响应、提交信息或 PR 描述中。

常用入口：

```bash
just build android debug|release
just build tui debug|release
just cache paths      # 返回解析后的绝对路径
just cache audit      # 审计各目录实际字节占用（含偏移本地构建目录）
just cache prune      # 按 cargo-sweep --time 7 清理 Cargo 缓存、7 天前 tmp/ 及超限 reports
just cache clean      # 删除编译输出及成品，保留 reports/ 与用户全局缓存
```

`CARGO_TARGET_DIR`、`LOMO_GENERATED_ROOT`、`LOMO_KOTLIN_BUILD_DIR` 与 `LOMO_REPORTS_DIR` 可分别独立覆盖对应根目录。`just build android|tui` 会在编译前自动执行 `just cache prune`；详细清理机制与限制见 [缓存清理决策](development/cache-cleanup.md)。`apps/android/app/jniLibs/` 与 `apps/android/native-bindings/src/` 由 xtask 生成并被 Git 忽略。
