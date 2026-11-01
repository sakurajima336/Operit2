# Operit2 上游同步记录

本文件由本地构建流程维护，记录 fork（`sakurajima336/Operit2`）与上游（`AAswordman/Operit2`）的同步历史，以及分支与发布的约定。

## 分支约定

| 分支 | 用途 |
| --- | --- |
| `main` | 与上游同步后的稳定代码，**仅在本地构建验证通过后**才接受推送 |
| `preview` | 集成测试分支，上游同步先落这里，构建验证通过后再快进到 `main` |

工作流：

```text
upstream/main  --merge-->  preview  --本地构建通过-->  main
```

## 当前状态

| 项目 | 值 |
| --- | --- |
| 同步基线提交 | `c5380614`（首次合并 316 个上游提交） |
| 最新同步提交 | `8a4e8e1a` |
| 上次同步日期 | 2026-11-01 |
| 领先上游 | 0 个提交（完全对齐） |
| fork 独有提交 | 13 个（CI 与签名相关） |
| 应用版本 | `2.0.0` build 30 |
| CLI 版本 | `2.0.0-preview.24` |

## 同步历史

### 2026-11-01 · 第二轮（12 个上游提交）

合并 `upstream/main` 的 `e8828b89` 及之前 12 个提交，**无冲突**。

主要内容：

- **同步可靠性**：按实体 ID 路由初始化信封、透出 blob 存在性检查、修复并发文件变更重试、独立收敛文件
- **事务与偏好**：`feat(cli)` 将事务历史重置暴露为 sync 命令；偏好布局版本化后再同步
- **工作区与用量**：改进工作区导航、新增用量分析（`UsagePricing`/`UsageTimeline`）与文件树组件
- **本地模型**：细化本地模型设置，改进事务同步恢复
- **Linux**：修复 Flutter 裁剪下原生 webview 不可见的问题
- **代理**：为 peer 同步方法增加静默分发丢失的防护测试

### 2026-11-01 · 第一轮（316 个上游提交）

合并 `upstream/main` 的 `f476f435` 及之前 316 个提交。唯一冲突 `.github/workflows/android-flutter-build.yml`，已手工保留双方内容（fork 的「Install plugin build tools」+ 上游的「Cache source-built Android V8」）。

按类型统计（不含合并提交，共 264 个）：

| 类型 | 数量 |
| --- | --- |
| fix | 154 |
| feat | 58 |
| refactor | 14 |
| docs | 12 |
| chore | 11 |
| test | 7 |
| perf | 5 |
| build/ci | 3 |

主要特性：

- **设备空间与同步**：QR 配对、Space 审批简化、同步进度展示、跨节点文件流式传输、`node:` 路径跨设备文件工具
- **聊天与工作区**：逐轮编辑快照与回滚、guidance inbox 引导当前轮、侧边栏分组与置顶、独立父对话与对话树
- **插件体系**：OpenUI 渲染器、Compose 选择控件与表格/LaTeX 流式插件、微信机器人、QQBot 与 Waifu 原生集成
- **UI**：Markdown 语义化渲染重构、工具结果统一行渲染、用户与 AI 消息样式分离设置
- **本地模型**：llama.cpp 与 MNN 原生推理、持久化 USearch WASM 索引
- **Android**：**为 Android 从源码编译 V8**、Impeller 部分重绘、Impeller 后端构建开关
- **CLI**：TUI diff 查看器、网络监听与直连地址设置、Debian 12 容器构建

### fork 独有提交（不合入上游）

这些是本地构建链路需要的改动，保持在 fork 的 `main` 上：

| 提交 | 说明 |
| --- | --- |
| `8ebbe78c` | debug 构建使用与 release 相同的 keystore |
| `6ab9e323` | CI 在 Flutter 构建前生成 Dart proxy 产物 |
| `fd14ba75` | CI 每行写一条签名属性 |
| `89636b7f` | CI 写 local.properties 时不带 BOM |
| `5c84f6cc` | CI 把 release 签名写入 local.properties |
| `0c01ae09` | CI 复用任意一次运行中的 runtime 产物 |
| `6ea6031e` | CI 按 conclusion 扫描可复用的 runtime |
| `c952a6cf` | CI 在 pub get 前剥离 gitee 专属依赖 |
| `aed6db6f` | CI 让 runtime 产物跨运行复用 |
| `8e751bdd` | 新增可选构建类型的手动 Android 构建 workflow |
| `7853148b` | 修复 Android APK 的 CI 构建与启动 |

## 本地构建验证

同步到 `preview` 后必须完成以下验证，才能推送到 `main`：

1. `apps/flutter/app` 下 `flutter pub get` 成功
2. `python tools/build_scripts/build_flutter_android.py --build-name 2.0.0 --build-number 30` 产出 release APK
3. `flutter build apk --debug --target-platform android-arm64` 产出 debug APK
4. 两个 APK 的 `apksigner verify --print-certs` SHA-256 一致

最近一次验证结果（2026-11-01）：release 157 MB、debug 184.8 MB，证书 SHA-256 `cf8cb8559d6d2983f0c303ff41c266eb7a179baad99b7798789459a7573b5475`。

## 发布

| Release | Tag | 内容 |
| --- | --- | --- |
| Operit2 2.0.0-preview.23（本地构建） | `local-build-preview-23` | release APK、debug APK、V8 预编译缓存 |

V8 静态库（`librusty_v8.a`，179 MB）不进 git 仓库：超过 GitHub 单文件 100 MB 限制，且属于构建缓存。以 `operit2-android-v8-arm64-152.2.0.tar.gz`（35 MB）作为 Release 附件分发，解压到 `tools/release/work/android-v8/152.2.0/aarch64-linux-android/` 即可复用。

## 已知限制

**Android 版 V8 只能在 Linux 上编译。** Chromium 的 `build/config/BUILDCONFIG.gn` 硬断言 `host_os == "linux"`，因此 Windows 主机无法完成 Android APK 构建。构建需在 Ubuntu 主机上进行（见 `BUILD-LOCAL-UBUNTU.md`）。
