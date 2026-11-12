# 项目目录与构建产物

源码按产品入口和模块职责组织，生成文件按用途归档。模块权威和依赖方向由
[Architecture](../ARCHITECTURE.md) 定义；构建输出规则由
[Quality](../quality/README.md#generated-state-and-failure-triage) 定义。

| 目录 | 职责 |
| --- | --- |
| `apps/android/` | Android 应用、Kotlin 模块和设备测试入口 |
| `apps/tui/` | 桌面终端应用、交互测试和平台打包配方 |
| `crates/` | Rust 领域模块、平台适配器和 `lomo-xtask` 构建工具 |
| `fixtures/` | 版本控制中的小型测试输入、golden 和能力契约 |
| `quality/` | 质量规范、静态分析配置和单一职责检查脚本 |
| `docs/` | 正式文档索引；`development/` 为工程说明，`assets/` 为静态图片 |
| `.github/`、`.githooks/` | CI、发布工作流和 Git hooks |
| `target/` | Cargo 编译输出、构建状态和成品，不提交到 Git |
| `reports/` | 按工具或任务归档的检查、性能、UI 和人工调查证据；整体被 Git 忽略 |
| `.cargo/` | Cargo 输出布局及被忽略的本地编译器设置 |
| `.config/` | nextest 等工具自动发现的项目配置 |

根目录保留 README、许可证、架构规范，以及 Cargo、just、Kotlin 等工具要求的入口和配置。
`docs/` 只保留仍有用途的正式内容；过期设计脚手架、审计元数据和备份副本不作为项目说明。
当前和历史诊断证据统一保存在根目录 `reports/`，手工命令日志放在 `reports/manual/<任务>/`。
Gradle、Cargo、Kotlin 和 XDG 依赖缓存继续复用调用者的标准目录；不要为单次任务新建缓存副本。

| 配置 | 位置与加载方式 |
| --- | --- |
| Rust 格式与 Clippy 规则 | 根目录 `.rustfmt.toml`、`.clippy.toml`，由工具自动发现 |
| Cargo 编译输出 | `.cargo/config.toml` 声明 `target/cargo`，xtask 读取同一声明 |
| 机器专用编译设置 | `.cargo/local.toml`，由共享配置可选包含，被 Git 忽略 |
| 工具版本 | `quality/tools.toml`，由 xtask 读取 |
| Rust 依赖审计 | `quality/rust/deny.toml`，由门禁显式传给 cargo-deny |
| Android Lint | `quality/android/lint.xml`，由 Lint 检查脚本显式加载 |
| Android 本地 SDK 配置 | `apps/android/local.properties`，仅保留在本机 |

## 去哪里找编译好的包

所有经过项目构建入口归档的可交付文件统一放在 `target/lomo/artifacts/`：

```text
reports/                          # 独立于构建目录的持久证据，Git 忽略
└── manual/<任务>/                # 手工命令 stdout / stderr 分开保存
target/
├── cargo/                         # Cargo 管理的编译缓存，内部结构保持原生
│   ├── debug/                     # 主机调试构建图
│   ├── release/                   # 主机发布构建图
│   ├── release-android/           # Android 交叉编译所需的主机工具
│   ├── <target-triple>/           # 各 ABI / 目标平台构建图
│   └── doc/                       # rustdoc 文档
└── lomo/
    ├── artifacts/                 # 可安装、可执行或可分发的成品
    │   ├── android/
    │   │   ├── debug/             # Lomo-<version>-<abi>.apk
    │   │   └── release/           # 通用包 lomo-v<version>-vc<code>.apk；分 ABI 包 Lomo-<version>-<abi>.apk
    │   └── tui/
    │       ├── debug/             # lomo / lomo.exe
    │       ├── release/           # lomo / lomo.exe
    │       └── dist/              # 发布压缩包、校验和
    ├── build/                     # 构建内部状态，不是可交付文件
    │   ├── bindings.json          # 绑定生成的输入 / 输出摘要
    │   ├── android-libraries.json # JNI 库构建来源摘要
    │   ├── kotlin/                # 所有 Kotlin 命令共用的构建输出
    │   ├── jni-stash/             # 打包时暂存未选中的 ABI 库
    │   └── android-benchmark/     # Gradle 测量 APK 和中间文件
    ├── corpora/                   # 生成的大型测试语料和设备测试数据
    └── tmp/                       # 命令拥有、可重置的临时工作目录
```

这些目录按需生成，空目录不提交。Cargo 的 `deps/`、`build/`、`.fingerprint/`、各 profile
及目标平台目录由 Cargo 管理，不手工重排内部文件。直接执行 `cargo build` 产生编译缓存；
使用以下项目入口会同时校验并归档成品：

```bash
just build android debug
just build android release
just build tui debug
just build tui release
just cache paths
```

`just cache paths` 返回实际绝对路径。`CARGO_TARGET_DIR` 单独覆盖 Cargo 编译缓存位置；
`LOMO_GENERATED_ROOT` 单独覆盖 Lomo 产物根目录（不改变 `reports/`）；`LOMO_KOTLIN_BUILD_DIR` 可覆盖共享 Kotlin
构建输出。依赖缓存仍使用标准工具目录，构建输出不再散落到根目录 `.kotlin/`。
Android 基准测量工具的 Gradle 输出位置由其构建配置声明。`jniLibs/` 和 `native-bindings/src/`
是 Android 模块所需的生成输入，仍位于对应模块旁并被 Git 忽略。

## 日常归档与清理

新报告放在 `reports/<工具或任务>/`，命令 stdout JSON 与 stderr 日志分别保存。
构建摘要放在 `build/`，临时解包和运行数据放在 `tmp/`，成品目录不接收日志或中间文件。
旧报告是历史证据，其中记录的旧绝对路径不代表当前查找规则。

`just cache audit` 按职责查看实际目录占用；`just cache prune` 清除过期 Cargo 构建文件并保留 Lomo 产物；
`just cache clean` 会删除编译输出及成品，保留根目录 `reports/`。报告保留期限由开发者管理；确认不再需要后再清理对应任务目录。

`just build android` 和 `just build tui` 在编译前自动复用 `just cache prune`：使用固定版本 `cargo-sweep --time 7`
按 fingerprint 使用时间清理 Cargo 文件，使用 `cargo clean` 清理覆盖率编译树。
`target/lomo/tmp/` 仅删除超过七天未修改的临时文件。
近期缓存、安装包、语料和根目录 `reports/` 保留；不扫描源码、用户全局依赖缓存或已安装 SDK。
清理期间持有 Cargo profile 锁，遇到其他编译或 I/O 错误会停止并说明原因。直接运行 `cargo build`
或 `./kotlin` 不触发此 hook。模块旁的旧 `.kotlin/`、`build/` 是已退役的输出位置，可以清理，
当前唯一共享 Kotlin 编译目录由 `just cache paths` 的 `kotlin_build` 返回。
